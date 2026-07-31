#!/usr/bin/env python3
"""Prepare and run a production Fabric profile against the exact release JAR.

The harness never mutates the user's launcher profile. It consumes the already
installed Minecraft 26.2/Fabric 0.19.3 libraries and official Java 25 runtime,
then builds an isolated game directory under build/exact-jar-qa.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
import zipfile
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Optional


MC_VERSION = "26.2"
FABRIC_PROFILE = "fabric-loader-0.19.3-26.2"
FABRIC_LOADER_VERSION = "0.19.3"
REQUIRED_IRIS_VERSION = "1.11.2+mc26.2"
REQUIRED_SODIUM_VERSION = "0.9.1+mc26.2"
DEFAULT_SHADER_PACK = "ComplementaryReimagined_r5.8.1.zip"
EXPECTED_COMPLEMENTARY_PROGRAMS = 76
EXPECTED_COMPLEMENTARY_STAGES = 152
SUPPORTED_BACKENDS = ("metal4", "metal3")
QA_MOD_ID = "metalrender-exact-jar-qa"
FORBIDDEN_RUNTIME_DIAGNOSTICS = (
    "GPU_ERROR:",
    "Frame skipped: in-flight GPU limit timed out",
    "Frame skipped: no IOSurface slot is safe for Metal",
    "[iosurface] frame not ready after bounded wait; presentation skipped",
    "[iosurface] fastpath fail",
    "[iosurface] fastpath off",
    "[iosurface] composite eww",
    "[iosurface] read fbo bad",
    "[iosurface] inter fbo bad",
    "[iosurface] inter fbo setup bad",
    "[iosurface] glblit err",
    "[iosurface] deferred gl err",
    "[MetalRender] WARN: Pipeline cache",
)
SANITIZED_ENVIRONMENT_VARIABLES = (
    "ASAN_OPTIONS",
    "CLASSPATH",
    "DYLD_FRAMEWORK_PATH",
    "DYLD_INSERT_LIBRARIES",
    "DYLD_LIBRARY_PATH",
    "JDK_JAVA_OPTIONS",
    "JAVA_HOME",
    "JAVA_TOOL_OPTIONS",
    "LSAN_OPTIONS",
    "METALRENDER_DYLD_INSERT_LIBRARIES",
    "_JAVA_OPTIONS",
)

PROJECT = Path(__file__).resolve().parents[1]
HARNESS_SOURCES = PROJECT / "scripts" / "exact-jar-qa"
DEFAULT_RUNTIME = PROJECT / "build" / "exact-jar-qa"


class HarnessError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise HarnessError(f"could not read JSON {path}: {error}") from error


def read_mod_metadata(path: Path) -> dict[str, Any]:
    try:
        with zipfile.ZipFile(path) as archive:
            return json.loads(
                archive.read("fabric.mod.json").decode("utf-8")
            )
    except Exception as error:
        raise HarnessError(
            f"could not read fabric.mod.json from {path}: {error}"
        ) from error


def require_file(path: Path, label: str) -> Path:
    path = path.expanduser().resolve()
    if not path.is_file():
        raise HarnessError(f"{label} is missing: {path}")
    return path


def safe_runtime_path(path: Path) -> Path:
    path = path.expanduser().resolve()
    build_root = (PROJECT / "build").resolve()
    if path == build_root or build_root not in path.parents:
        raise HarnessError(
            f"runtime directory must be a child of {build_root}: {path}"
        )
    return path


@contextmanager
def runtime_lock(path: Path):
    """Serialize all profiles because runtime directories may be nested."""
    runtime = safe_runtime_path(path)
    build_root = (PROJECT / "build").resolve()
    build_root.mkdir(parents=True, exist_ok=True)
    lock_path = build_root / ".exact-jar-qa.lock"
    lock_stream = lock_path.open("a+", encoding="utf-8")
    try:
        try:
            fcntl.flock(lock_stream.fileno(),
                        fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise HarnessError(
                f"another exact-JAR QA owns the project build tree; "
                f"lock: {lock_path}"
            ) from error
        lock_stream.seek(0)
        lock_stream.truncate()
        lock_stream.write(f"pid={os.getpid()}\nruntime={runtime}\n")
        lock_stream.flush()
        yield
    finally:
        fcntl.flock(lock_stream.fileno(), fcntl.LOCK_UN)
        lock_stream.close()


def normalize_arch(value: str) -> str:
    value = value.lower()
    if value in {"arm64", "aarch64"}:
        return "arm64"
    if value in {"x86_64", "amd64"}:
        return "x86_64"
    return value


def rule_matches(rule: dict[str, Any]) -> bool:
    os_rule = rule.get("os")
    if os_rule:
        if os_rule.get("name") not in {None, "osx"}:
            return False
        required_arch = os_rule.get("arch")
        if required_arch and normalize_arch(required_arch) != normalize_arch(
                platform.machine()):
            return False
        version_pattern = os_rule.get("version")
        if version_pattern and not re.search(
                version_pattern, platform.mac_ver()[0]):
            return False
    features = rule.get("features")
    if features:
        # The exact-JAR harness does not request demo, custom resolution, or
        # quick-play features from rule-gated library entries.
        return False
    return True


def library_allowed(library: dict[str, Any]) -> bool:
    rules = library.get("rules") or []
    allowed = not rules
    for rule in rules:
        if rule_matches(rule):
            allowed = rule.get("action") == "allow"
    return allowed


def coordinate_path(name: str) -> Path:
    parts = name.split(":")
    if len(parts) not in {3, 4}:
        raise HarnessError(f"unsupported Maven coordinate: {name}")
    group, artifact, version = parts[:3]
    classifier = f"-{parts[3]}" if len(parts) == 4 else ""
    return (
        Path(*group.split(".")) / artifact / version /
        f"{artifact}-{version}{classifier}.jar"
    )


def resolve_libraries(
    minecraft_home: Path,
    vanilla: dict[str, Any],
    fabric: dict[str, Any],
) -> list[Path]:
    libraries_root = minecraft_home / "libraries"
    result: list[Path] = []
    seen: set[Path] = set()

    for library in [*fabric.get("libraries", []),
                    *vanilla.get("libraries", [])]:
        if not library_allowed(library):
            continue
        artifact = (library.get("downloads") or {}).get("artifact")
        relative = (
            Path(artifact["path"]) if artifact and artifact.get("path")
            else coordinate_path(library["name"])
        )
        path = require_file(
            libraries_root / relative,
            f"launcher library {library['name']}",
        )
        expected_sha1 = (
            artifact.get("sha1") if artifact else library.get("sha1")
        )
        if expected_sha1 and sha1(path) != expected_sha1:
            raise HarnessError(
                f"SHA-1 mismatch for launcher library {path}"
            )
        if path not in seen:
            result.append(path)
            seen.add(path)

    game_jar = require_file(
        minecraft_home / "versions" / MC_VERSION /
        f"{MC_VERSION}.jar",
        "Minecraft client JAR",
    )
    result.append(game_jar)
    return result


def find_mod(
    mods_directory: Path,
    mod_id: str,
    required_version: Optional[str] = None,
) -> tuple[Path, dict[str, Any]]:
    matches: list[tuple[Path, dict[str, Any]]] = []
    for candidate in sorted(mods_directory.glob("*.jar")):
        try:
            metadata = read_mod_metadata(candidate)
        except HarnessError:
            continue
        if metadata.get("id") == mod_id:
            matches.append((candidate.resolve(), metadata))
    if required_version:
        matches = [
            match for match in matches
            if match[1].get("version") == required_version
        ]
    if len(matches) != 1:
        versions = ", ".join(
            f"{path.name}:{metadata.get('version')}"
            for path, metadata in matches
        ) or "none"
        raise HarnessError(
            f"expected exactly one {mod_id} {required_version or ''} "
            f"in {mods_directory}, found {versions}"
        )
    return matches[0]


def paired_module_version(home: Path, fabric_api_version: str,
                          artifact_id: str) -> str:
    aggregate_root = (
        home / ".gradle" / "caches" / "modules-2" / "files-2.1" /
        "net.fabricmc.fabric-api" / "fabric-api" / fabric_api_version
    )
    versions: set[str] = set()
    for pom in aggregate_root.glob("*/*.pom"):
        try:
            root = ET.parse(pom).getroot()
        except (ET.ParseError, OSError) as error:
            raise HarnessError(
                f"could not parse configured Fabric API POM {pom}: {error}"
            ) from error
        for dependency in root.iter():
            if dependency.tag.rsplit("}", 1)[-1] != "dependency":
                continue
            values = {
                child.tag.rsplit("}", 1)[-1]: (child.text or "").strip()
                for child in dependency
            }
            if (values.get("groupId") == "net.fabricmc.fabric-api"
                    and values.get("artifactId") == artifact_id
                    and values.get("version")):
                versions.add(values["version"])
    if len(versions) != 1:
        raise HarnessError(
            f"configured Fabric API {fabric_api_version} does not resolve "
            f"exactly one {artifact_id} version; found "
            + (", ".join(sorted(versions)) or "none")
        )
    return versions.pop()


def find_client_gametest_api(
    home: Path,
    fabric_api_version: str,
) -> tuple[Path, dict[str, Any]]:
    paired_version = paired_module_version(
        home, fabric_api_version, "fabric-client-gametest-api-v1")
    root = (
        home / ".gradle" / "caches" / "modules-2" / "files-2.1" /
        "net.fabricmc.fabric-api" / "fabric-client-gametest-api-v1" /
        paired_version
    )
    matches: list[tuple[Path, dict[str, Any]]] = []
    for candidate in root.glob("*/*.jar"):
        try:
            metadata = read_mod_metadata(candidate)
        except HarnessError:
            continue
        if metadata.get("id") == "fabric-client-gametest-api-v1":
            matches.append((candidate.resolve(), metadata))
    if len(matches) != 1:
        raise HarnessError(
            "expected one cached Fabric client game-test API JAR paired with "
            f"Fabric API {fabric_api_version} ({paired_version}), found "
            + ", ".join(str(item[0]) for item in matches)
        )
    return matches[0]


def parse_project_property(name: str) -> str:
    properties = PROJECT / "gradle.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    raise HarnessError(f"missing {name} in {properties}")


def java_home(minecraft_home: Path) -> Path:
    return (
        minecraft_home / "runtime" / "java-runtime-epsilon" /
        "mac-os-arm64" / "java-runtime-epsilon" /
        "jre.bundle" / "Contents" / "Home"
    ).resolve()


def run_checked(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess:
    kwargs.setdefault("env", clean_environment())
    result = subprocess.run(
        command,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        **kwargs,
    )
    if result.returncode != 0:
        raise HarnessError(
            f"command failed ({result.returncode}): "
            f"{' '.join(command)}\n{result.stdout}"
        )
    return result


def clean_environment() -> dict[str, str]:
    environment = os.environ.copy()
    for name in SANITIZED_ENVIRONMENT_VARIABLES:
        environment.pop(name, None)
    for name in tuple(environment):
        if name.startswith("METALRENDER_"):
            environment.pop(name, None)
    return environment


def compile_driver(
    staging: Path,
    java: Path,
    classpath: list[Path],
    exact_jar: Path,
    iris: Path,
    gametest_api: Path,
) -> Path:
    classes = staging / "driver-classes"
    classes.mkdir(parents=True)
    compile_classpath = os.pathsep.join(
        str(item) for item in
        [*classpath, exact_jar, iris, gametest_api]
    )
    run_checked([
        str(java / "bin" / "javac"),
        "--release", "25",
        "-encoding", "UTF-8",
        "-classpath", compile_classpath,
        "-d", str(classes),
        str(HARNESS_SOURCES / "ExactJarClientGameTest.java"),
    ])
    driver = staging / "metalrender-exact-jar-qa-driver.jar"
    run_checked([
        str(java / "bin" / "jar"),
        "--create",
        "--file", str(driver),
        "-C", str(classes), ".",
        "-C", str(HARNESS_SOURCES), "fabric.mod.json",
    ])
    return driver


def java_version(java: Path) -> str:
    output = run_checked([str(java / "bin" / "java"), "-version"]).stdout
    match = re.search(r'version "([^"]+)"', output)
    if not match or not match.group(1).startswith("25."):
        raise HarnessError(
            f"official launcher runtime is not Java 25:\n{output}"
        )
    return match.group(1)


def prepare(args: argparse.Namespace) -> dict[str, Any]:
    if platform.system() != "Darwin" or normalize_arch(
            platform.machine()) != "arm64":
        raise HarnessError("exact-JAR QA requires Apple Silicon macOS")

    runtime = safe_runtime_path(Path(args.runtime_dir))
    release_jar = require_file(Path(args.jar), "exact release JAR")
    release_metadata = read_mod_metadata(release_jar)
    if release_metadata.get("id") != "metalrender":
        raise HarnessError(f"not a MetalRender JAR: {release_jar}")
    expected_release_version = parse_project_property("mod_version")
    if release_metadata.get("version") != expected_release_version:
        raise HarnessError(
            "release JAR version does not match project mod_version: "
            f"expected {expected_release_version}, got "
            f"{release_metadata.get('version')}"
        )
    if release_metadata.get("depends", {}).get("minecraft") != MC_VERSION:
        raise HarnessError(
            f"release JAR does not target Minecraft {MC_VERSION}"
        )

    minecraft_home = Path(args.minecraft_home).expanduser().resolve()
    vanilla_json = require_file(
        minecraft_home / "versions" / MC_VERSION /
        f"{MC_VERSION}.json",
        "Minecraft version JSON",
    )
    fabric_json = require_file(
        minecraft_home / "versions" / FABRIC_PROFILE /
        f"{FABRIC_PROFILE}.json",
        "Fabric launcher profile JSON",
    )
    vanilla = read_json(vanilla_json)
    fabric = read_json(fabric_json)
    if fabric.get("mainClass") != (
            "net.fabricmc.loader.impl.launch.knot.KnotClient"):
        raise HarnessError("Fabric profile has an unexpected main class")

    java = java_home(minecraft_home)
    require_file(java / "bin" / "java", "official Java executable")
    require_file(java / "bin" / "javac", "official javac executable")
    java_runtime_version = java_version(java)
    classpath = resolve_libraries(minecraft_home, vanilla, fabric)
    minecraft_client = vanilla.get("downloads", {}).get("client", {})
    if (minecraft_client.get("sha1") and
            sha1(classpath[-1]) != minecraft_client["sha1"]):
        raise HarnessError("Minecraft client JAR SHA-1 does not match JSON")
    if (minecraft_client.get("size") and
            classpath[-1].stat().st_size != minecraft_client["size"]):
        raise HarnessError("Minecraft client JAR size does not match JSON")
    expected_loader_coordinate = (
        f"net.fabricmc:fabric-loader:{FABRIC_LOADER_VERSION}")
    if expected_loader_coordinate not in {
            library.get("name") for library in fabric.get("libraries", [])}:
        raise HarnessError(
            "Fabric profile does not attest the required loader version")
    if any(
        "build/classes" in str(item) or "build/resources" in str(item)
        for item in classpath
    ):
        raise HarnessError("source-set output leaked into runtime classpath")
    if any(item.suffix != ".jar" for item in classpath):
        raise HarnessError("runtime classpath contains a non-JAR entry")

    launcher_mods = minecraft_home / "mods"
    fapi_version = parse_project_property("fabric_api_version")
    fabric_api, fabric_api_metadata = find_mod(
        launcher_mods, "fabric-api", fapi_version)
    sodium, sodium_metadata = find_mod(
        launcher_mods, "sodium", REQUIRED_SODIUM_VERSION)
    iris, iris_metadata = find_mod(
        launcher_mods, "iris", REQUIRED_IRIS_VERSION)
    gametest_api, gametest_metadata = find_client_gametest_api(
        Path.home(), fapi_version)

    shader_pack_name = args.shader_pack or DEFAULT_SHADER_PACK
    shader_pack = require_file(
        minecraft_home / "shaderpacks" / shader_pack_name,
        "Iris shader pack",
    )
    try:
        with zipfile.ZipFile(shader_pack) as archive:
            if not any(name.startswith("shaders/") for name in archive.namelist()):
                raise HarnessError(
                    f"shader pack has no shaders/ tree: {shader_pack}"
                )
    except zipfile.BadZipFile as error:
        raise HarnessError(
            f"invalid shader pack ZIP: {shader_pack}"
        ) from error

    staging = Path(tempfile.mkdtemp(
        prefix=".exact-jar-qa-", dir=runtime.parent))
    installed = False
    try:
        game = staging / "game"
        mods = game / "mods"
        evidence = staging / "evidence"
        natives = staging / "natives"
        for directory in (mods, evidence, natives, game / "config",
                          game / "shaderpacks", staging / "home",
                          staging / "tmp"):
            directory.mkdir(parents=True, exist_ok=True)

        for source in (
            release_jar,
            fabric_api,
            sodium,
            iris,
            gametest_api,
        ):
            destination = mods / source.name
            shutil.copy2(source, destination)
            if sha256(destination) != sha256(source):
                raise HarnessError(
                    f"isolated copy differs from source artifact: {source}"
                )

        copied_shader_pack = game / "shaderpacks" / shader_pack.name
        shutil.copy2(shader_pack, copied_shader_pack)
        if sha256(copied_shader_pack) != sha256(shader_pack):
            raise HarnessError(
                f"isolated shader pack copy differs from {shader_pack}"
            )
        (game / "config" / "iris.properties").write_text(
            "# Generated by MetalRender exact-JAR QA\n"
            "allowUnknownShaders=false\n"
            "colorSpace=SRGB\n"
            "disableUpdateMessage=true\n"
            "enableDebugOptions=false\n"
            "enableShaders=true\n"
            "maxShadowRenderDistance=16\n"
            f"shaderPack={shader_pack.name}\n",
            encoding="utf-8",
        )
        (game / "config" / "iris-excluded.json").write_text(
            '{"excluded":["put:valuesHere"]}\n', encoding="utf-8")
        (game / "options.txt").write_text(
            "enableVsync:true\n"
            "fullscreen:false\n"
            "maxFps:60\n"
            "renderDistance:8\n"
            "simulationDistance:5\n"
            "pauseOnLostFocus:false\n"
            "onboardAccessibility:false\n"
            "soundCategory_master:0.0\n",
            encoding="utf-8",
        )

        driver = compile_driver(
            staging, java, classpath, release_jar, iris, gametest_api)
        driver_destination = mods / driver.name
        shutil.move(driver, driver_destination)

        final_exact_jar = runtime / "game" / "mods" / release_jar.name
        driver_evidence = runtime / "evidence" / "driver-result.json"
        launch_command = build_launch_command(
            runtime=runtime,
            minecraft_home=minecraft_home,
            java=java,
            classpath=classpath,
            exact_jar=final_exact_jar,
            exact_sha=sha256(release_jar),
            driver_evidence=driver_evidence,
            shader_pack=shader_pack.name,
            asset_index=vanilla["assetIndex"]["id"],
            backend=args.backend,
        )

        manifest = {
            "schemaVersion": 2,
            "status": "PREPARED",
            "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "networkDownloadsRequired": False,
            "allArtifactsResolvedLocally": True,
            "sanitizedEnvironmentVariables": [
                *SANITIZED_ENVIRONMENT_VARIABLES,
                "METALRENDER_*",
            ],
            "productionFabricEnvironment": True,
            "sourceSetClasspathEntries": [],
            "qaBackend": args.backend,
            "minecraft": {
                "version": MC_VERSION,
                "jar": str(classpath[-1]),
                "jarSha256": sha256(classpath[-1]),
                "versionJson": str(vanilla_json),
                "versionJsonSha256": sha256(vanilla_json),
            },
            "fabric": {
                "profile": FABRIC_PROFILE,
                "loaderVersion": FABRIC_LOADER_VERSION,
                "profileJson": str(fabric_json),
                "profileJsonSha256": sha256(fabric_json),
                "mainClass": fabric["mainClass"],
            },
            "java": {
                "home": str(java),
                "version": java_runtime_version,
            },
            "release": {
                "sourcePath": str(release_jar),
                "runtimePath": str(final_exact_jar),
                "version": release_metadata["version"],
                "sha256": sha256(release_jar),
            },
            "compatibilityMods": {
                "fabricApi": artifact_record(
                    fabric_api, fabric_api_metadata),
                "sodium": artifact_record(sodium, sodium_metadata),
                "iris": artifact_record(iris, iris_metadata),
                "clientGametestApi": artifact_record(
                    gametest_api, gametest_metadata),
            },
            "shaderPack": {
                "sourcePath": str(shader_pack),
                "runtimePath": str(
                    runtime / "game" / "shaderpacks" / shader_pack.name),
                "sha256": sha256(shader_pack),
            },
            "qaDriver": {
                "runtimePath": str(
                    runtime / "game" / "mods" / driver_destination.name),
                "sha256": sha256(driver_destination),
            },
            "runtimeClasspath": [str(item) for item in classpath],
            "runtimeClasspathCount": len(classpath),
            "runtimeClasspathArtifacts": [
                {"path": str(item), "sha256": sha256(item)}
                for item in classpath
            ],
            "runtimeArtifacts": [
                {
                    "path": str(runtime / "game" / "mods" / source.name),
                    "sha256": sha256(source),
                }
                for source in (
                    release_jar,
                    fabric_api,
                    sodium,
                    iris,
                    gametest_api,
                )
            ] + [
                {
                    "path": str(runtime / "game" / "mods"
                                / driver_destination.name),
                    "sha256": sha256(driver_destination),
                },
                {
                    "path": str(runtime / "game" / "shaderpacks"
                                / shader_pack.name),
                    "sha256": sha256(shader_pack),
                },
            ],
            "launchCommand": launch_command,
        }
        (staging / "prepare-manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

        if runtime.exists():
            shutil.rmtree(runtime)
        staging.rename(runtime)
        installed = True
    finally:
        if not installed:
            shutil.rmtree(staging, ignore_errors=True)

    return read_json(runtime / "prepare-manifest.json")


def artifact_record(path: Path, metadata: dict[str, Any]) -> dict[str, str]:
    return {
        "sourcePath": str(path),
        "id": str(metadata["id"]),
        "version": str(metadata["version"]),
        "sha256": sha256(path),
    }


def verify_prepared_runtime(manifest: dict[str, Any]) -> None:
    if (manifest.get("schemaVersion") != 2
            or manifest.get("status") != "PREPARED"):
        raise HarnessError("prepared manifest has an unsupported schema/status")
    backend = manifest.get("qaBackend")
    if backend not in SUPPORTED_BACKENDS:
        raise HarnessError(
            f"prepared manifest has an invalid QA backend: {backend}")
    release = manifest.get("release")
    if not isinstance(release, dict):
        raise HarnessError("prepared manifest has no release attestation")
    if release.get("version") != parse_project_property("mod_version"):
        raise HarnessError(
            "prepared release version no longer matches project mod_version")

    records = [
        *manifest.get("runtimeClasspathArtifacts", []),
        *manifest.get("runtimeArtifacts", []),
    ]
    expected_count = (
        manifest.get("runtimeClasspathCount", 0)
        + len(manifest.get("runtimeArtifacts", []))
    )
    if len(records) != expected_count or not records:
        raise HarnessError(
            "prepared manifest has incomplete artifact attestations")
    for record in records:
        path = require_file(
            Path(record["path"]), "prepared attested artifact")
        if sha256(path) != record.get("sha256"):
            raise HarnessError(
                f"prepared artifact changed before launch: {path}")
    verify_release_source(manifest)


def release_source_matches(manifest: dict[str, Any]) -> bool:
    try:
        release = manifest["release"]
        source = Path(release["sourcePath"]).expanduser().resolve()
        return source.is_file() and sha256(source) == release["sha256"]
    except (KeyError, OSError, RuntimeError, TypeError, ValueError):
        return False


def verify_release_source(manifest: dict[str, Any]) -> None:
    if not release_source_matches(manifest):
        raise HarnessError(
            "source release JAR changed or disappeared after preparation")


def expected_backend_mode(backend: str) -> str:
    if backend == "metal4":
        return "METAL4_RUNTIME_VERIFIED_METAL3_RENDER"
    if backend == "metal3":
        return "METAL3"
    raise HarnessError(f"unsupported QA backend: {backend}")


def fault_counter_group_is_zero(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    return all(
        type(value.get(name)) is int and value.get(name) == 0
        for name in (
            "gpuCommandBufferErrors",
            "inFlightFrameTimeouts",
            "noIOSurfaceSlotSkips",
        )
    )


def driver_identity_matches_manifest(
    driver_result: Any,
    manifest: dict[str, Any],
    cache_expectation: str,
) -> bool:
    if not isinstance(driver_result, dict):
        return False
    try:
        release = manifest["release"]
        backend = manifest["qaBackend"]
        actual_path = Path(driver_result["exactJarPath"]).resolve()
        expected_path = Path(release["runtimePath"]).resolve()
    except (KeyError, OSError, RuntimeError, TypeError, ValueError):
        return False

    native_faults = driver_result.get("nativeFaultCounters")
    iris_translation = driver_result.get("irisTranslation")
    if not isinstance(iris_translation, dict):
        return False
    return (
        driver_result.get("status") == "PASS"
        and actual_path == expected_path
        and driver_result.get("exactJarSha256") == release.get("sha256")
        and driver_result.get("metalrenderVersion") == release.get("version")
        and driver_result.get("cacheExpectation") == cache_expectation
        and driver_result.get("backendExpectation") == backend
        and driver_result.get("backendMode") == expected_backend_mode(backend)
        and type(driver_result.get("metal4Active")) is bool
        and driver_result.get("metal4Active") == (backend == "metal4")
        and driver_result.get("metal4DrawPathActive") is False
        and driver_result.get("irisDrawBackend") == "OPENGL"
        and driver_result.get("generatedMslExecuted") is False
        and iris_translation.get("pipelineStatus") == "pending"
        and isinstance(native_faults, dict)
        and native_faults.get("semantics")
            == "process-lifetime-monotonic"
        and fault_counter_group_is_zero(native_faults.get("baseline"))
        and fault_counter_group_is_zero(native_faults.get("end"))
        and fault_counter_group_is_zero(native_faults.get("delta"))
    )


def verify_cold_result_for_warm(
    runtime: Path,
    manifest: dict[str, Any],
) -> None:
    cold_path = require_file(
        runtime / "evidence" / "run-result-cold.json",
        "successful cold exact-JAR result",
    )
    cold = read_json(cold_path)
    manifest_path = require_file(
        runtime / "prepare-manifest.json", "prepared exact-JAR manifest")
    if cold.get("status") != "PASS":
        raise HarnessError("warm run requires a PASS cold result")
    if cold.get("cacheExpectation") != "cold":
        raise HarnessError("cold result has an invalid cache expectation")
    if (cold.get("prepareManifest", {}).get("sha256")
            != sha256(manifest_path)):
        raise HarnessError(
            "cold result was produced from a different prepare manifest")
    if not driver_identity_matches_manifest(
            cold.get("driverResult"), manifest, "cold"):
        raise HarnessError(
            "cold result is not bound to this backend and release JAR")


def build_launch_command(
    *,
    runtime: Path,
    minecraft_home: Path,
    java: Path,
    classpath: list[Path],
    exact_jar: Path,
    exact_sha: str,
    driver_evidence: Path,
    shader_pack: str,
    asset_index: str,
    backend: str,
) -> list[str]:
    game = runtime / "game"
    natives = runtime / "natives"
    return [
        str(java / "bin" / "java"),
        "-XstartOnFirstThread",
        "-Xms2G",
        "-Xmx6G",
        "-Xss1M",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        f"-Duser.home={runtime / 'home'}",
        f"-Djava.io.tmpdir={runtime / 'tmp'}",
        f"-Djava.library.path={natives / 'java'}",
        f"-Djna.tmpdir={natives / 'jna'}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives / 'lwjgl'}",
        f"-Dio.netty.native.workdir={natives / 'netty'}",
        "-Dminecraft.launcher.brand=metalrender-exact-jar-qa",
        "-Dminecraft.launcher.version=1",
        "-DFabricMcEmu= net.minecraft.client.main.Main ",
        "-Dfabric.development=false",
        "-Dfabric.client.gametest=true",
        f"-Dfabric.client.gametest.modid={QA_MOD_ID}",
        f"-Dmetalrender.exactJar.expectedPath={exact_jar}",
        f"-Dmetalrender.exactJar.expectedSha256={exact_sha}",
        f"-Dmetalrender.exactJar.driverEvidencePath={driver_evidence}",
        f"-Dmetalrender.exactJar.shaderPack={shader_pack}",
        "-Dmetalrender.exactJar.cacheExpectation=cold",
        f"-Dmetalrender.exactJar.backend={backend}",
        f"-Dmetalrender.feature.metal4="
        f"{'true' if backend == 'metal4' else 'false'}",
        "-Dmetalrender.experimental.irisMetalPipeline=true",
        "-Dmetalrender.experimental.irisMetalTranslation=true",
        f"-Dmetalrender.experimental.irisMetalCacheRoot="
        f"{runtime / 'iris-metal-cache'}",
        "-classpath",
        os.pathsep.join(str(item) for item in classpath),
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "--username", "MetalRenderQA26",
        "--version", FABRIC_PROFILE,
        "--gameDir", str(game),
        "--assetsDir", str(minecraft_home / "assets"),
        "--assetIndex", asset_index,
        "--uuid", "00000000-0000-4000-8000-000000000026",
        "--accessToken", "0",
        "--clientId", "0",
        "--xuid", "0",
        "--versionType", "release",
        "--width", "1280",
        "--height", "720",
    ]


def terminate_process_group(process: subprocess.Popen) -> int:
    if process.poll() is not None:
        return int(process.returncode)
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        return process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return process.wait(timeout=15)


def inspect_translation_cache(cache_root: Path) -> dict[str, Any]:
    all_paths = (
        sorted(cache_root.rglob("*")) if cache_root.is_dir() else []
    )
    regular_files = (
        [path for path in all_paths if path.is_file()]
    )
    glsl_files = [
        path for path in regular_files
        if path.suffix.lower() in {".glsl", ".vert", ".frag", ".geom",
                                   ".tesc", ".tese", ".comp"}
    ]
    spirv_files = [path for path in regular_files if path.suffix == ".spv"]
    msl_files = [path for path in regular_files if path.suffix == ".metal"]
    complete_markers = [
        path for path in regular_files
        if path.name == "translation.complete"
    ]
    manifests = [
        path for path in regular_files if path.name == "manifest.properties"
    ]
    valid_spirv = all(
        path.stat().st_size >= 20
        and path.stat().st_size % 4 == 0
        and path.read_bytes()[:4] == b"\x03\x02\x23\x07"
        for path in spirv_files
    )
    valid_msl = True
    for path in msl_files:
        text_content = path.read_text(encoding="utf-8", errors="strict")
        valid_msl = (
            valid_msl
            and bool(text_content.strip())
            and "metal_stdlib" in text_content
            and "#version" not in text_content
        )
    redacted_manifests = all(
        "source.original_glsl_persisted=false"
        in path.read_text(encoding="utf-8", errors="strict")
        and "pipeline.status=pending"
        in path.read_text(encoding="utf-8", errors="strict")
        for path in manifests
    )
    artifact_integrity = True
    schema_2_manifests = True
    stages = (
        "vertex", "tess-control", "tess-evaluation",
        "geometry", "fragment", "compute",
    )
    for manifest in manifests:
        properties: dict[str, str] = {}
        for line in manifest.read_text(
                encoding="utf-8", errors="strict").splitlines():
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            properties[key] = value
        schema_2_manifests = (
            schema_2_manifests and properties.get("schema") == "2")
        entry = manifest.parent.resolve()
        for stage in stages:
            prefix = f"stage.{stage}"
            present = properties.get(f"{prefix}.present")
            if present not in {"true", "false"}:
                artifact_integrity = False
                continue
            if present == "false":
                continue
            for artifact, suffix in (("spirv", ".spv"), ("msl", ".metal")):
                relative = properties.get(f"{prefix}.{artifact}")
                if not relative:
                    artifact_integrity = False
                    continue
                path = (manifest.parent / relative).resolve()
                try:
                    path.relative_to(entry)
                except ValueError:
                    artifact_integrity = False
                    continue
                artifact_integrity = (
                    artifact_integrity
                    and path.is_file()
                    and not path.is_symlink()
                    and path.suffix == suffix
                    and properties.get(f"{prefix}.{artifact}.bytes")
                        == str(path.stat().st_size)
                    and properties.get(f"{prefix}.{artifact}.sha256")
                        == sha256(path)
                )
    valid_markers = all(
        not path.is_symlink() and 0 < path.stat().st_size <= 4096
        for path in complete_markers
    )
    checks = {
        "cacheRootExists": cache_root.is_dir(),
        "hasCompleteTranslation": bool(complete_markers),
        "hasSpirv": bool(spirv_files),
        "hasMsl": bool(msl_files),
        "validSpirv": valid_spirv,
        "validMsl": valid_msl,
        "redactedManifests": bool(manifests) and redacted_manifests,
        "manifestSchema2": bool(manifests) and schema_2_manifests,
        "artifactHashesMatchManifest":
            bool(manifests) and artifact_integrity,
        "completionMarkersBounded": valid_markers,
        "entryCountsMatch":
            len(manifests) == len(complete_markers),
        "noSymlinks": not any(path.is_symlink() for path in all_paths),
        "noRawGlslFiles": not glsl_files,
    }
    return {
        "path": str(cache_root),
        "status": "PASS" if all(checks.values()) else "FAIL",
        "checks": checks,
        "fileCount": len(regular_files),
        "completeTranslationCount": len(complete_markers),
        "spirvStageCount": len(spirv_files),
        "mslStageCount": len(msl_files),
        "rawGlslFiles": [str(path) for path in glsl_files],
    }


def replace_system_property(
    command: list[str],
    name: str,
    value: str,
) -> list[str]:
    prefix = f"-D{name}="
    replaced = False
    result: list[str] = []
    for argument in command:
        if argument.startswith(prefix):
            result.append(prefix + value)
            replaced = True
        else:
            result.append(argument)
    if not replaced:
        raise HarnessError(f"launch command is missing {name}")
    return result


def run_harness(
    args: argparse.Namespace,
    manifest: dict[str, Any],
    cache_expectation: str,
) -> None:
    runtime = safe_runtime_path(Path(args.runtime_dir))
    if cache_expectation not in {"cold", "warm"}:
        raise HarnessError(
            f"unsupported cache expectation: {cache_expectation}")
    backend = manifest.get("qaBackend")
    if backend != args.backend:
        raise HarnessError(
            "requested backend does not match prepared runtime: "
            f"requested {args.backend}, prepared {backend}"
        )
    log_path = (
        runtime / "evidence" / f"client-{cache_expectation}.log")
    result_path = (
        runtime / "evidence" / f"run-result-{cache_expectation}.json")
    manifest_path = runtime / "prepare-manifest.json"
    command = [str(item) for item in manifest["launchCommand"]]
    driver_result_path = (
        runtime / "evidence" /
        f"driver-result-{cache_expectation}.json"
    )
    for stale in (driver_result_path, result_path):
        stale.unlink(missing_ok=True)
    for stale in (runtime / "game" / "screenshots").glob(
            f"*metalrender-exact-jar-{cache_expectation}-*.png"):
        stale.unlink()
    command = replace_system_property(
        command, "metalrender.exactJar.cacheExpectation",
        cache_expectation)
    command = replace_system_property(
        command, "metalrender.exactJar.driverEvidencePath",
        str(driver_result_path))
    command = replace_system_property(
        command, "metalrender.exactJar.backend", backend)
    command = replace_system_property(
        command, "metalrender.feature.metal4",
        "true" if backend == "metal4" else "false")
    started = dt.datetime.now(dt.timezone.utc)
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            command,
            cwd=runtime / "game",
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            env=clean_environment(),
        )
        timed_out = False
        return_code: Optional[int] = None
        previous_handlers: dict[int, Any] = {}

        def interrupted(signum: int, _frame: Any) -> None:
            raise HarnessError(
                f"exact-JAR QA interrupted by signal {signum}")

        try:
            for interrupt_signal in (signal.SIGTERM, signal.SIGHUP):
                previous_handlers[interrupt_signal] = signal.getsignal(
                    interrupt_signal)
                signal.signal(interrupt_signal, interrupted)
            try:
                return_code = process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                return_code = terminate_process_group(process)
        finally:
            if process.poll() is None:
                terminated_code = terminate_process_group(process)
                if return_code is None:
                    return_code = terminated_code
            for interrupt_signal, previous in previous_handlers.items():
                signal.signal(interrupt_signal, previous)
    if return_code is None:
        raise HarnessError("Minecraft process ended without an exit code")

    driver_result = (
        read_json(driver_result_path) if driver_result_path.is_file()
        else None
    )
    screenshots = sorted(
        (runtime / "game" / "screenshots").glob(
            f"*metalrender-exact-jar-{cache_expectation}-*.png")
    )
    crash_reports = sorted(
        (runtime / "game" / "crash-reports").glob("*.txt")
    )
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    forbidden_runtime_diagnostics = [
        diagnostic for diagnostic in FORBIDDEN_RUNTIME_DIAGNOSTICS
        if diagnostic in log_text
    ]
    translation_cache = inspect_translation_cache(
        runtime / "iris-metal-cache")
    iris_translation = (
        driver_result.get("irisTranslation", {})
        if isinstance(driver_result, dict) else {}
    )
    attempted = iris_translation.get("attempted")
    cache_entry_count = translation_cache.get("completeTranslationCount")
    checks = {
        "processExitedZero": return_code == 0,
        "didNotTimeout": not timed_out,
        "driverReportedPass": bool(
            driver_result and driver_result.get("status") == "PASS"),
        "driverIdentityMatchesManifest":
            driver_identity_matches_manifest(
                driver_result, manifest, cache_expectation),
        "threeShaderToggleScreenshots": len(screenshots) == 3,
        "normalShutdownLogged": "Stopping!" in log_text,
        "noCrashReports": not crash_reports,
        "noForbiddenRuntimeDiagnostics":
            not forbidden_runtime_diagnostics,
        "translationCacheValidated":
            translation_cache["status"] == "PASS",
        "translationCacheCountMatchesDriver":
            isinstance(attempted, int)
            and attempted == EXPECTED_COMPLEMENTARY_PROGRAMS
            and cache_entry_count == attempted,
        "translationStageCountsMatchExactWorkload":
            translation_cache.get("spirvStageCount")
                == EXPECTED_COMPLEMENTARY_STAGES
            and translation_cache.get("mslStageCount")
                == EXPECTED_COMPLEMENTARY_STAGES,
        "translationCompletedWithoutFailures":
            iris_translation.get("failed") == 0
            and iris_translation.get("rejected") == 0
            and iris_translation.get("captureFailures") == 0
            and iris_translation.get("queuedAtEvidence") == 0
            and attempted
            == iris_translation.get("translated", 0)
                + iris_translation.get("cacheHits", 0),
        "preparedManifestPresent": manifest_path.is_file(),
        "releaseSourceUnchangedAfterRun":
            release_source_matches(manifest),
    }
    run_result = {
        "schemaVersion": 2,
        "cacheExpectation": cache_expectation,
        "qaBackend": backend,
        "status": "PASS" if all(checks.values()) else "FAIL",
        "startedAt": started.isoformat(),
        "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "timeoutSeconds": args.timeout,
        "exitCode": return_code,
        "checks": checks,
        "driverResult": driver_result,
        "prepareManifest": {
            "path": str(manifest_path),
            "sha256": sha256(manifest_path),
        },
        "log": {
            "path": str(log_path),
            "sha256": sha256(log_path),
        },
        "screenshots": [
            {
                "path": str(path),
                "size": path.stat().st_size,
                "sha256": sha256(path),
            }
            for path in screenshots
        ],
        "translationCache": translation_cache,
        "forbiddenRuntimeDiagnostics": forbidden_runtime_diagnostics,
        "crashReports": [str(path) for path in crash_reports],
    }
    result_path.write_text(
        json.dumps(run_result, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    if run_result["status"] != "PASS":
        raise HarnessError(
            f"exact-JAR run failed; inspect {result_path} and {log_path}"
        )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description=(
            "Prepare or run an isolated production Fabric 26.2 "
            "exact-release-JAR QA profile."
        )
    )
    result.add_argument(
        "action", choices=("prepare", "run", "warm"),
        help=(
            "'prepare' never launches Minecraft; 'run' prepares a cold cache "
            "and launches; 'warm' reuses the prepared runtime and cache"
        ),
    )
    result.add_argument(
        "--jar", default=str(
            PROJECT / "build" / "libs" /
            f"metalrender-{parse_project_property('mod_version')}.jar"),
        help="exact MetalRender release JAR to test",
    )
    result.add_argument(
        "--runtime-dir", default=str(DEFAULT_RUNTIME),
        help="isolated runtime directory (must be below project build/)",
    )
    result.add_argument(
        "--backend", choices=SUPPORTED_BACKENDS, default="metal4",
        help=(
            "native runtime profile to verify; use a separate runtime "
            "directory for each backend"
        ),
    )
    result.add_argument(
        "--minecraft-home",
        default=str(Path.home() / "Library" /
                    "Application Support" / "minecraft"),
        help="read-only official launcher installation",
    )
    result.add_argument(
        "--shader-pack", default=DEFAULT_SHADER_PACK,
        help="existing shader pack filename from launcher shaderpacks/",
    )
    result.add_argument(
        "--timeout", type=positive_int, default=900,
        help="maximum client runtime in seconds",
    )
    return result


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "must be a positive integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return parsed


def main() -> int:
    try:
        args = parser().parse_args()
        with runtime_lock(Path(args.runtime_dir)):
            runtime = safe_runtime_path(Path(args.runtime_dir))
            manifest_path = runtime / "prepare-manifest.json"
            if args.action == "warm":
                manifest = read_json(require_file(
                    manifest_path, "prepared exact-JAR manifest"))
                if manifest.get("qaBackend") != args.backend:
                    raise HarnessError(
                        "warm backend does not match prepared runtime: "
                        f"requested {args.backend}, prepared "
                        f"{manifest.get('qaBackend')}"
                    )
                runtime_jar = require_file(
                    Path(manifest["release"]["runtimePath"]),
                    "prepared exact release JAR")
                if sha256(runtime_jar) != manifest["release"]["sha256"]:
                    raise HarnessError(
                        "prepared exact release JAR changed before warm run")
                verify_cold_result_for_warm(runtime, manifest)
            else:
                manifest = prepare(args)
                print(
                    "Prepared production Fabric exact-JAR profile:\n"
                    f"  manifest: {manifest_path}\n"
                    f"  release SHA-256: {manifest['release']['sha256']}\n"
                    f"  classpath JARs: {manifest['runtimeClasspathCount']}\n"
                    "  source-set classpath entries: 0"
                )
            if args.action in {"run", "warm"}:
                verify_prepared_runtime(manifest)
                cache_expectation = (
                    "cold" if args.action == "run" else "warm")
                run_harness(args, manifest, cache_expectation)
                if args.action == "warm":
                    # Re-hash the original publishable artifact after the warm
                    # client has exited; evidence is invalid if build/libs was
                    # replaced while the prepared copy was running.
                    verify_release_source(manifest)
                print(
                    f"Exact release JAR {cache_expectation}-cache QA PASS:\n"
                    f"  evidence: "
                    f"{runtime / 'evidence' / ('run-result-' + cache_expectation + '.json')}"
                )
        return 0
    except HarnessError as error:
        print(f"exact-JAR QA error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("exact-JAR QA interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
