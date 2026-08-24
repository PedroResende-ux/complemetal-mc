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
import struct
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
EXPECTED_COMPLEMENTARY_PROGRAMS = 231
EXPECTED_COMPLEMENTARY_STAGES = 462
RENDER_GRAPH_FRAME_QUEUE_CAPACITY = 16
FULL_GRAPH_FRAME_QUEUE_CAPACITY = 2
SUPPORTED_BACKENDS = ("metal4", "metal3")
SUPPORTED_PERFORMANCE_SIDES = ("opengl", "metal")
MINIMUM_PERFORMANCE_SAMPLES = 600
MAXIMUM_PERFORMANCE_SAMPLES = 36_000
MINIMUM_PRESENTATION_SAMPLES = 120
MAXIMUM_PRESENTATION_SAMPLES = 5_000
LIFECYCLE_MINIMUM_PRESENTATIONS = 80
LIFECYCLE_MAXIMUM_INVALIDATIONS = 16
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


def uses_packaged_stable_iris_metal_defaults(version: str) -> bool:
    return re.fullmatch(
        r"[0-9]+\.[0-9]+\.[0-9]+\+mc[0-9]+(?:\.[0-9]+)*",
        version,
    ) is not None


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


def diagnostic_asan_java(runtime: Path, launch_command: list[str]) -> Path:
    """Create an isolated, non-hardened Java launcher for ASan injection.

    Microsoft's Minecraft runtime is signed with the hardened-runtime flag,
    so macOS rejects DYLD_INSERT_LIBRARIES before our instrumented JNI library
    can load.  Copying and ad-hoc signing only the tiny launcher removes that
    flag for this diagnostic process; the official runtime remains untouched
    and its read-only lib/conf trees are reused through local symlinks.
    """
    if not launch_command:
        raise HarnessError("missing Java launch command")
    source_java = require_file(Path(launch_command[0]), "Java launcher")
    source_home = source_java.parent.parent
    diagnostic_root = runtime / "diagnostics" / "asan-java"
    if diagnostic_root.exists():
        shutil.rmtree(diagnostic_root)
    diagnostic_home = diagnostic_root / "Home"
    diagnostic_bin = diagnostic_home / "bin"
    diagnostic_bin.mkdir(parents=True)
    copied_java = diagnostic_bin / "java"
    shutil.copy2(source_java, copied_java)
    for name in ("lib", "conf"):
        source = source_home / name
        if not source.is_dir():
            raise HarnessError(f"Java runtime is missing {name}: {source}")
        (diagnostic_home / name).symlink_to(source, target_is_directory=True)
    run_checked(["codesign", "--force", "--sign", "-", str(copied_java)])
    return copied_java


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
        performance_side = args.performance_side or ""
        performance_samples = (
            args.performance_samples if performance_side else 0)
        hardware_display = args.hardware_display
        presentation_samples = (
            args.presentation_samples if hardware_display else 0)
        minimum_refresh_hz = (
            args.minimum_refresh_hz if hardware_display else 0)
        (game / "options.txt").write_text(
            f"enableVsync:{'false' if performance_side or hardware_display else 'true'}\n"
            "fullscreen:false\n"
            f"maxFps:{260 if performance_side or hardware_display else 60}\n"
            f"inactivityFpsLimit:{'\"minimized\"' if hardware_display else '\"afk\"'}\n"
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
            require_graph_ownership=args.require_graph_ownership,
            performance_side=performance_side,
            performance_samples=performance_samples,
            hardware_display=hardware_display,
            require_retina=args.require_retina,
            require_display_migration=args.require_display_migration,
            minimum_refresh_hz=minimum_refresh_hz,
            presentation_samples=presentation_samples,
        )

        manifest = {
            "schemaVersion": 4,
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
            "graphOwnershipRequired": args.require_graph_ownership,
            "performanceSide": performance_side,
            "performanceSamples": performance_samples,
            "hardwareDisplay": hardware_display,
            "requireRetina": args.require_retina,
            "requireDisplayMigration": args.require_display_migration,
            "minimumRefreshHz": minimum_refresh_hz,
            "presentationSamples": presentation_samples,
            "irisMetalActivation": (
                "packaged-stable-default"
                if uses_packaged_stable_iris_metal_defaults(
                    str(release_metadata["version"]))
                else "legacy-explicit-flags"
            ),
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
    if (manifest.get("schemaVersion") != 4
            or manifest.get("status") != "PREPARED"):
        raise HarnessError("prepared manifest has an unsupported schema/status")
    backend = manifest.get("qaBackend")
    if backend not in SUPPORTED_BACKENDS:
        raise HarnessError(
            f"prepared manifest has an invalid QA backend: {backend}")
    if type(manifest.get("graphOwnershipRequired")) is not bool:
        raise HarnessError(
            "prepared manifest has no graph ownership expectation")
    activation = manifest.get("irisMetalActivation")
    if activation not in {
        "legacy-explicit-flags", "packaged-stable-default"
    }:
        raise HarnessError(
            "prepared manifest has no valid Iris Metal activation mode")
    if manifest["graphOwnershipRequired"] and backend != "metal4":
        raise HarnessError(
            "prepared graph ownership profile does not use Metal 4")
    performance_side = manifest.get("performanceSide")
    performance_samples = manifest.get("performanceSamples")
    if performance_side not in {"", *SUPPORTED_PERFORMANCE_SIDES}:
        raise HarnessError(
            "prepared manifest has an invalid performance side")
    if (type(performance_samples) is not int
            or (performance_side == "" and performance_samples != 0)
            or (performance_side != "" and not (
                MINIMUM_PERFORMANCE_SAMPLES <= performance_samples
                <= MAXIMUM_PERFORMANCE_SAMPLES))):
        raise HarnessError(
            "prepared manifest has an invalid performance sample count")
    if (performance_side == "metal"
            and (backend != "metal4"
                 or not manifest["graphOwnershipRequired"])):
        raise HarnessError(
            "prepared Metal performance profile lacks graph ownership")
    if (performance_side == "opengl"
            and (backend != "metal3"
                 or manifest["graphOwnershipRequired"])):
        raise HarnessError(
            "prepared OpenGL performance profile is not Metal 3 fallback")
    hardware_display = manifest.get("hardwareDisplay")
    require_retina = manifest.get("requireRetina")
    require_display_migration = manifest.get("requireDisplayMigration")
    minimum_refresh_hz = manifest.get("minimumRefreshHz")
    presentation_samples = manifest.get("presentationSamples")
    if (type(hardware_display) is not bool
            or type(require_retina) is not bool
            or type(require_display_migration) is not bool
            or type(minimum_refresh_hz) is not int
            or type(presentation_samples) is not int):
        raise HarnessError(
            "prepared manifest has an invalid hardware display profile")
    if hardware_display:
        if (backend != "metal4" or not manifest["graphOwnershipRequired"]
                or performance_side != ""
                or minimum_refresh_hz < 0
                or not MINIMUM_PRESENTATION_SAMPLES
                    <= presentation_samples
                    <= MAXIMUM_PRESENTATION_SAMPLES):
            raise HarnessError(
                "prepared hardware display profile is inconsistent")
    elif (require_retina or require_display_migration
          or minimum_refresh_hz != 0 or presentation_samples != 0):
        raise HarnessError(
            "prepared non-hardware profile contains display requirements")
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


def expected_backend_mode(
    backend: str,
    graph_ownership_required: bool = False,
) -> str:
    if backend == "metal4":
        return (
            "METAL4" if graph_ownership_required
            else "METAL4_RUNTIME_VERIFIED_METAL3_RENDER"
        )
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


def msl_library_validation_is_safe(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    return (
        value.get("enabled") is True
        and value.get("ready") is True
        and value.get("complete") is True
        and value.get("mode") == "compile-resolve-release-only"
        and value.get("executionBoundary")
            == "library-compile-resolve-release-only"
        and value.get("stageCounterSemantics")
            == "coordinator-lifetime-monotonic"
        and value.get("nativeCounterSemantics")
            == "process-lifetime-monotonic"
        and value.get("liveLibrarySemantics") == "instantaneous-gauge"
        and value.get("lastFailure") == ""
        and type(value.get("liveLibrariesAtEvidence")) is int
        and value.get("liveLibrariesAtEvidence") == 0
        and "librariesCreated" not in value
        and "functionsResolved" not in value
        and "renderPipelinesCreated" not in value
        and "drawsEncoded" not in value
    )


def pipeline_state_capture_is_complete(value: Any, backend: str) -> bool:
    if not isinstance(value, dict):
        return False
    return (
        value.get("complete") is True
        and type(value.get("drawsObserved")) is int
        and value.get("drawsObserved", 0) > 0
        and type(value.get("dispatchesObserved")) is int
        and value.get("dispatchesObserved", -1) >= 0
        and type(value.get("variantsAccepted")) is int
        and value.get("variantsAccepted", 0) > 0
        and value.get("variantsRejected") == 0
        and value.get("incompleteVariants") == 0
        and value.get("statesPending") == 0
        and value.get("statesAttempted") == value.get("variantsAccepted")
        and value.get("statesSucceeded") == value.get("statesAttempted")
        and value.get("statesUnsupported") == 0
        and value.get("statesFailed") == 0
        and type(value.get("executionBlocked")) is int
        and 0 <= value.get("executionBlocked", -1)
        <= value.get("statesSucceeded", -1)
        and type(value.get("stateIdentityCount")) is int
        and value.get("stateIdentityCount", 0) > 0
        and isinstance(value.get("stateSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["stateSetSha256"])
            is not None
        and value.get("stateSetComplete") is True
        and value.get("lastFailure") == ""
        and value.get("pipelineStatus") == (
            "cached" if backend == "metal4"
            else "unsupported-safe-fallback"
        )
        and value.get("irisOpenGlActive") is True
    )


def resource_reflection_is_complete(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    return (
        value.get("complete") is True
        and value.get("programsAttempted") == EXPECTED_COMPLEMENTARY_PROGRAMS
        and value.get("programsSucceeded") == EXPECTED_COMPLEMENTARY_PROGRAMS
        and value.get("programsUnsupported") == 0
        and value.get("programsFailed") == 0
        and value.get("stagesReflected") == EXPECTED_COMPLEMENTARY_STAGES
        and type(value.get("bindingsReflected")) is int
        and value.get("bindingsReflected", 0) > 0
        and value.get("layoutIdentityCount")
            == EXPECTED_COMPLEMENTARY_PROGRAMS
        and value.get("layoutSetComplete") is True
        and isinstance(value.get("layoutSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["layoutSetSha256"])
            is not None
        and value.get("lastFailure") == ""
        and type(value.get("bindingVariantsAttempted")) is int
        and value.get("bindingVariantsAttempted", 0) > 0
        and value.get("bindingVariantsSucceeded")
            == value.get("bindingVariantsAttempted")
        and value.get("bindingVariantsIncomplete") == 0
        and type(value.get("runtimeBindingsMatched")) is int
        and value.get("runtimeBindingsMatched", 0) > 0
        and value.get("bindingIncompleteReasonCount") == 0
        and value.get("bindingIncompleteReasonSetComplete") is True
        and isinstance(value.get("bindingIncompleteReasonSetSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}",
            value["bindingIncompleteReasonSetSha256"],
        ) is not None
        and value.get("bindingIncompleteReasonSummary") == ""
        and value.get("runtimeBindingsCaptured") is True
    )


def render_graph_is_complete(
    value: Any,
    backend: str,
    graph_ownership_required: bool,
) -> bool:
    if not isinstance(value, dict):
        return False
    required_phases = {"SHADOW", "GEOMETRY", "COMPOSITE", "FINAL"}
    phases = set(str(value.get("phaseSummary", "")).split(","))
    frames_completed = value.get("framesCompleted")
    frames_pending = value.get("framesPending")
    graphs_attempted = value.get("graphsAttempted")
    counters_are_integers = all(type(counter) is int for counter in (
        frames_completed, frames_pending, graphs_attempted,
    ))
    if graph_ownership_required:
        frame_accounting = (
            counters_are_integers
            and value.get("captureFrozen") is False
            and 0 <= frames_pending <= RENDER_GRAPH_FRAME_QUEUE_CAPACITY
            and frames_completed >= graphs_attempted
            and frames_completed - graphs_attempted == frames_pending
        )
    else:
        frame_accounting = (
            counters_are_integers
            and value.get("captureFrozen") is True
            and frames_pending == 0
            and graphs_attempted == frames_completed
        )
    return (
        value.get("complete") is True
        and type(value.get("framesStarted")) is int
        and value.get("framesStarted", 0) > 0
        and type(value.get("framesCompleted")) is int
        and value.get("framesCompleted", 0) > 0
        and value.get("framesRejected") == 0
        and value.get("framesStarted") >= value.get("framesCompleted")
        and frame_accounting
        and value.get("graphsSucceeded") == value.get("graphsAttempted")
        and value.get("graphsUnsupported") == 0
        and value.get("graphsFailed") == 0
        and type(value.get("resourcesRepresented")) is int
        and value.get("resourcesRepresented", 0) > 0
        and type(value.get("nodesRepresented")) is int
        and value.get("nodesRepresented", 0) > 0
        and type(value.get("edgesRepresented")) is int
        and value.get("edgesRepresented", 0) > 0
        and type(value.get("barriersRepresented")) is int
        and value.get("barriersRepresented", 0) > 0
        and type(value.get("clearsRepresented")) is int
        and value.get("clearsRepresented", 0) > 0
        and type(value.get("transfersRepresented")) is int
        and value.get("transfersRepresented", 0) > 0
        and type(value.get("pingPongResourcesRepresented")) is int
        and value.get("pingPongResourcesRepresented", 0) > 0
        and required_phases.issubset(phases)
        and type(value.get("graphIdentityCount")) is int
        and value.get("graphIdentityCount", 0) > 0
        and value.get("graphSetComplete") is True
        and isinstance(value.get("graphSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["graphSetSha256"])
            is not None
        and value.get("unsupportedReasonCount") == 0
        and value.get("unsupportedReasonSetComplete") is True
        and isinstance(value.get("unsupportedReasonSetSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}", value["unsupportedReasonSetSha256"]
        ) is not None
        and value.get("lastFailure") == ""
        and type(value.get("offscreenGeneratedMslReplayObserved")) is bool
        and value.get("visibleMetalExecution") is (backend == "metal4")
    )


def metal_graph_resources_are_valid(
    value: Any,
    render_graph: Any,
    backend: str,
) -> bool:
    if not isinstance(value, dict) or not isinstance(render_graph, dict):
        return False
    integer_fields = (
        "plansObserved", "plansComplete", "plansBlocked",
        "allocationsRequested", "nativeAttempts", "nativeSucceeded",
        "nativeFailed", "nativeTextureCount", "nativeTextureBytes",
        "blockerCount",
    )
    if not all(type(value.get(name)) is int and value.get(name) >= 0
               for name in integer_fields):
        return False
    common = (
        value.get("enabled") is True
        and value["plansObserved"] > 0
        and value["plansObserved"] == render_graph.get("graphsSucceeded")
        and value["plansComplete"] == value["plansObserved"]
        and value["plansBlocked"] == 0
        and value["allocationsRequested"] > 0
        and value["blockerCount"] == 0
        and value.get("blockerSetComplete") is True
        and isinstance(value.get("blockerSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["blockerSetSha256"])
            is not None
        and value.get("blockerSummary") == ""
        and value.get("storageMode") == "private"
        and value.get("identity")
            == "context-generation/gl-name/resource-generation"
    )
    if not common:
        return False
    if backend == "metal3":
        return (
            value.get("runtimeAvailable") is False
            and value.get("complete") is False
            and value.get("safeUnsupportedFallback") is True
            and value["nativeAttempts"] == 0
            and value["nativeSucceeded"] == 0
            and value["nativeFailed"] == 0
            and value["nativeTextureCount"] == 0
            and value["nativeTextureBytes"] == 0
        )
    return (
        value.get("runtimeAvailable") is True
        and value.get("complete") is True
        and value.get("safeUnsupportedFallback") is False
        and value["nativeAttempts"] == value["plansComplete"]
        and value["nativeSucceeded"] == value["nativeAttempts"]
        and value["nativeFailed"] == 0
        and value["nativeTextureCount"] > 0
        and value["nativeTextureBytes"] > 0
    )


def full_metal_graph_is_valid(
    value: Any,
    backend: str,
    graph_ownership_required: bool,
) -> bool:
    if not isinstance(value, dict):
        return False
    integer_fields = (
        "captureRequests", "framesObserved", "framesPlanned",
        "framesPending", "framesAttempted", "framesSucceeded",
        "framesUnsupported", "framesFailed", "operations", "draws",
        "clears", "transfers", "barriers", "capturedBytes",
        "initializedResources", "minimumSuccessFrames",
        "ownershipSubmissions", "ownershipReady",
        "ownershipFramesArmed", "ownershipFramesPresented",
        "ownershipFramesReused", "ownershipFramesInvalidated",
        "ownershipCommandsSuppressed",
        "ownershipFailures", "blockerCount",
    )
    if not all(type(value.get(name)) is int and value.get(name) >= 0
               for name in integer_fields):
        return False
    production_ownership = backend == "metal4" and graph_ownership_required
    queue_is_valid = (
        type(value.get("captureActive")) is bool
        and (
            value["framesPending"] <= FULL_GRAPH_FRAME_QUEUE_CAPACITY
            if production_ownership
            else value.get("captureActive") is False
                and value["framesPending"] == 0
        )
    )
    common = (
        value.get("enabled") is True
        and queue_is_valid
        and value["minimumSuccessFrames"] >= 3
        and type(value.get("ownershipOptedIn")) is bool
        and isinstance(value.get("ownershipLastFailure"), str)
        and value.get("blockerSetComplete") is True
        and isinstance(value.get("blockerSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["blockerSetSha256"])
            is not None
    )
    if not common:
        return False
    if backend == "metal3":
        return (
            value.get("runtimeAvailable") is False
            and value.get("validated") is False
            and value.get("ownershipOptedIn") is False
            and value.get("ownershipMode") == "OPENGL_VISIBLE_VALIDATION"
            and value.get("performanceEligible") is False
            and all(value[name] == 0 for name in integer_fields
                    if name not in {"minimumSuccessFrames"})
            and value.get("ownershipLastFailure") == ""
            and value.get("lastOutputHashUnsigned") == "0"
            and value.get("blockerSummary") == ""
            and value.get("lastFailure") == ""
        )
    baseline = (
        value.get("runtimeAvailable") is True
        and value.get("validated") is True
        and value["captureRequests"] >= value["minimumSuccessFrames"]
        and value["framesObserved"] >= value["minimumSuccessFrames"]
        and value["framesAttempted"] == value["framesSucceeded"]
        and value["framesSucceeded"] >= value["minimumSuccessFrames"]
        and value["framesUnsupported"] == 0
        and value["framesFailed"] == 0
        and value["operations"] > 0
        and value["draws"] > 0
        and value["clears"] > 0
        and value["transfers"] > 0
        and value["barriers"] > 0
        and value["capturedBytes"] >= 0
        and value["initializedResources"] > 0
        and isinstance(value.get("lastOutputHashUnsigned"), str)
        and value.get("lastOutputHashUnsigned") != "0"
        and value["blockerCount"] == 0
        and value.get("blockerSummary") == ""
        and value.get("lastFailure") == ""
    )
    if not baseline:
        return False
    if not graph_ownership_required:
        return (
            value.get("ownershipOptedIn") is False
            and value.get("ownershipMode") == "OPENGL_VISIBLE_VALIDATION"
            and value.get("performanceEligible") is False
            and value["framesPlanned"] == value["framesAttempted"]
            and all(value[name] == 0 for name in (
                "ownershipSubmissions", "ownershipReady",
                "ownershipFramesArmed", "ownershipFramesPresented",
                "ownershipFramesReused", "ownershipFramesInvalidated",
                "ownershipCommandsSuppressed",
                "ownershipFailures",
            ))
            and value.get("ownershipLastFailure") == ""
        )
    return (
        value.get("ownershipOptedIn") is True
        and value.get("ownershipMode") == "METAL_FULL_GRAPH_OWNERSHIP"
        and value.get("performanceEligible") is True
        and value["ownershipSubmissions"] > 0
        and value["ownershipReady"] > 0
        and value["ownershipFramesArmed"] >= 30
        and value["ownershipFramesPresented"] >= 30
        and value["ownershipFramesReused"]
            <= value["ownershipFramesPresented"]
        and value["ownershipCommandsSuppressed"] > 0
        and value["ownershipFailures"] == 0
        and value.get("ownershipLastFailure") == ""
        and value["framesPlanned"]
            == value["framesAttempted"] + value["ownershipSubmissions"]
                + value["ownershipFramesInvalidated"]
                + value["framesPending"]
    )


def shadow_plan_capture_is_valid(
    value: Any,
    render_graph: Any,
    backend: str,
) -> bool:
    if not isinstance(value, dict) or not isinstance(render_graph, dict):
        return False
    plans = value.get("plansObserved")
    complete = value.get("structurallyComplete")
    blocked = value.get("blocked")
    blockers = value.get("blockerCount")
    summary = value.get("blockerSummary")
    if not all(type(item) is int for item in (
            plans, complete, blocked, blockers)):
        return False
    expected_complete = (
        plans > 0
        and complete == plans
        and blocked == 0
        and value.get("executionSteps", 0) > 0
        and blockers == 0
        and value.get("blockerSetComplete") is True
    )
    return (
        plans > 0
        and plans == complete + blocked
        and plans == render_graph.get("graphsSucceeded")
        and type(value.get("executionSteps")) is int
        and value.get("executionSteps", 0) >= plans
        and blockers >= 0
        and value.get("blockerSetComplete") is True
        and isinstance(value.get("blockerSetSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}", value["blockerSetSha256"]
        ) is not None
        and isinstance(summary, str)
        and ((blockers == 0) == (summary == ""))
        and value.get("complete") is expected_complete
        and type(value.get("offscreenIrisReplayExecuted")) is bool
        and type(value.get("openGlDrawsSuppressed")) is int
        and (value.get("openGlDrawsSuppressed", 0) > 0
             if backend == "metal4"
             else value.get("openGlDrawsSuppressed") == 0)
    )


def shadow_replay_is_valid(
    value: Any,
    backend: str,
    pipeline_cache: Any,
) -> bool:
    if not isinstance(value, dict) or not isinstance(pipeline_cache, dict):
        return False
    integer_fields = (
        "drawsObserved", "drawsReady", "drawsAttempted",
        "drawsSucceeded", "drawsUnsupported", "drawsFailed",
        "drawsBlocked", "candidateCount", "successfulPhaseCount",
        "lastWidth", "lastHeight", "blockerCount",
    )
    if not all(type(value.get(name)) is int and value.get(name) >= 0
               for name in integer_fields):
        return False
    attempts = value["drawsAttempted"]
    succeeded = value["drawsSucceeded"]
    common = (
        value.get("enabled") is True
        and value["drawsObserved"] > 0
        and attempts == succeeded + value["drawsUnsupported"]
            + value["drawsFailed"]
        and value["candidateCount"] == attempts
        and value.get("candidateSetComplete") is True
        and isinstance(value.get("successfulPhaseSummary"), str)
        and isinstance(value.get("lastColorHashUnsigned"), str)
        and re.fullmatch(r"[0-9]+", value["lastColorHashUnsigned"])
            is not None
        and value.get("blockerSetComplete") is True
        and isinstance(value.get("blockerSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["blockerSetSha256"])
            is not None
        and isinstance(value.get("blockerSummary"), str)
        and ((value["blockerCount"] == 0)
             == (value["blockerSummary"] == ""))
        and value.get("offscreenOnly") is True
        and value.get("openGlDrawsSuppressed") == 0
        and type(value.get("visualParityValidated")) is bool
        and value.get("visualParityValidated") == (backend == "metal4")
    )
    if not common:
        return False
    if backend == "metal3":
        return (
            attempts == 0
            and succeeded == 0
            and value["drawsFailed"] == 0
            and value["drawsBlocked"] > 0
            and value.get("executionComplete") is False
            and pipeline_cache.get("nativeDrawAttempts") == 0
        )
    return (
        attempts > 0
        and succeeded > 0
        and value["drawsUnsupported"] == 0
        and value["drawsFailed"] == 0
        and value["successfulPhaseCount"] >= 4
        and all(phase in value["successfulPhaseSummary"] for phase in (
            "SHADOW", "GEOMETRY", "COMPOSITE", "FINAL"))
        and value["lastColorHashUnsigned"] != "0"
        and value["lastWidth"] > 0
        and value["lastHeight"] > 0
        and pipeline_cache.get("nativeDrawAttempts", 0) >= succeeded
    )


def visual_parity_is_valid(value: Any, backend: str) -> bool:
    if not isinstance(value, dict):
        return False
    integer_fields = (
        "channelTolerance", "minimumFrames", "requiredConsecutivePasses",
        "captureScheduled", "captureSucceeded", "captureFailed",
        "captureDropped", "capturePending", "samplesHandled",
        "missingOpenGlFrames", "missingMetalFrames",
        "dimensionMismatches", "replayFailures", "framesCompared",
        "framesPassed", "framesFailed", "consecutivePasses",
        "worstChannelDelta", "blockerCount",
    )
    if not all(type(value.get(name)) is int and value.get(name) >= 0
               for name in integer_fields):
        return False
    common = (
        value.get("enabled") is True
        and value["channelTolerance"] == 2
        and value["minimumFrames"] == 3
        and value["requiredConsecutivePasses"] == 3
        and value["captureScheduled"] > 0
        and value["captureSucceeded"] > 0
        and value["captureFailed"] == 0
        and value["captureDropped"] == 0
        and value.get("captureLastFailure") == ""
        and value.get("orientation") == "native-row-order"
        and value.get("comparisonTarget")
            == "iris-opengl-final-vs-offscreen-metal-final"
        and value.get("blockerSetComplete") is True
        and isinstance(value.get("blockerSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["blockerSetSha256"])
            is not None
        and isinstance(value.get("blockerSummary"), str)
        and isinstance(value.get("maxDifferentPixelRatio"), (int, float))
        and isinstance(value.get("maxRootMeanSquareError"), (int, float))
        and isinstance(value.get("worstDifferentPixelRatio"), (int, float))
        and isinstance(value.get("worstRootMeanSquareError"), (int, float))
    )
    if not common:
        return False
    if backend == "metal3":
        return (
            value.get("validated") is False
            and value["samplesHandled"] >= 4
            and value["framesCompared"] == 0
            and value["framesPassed"] == 0
            and value["framesFailed"] == 0
            and value["replayFailures"] > 0
            and value["blockerCount"] > 0
        )
    return (
        value.get("validated") is True
        and value["samplesHandled"] >= value["minimumFrames"]
        and value["framesCompared"] >= value["minimumFrames"]
        and value["framesPassed"] == value["framesCompared"]
        and value["framesFailed"] == 0
        and value["consecutivePasses"]
            >= value["requiredConsecutivePasses"]
        and value["missingOpenGlFrames"] == 0
        and value["missingMetalFrames"] == 0
        and value["dimensionMismatches"] == 0
        and value["replayFailures"] == 0
        and value["blockerCount"] == 0
        and value["blockerSummary"] == ""
        and value["worstDifferentPixelRatio"]
            <= value["maxDifferentPixelRatio"]
        and value["worstRootMeanSquareError"]
            <= value["maxRootMeanSquareError"]
    )


def final_cutover_is_valid(
    value: Any,
    backend: str,
    graph_ownership_required: bool,
) -> bool:
    if not isinstance(value, dict):
        return False
    integer_fields = (
        "currentFrame", "contextGeneration", "drawsObserved",
        "drawsEligible", "metalAttempts", "metalSucceeded",
        "presentations", "openGlDrawsSuppressed", "frameFallbacks",
        "lifecycleResets", "failures", "gpuInputTextures",
        "gpuInputBytes", "cpuInputTextures", "cpuInputBytes",
        "gpuInputBuffers", "gpuInputBufferBytes", "cpuInputBuffers",
        "cpuInputBufferBytes",
        "pipelineLookupCount",
        "failureReasonCount", "screenshotSampledUniqueColors",
    )
    if not all(type(value.get(name)) is int for name in integer_fields):
        return False
    nonnegative = tuple(name for name in integer_fields
                        if name not in {"currentFrame", "contextGeneration"})
    common = (
        value.get("enabled") is True
        and value["currentFrame"] > 0
        and value["contextGeneration"] > 0
        and all(value[name] >= 0 for name in nonnegative)
        and value.get("frameFallback") is False
        and value.get("bridge") == "iosurface-gpu-handoff"
        and value.get("performanceEligible") is False
        and value["pipelineLookupCount"] > 0
        and value.get("failureReasonSetComplete") is True
        and isinstance(value.get("failureReasonSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}",
                         value["failureReasonSetSha256"]) is not None
        and isinstance(value.get("failureReasonSummary"), str)
        and isinstance(value.get("lastFailure"), str)
        and isinstance(value.get("screenshotLuminanceStdDev"),
                       (int, float))
        and isinstance(value.get("screenshotReferenceMad"), (int, float))
        and isinstance(value.get("screenshotPath"), str)
        and value["screenshotPath"] != ""
    )
    if not common:
        return False
    if backend == "metal3":
        return (
            value.get("active") is False
            and value.get("mode") == "SHADOW"
            and value["drawsEligible"] == 0
            and value["metalAttempts"] == 0
            and value["metalSucceeded"] == 0
            and value["presentations"] == 0
            and value["openGlDrawsSuppressed"] == 0
            and value["gpuInputTextures"] == 0
            and value["gpuInputBytes"] == 0
            and value["cpuInputTextures"] == 0
            and value["cpuInputBytes"] == 0
            and value["gpuInputBuffers"] == 0
            and value["gpuInputBufferBytes"] == 0
            and value["cpuInputBuffers"] == 0
            and value["cpuInputBufferBytes"] == 0
            and value["frameFallbacks"] == 0
            and value["failures"] == 0
            and value["failureReasonCount"] == 0
            and value["failureReasonSummary"] == ""
            and value["lastFailure"] == ""
            and value.get("screenshotCaptured") is False
            and value["screenshotReferenceMad"] == 0.0
        )
    if graph_ownership_required:
        return (
            value["failures"] == 0
            and value["failureReasonCount"] == 0
            and value["failureReasonSummary"] == ""
            and value["lastFailure"] == ""
            and value.get("screenshotCaptured") is True
            and value["screenshotSampledUniqueColors"] >= 64
            and value["screenshotLuminanceStdDev"] >= 0.02
            and 0.0 <= value["screenshotReferenceMad"] < 0.20
        )
    return (
        value.get("active") is True
        and value.get("mode") == "ACTIVE"
        and value["drawsObserved"] >= value["drawsEligible"]
        and value["drawsEligible"] == value["metalAttempts"]
        and value["metalAttempts"] == value["metalSucceeded"]
        and value["metalSucceeded"] == value["presentations"]
        and value["presentations"] == value["openGlDrawsSuppressed"]
        and value["presentations"] >= 30
        and value["gpuInputTextures"] >= value["presentations"] * 3
        and value["gpuInputBytes"] > 0
        and value["cpuInputTextures"] == 0
        and value["cpuInputBytes"] == 0
        and value["gpuInputBuffers"] >= value["presentations"] * 2
        and value["gpuInputBufferBytes"] > 0
        and value["cpuInputBuffers"] == 0
        and value["cpuInputBufferBytes"] == 0
        and value["frameFallbacks"] == 0
        and value["lifecycleResets"] == 0
        and value["failures"] == 0
        and value["failureReasonCount"] == 0
        and value["failureReasonSummary"] == ""
        and value["lastFailure"] == ""
        and value.get("screenshotCaptured") is True
        and value["screenshotSampledUniqueColors"] >= 64
        and value["screenshotLuminanceStdDev"] >= 0.02
        and 0.0 <= value["screenshotReferenceMad"] < 0.20
    )


def metal_pipeline_cache_is_complete(
    value: Any,
    backend: str,
    cache_expectation: str,
) -> bool:
    if not isinstance(value, dict):
        return False
    common = (
        value.get("activeGatePassed") is True
        and value.get("enabled") is True
        and type(value.get("candidatesObserved")) is int
        and value.get("candidatesObserved", 0) > 0
        and value.get("executionBlockedCandidates") == 0
        and value.get("pending") == 0
        and value.get("queueRejected") == 0
        and value.get("failureReasonCount") == 0
        and value.get("failureReasonSetComplete") is True
        and isinstance(value.get("failureReasonSetSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}", value["failureReasonSetSha256"]
        ) is not None
        and value.get("lastFailure") == ""
        and type(value.get("nativeDrawAttempts")) is int
        and value.get("nativeDrawAttempts", -1) >= 0
        and type(value.get("offscreenGeneratedMslReplayObserved")) is bool
        and value.get("visibleMetalExecution") is (backend == "metal4")
    )
    if not common:
        return False
    candidates = value["candidatesObserved"]
    compiled = value.get("compiled")
    cache_hits = value.get("cacheHits")
    new_variants = value.get("newVariantsCompiled")
    known_archive_misses = value.get("knownArchiveMisses")
    if not all(
        type(counter) is int and counter >= 0
        for counter in (
            compiled, cache_hits, new_variants, known_archive_misses
        )
    ):
        return False
    if backend == "metal3":
        return (
            value.get("complete") is False
            and value.get("safeUnsupportedFallback") is True
            and value.get("readiness") == "UNSUPPORTED"
            and value.get("deviceCompilerSha256") == ""
            and value.get("attempted") == 0
            and compiled == 0
            and cache_hits == 0
            and new_variants == 0
            and known_archive_misses == 0
            and value.get("unsupported") == candidates
            and value.get("failed") == 0
            and value.get("archiveFlushed") is False
            and value.get("archiveFlushFailures") == 0
            and value.get("pipelineIdentityCount") == 0
            and value.get("pipelineIdentitySetComplete") is True
            and value.get("nativeAttempts") == 0
            and value.get("nativeCompiled") == 0
            and value.get("nativeCacheHits") == 0
            and value.get("nativeFailures") == 0
            and value.get("nativeLivePipelines") == 0
            and value.get("nativeDrawAttempts") == 0
            and value.get("offscreenGeneratedMslReplayObserved") is False
        )
    cache_classification_is_valid = (
        known_archive_misses == 0
        and compiled == new_variants
        and compiled + cache_hits == candidates
        and (
            cache_expectation != "cold"
            or (compiled == candidates and cache_hits == 0)
        )
    )
    return (
        value.get("complete") is True
        and value.get("safeUnsupportedFallback") is False
        and value.get("readiness") == "READY"
        and isinstance(value.get("deviceCompilerSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}", value["deviceCompilerSha256"]
        ) is not None
        and value.get("attempted") == candidates
        and cache_classification_is_valid
        and value.get("unsupported") == 0
        and value.get("failed") == 0
        and value.get("archiveFlushed") is True
        and value.get("archiveFlushFailures") == 0
        and value.get("pipelineIdentityCount") == candidates
        and value.get("pipelineIdentitySetComplete") is True
        and isinstance(value.get("pipelineSetSha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", value["pipelineSetSha256"])
            is not None
        and value.get("nativeAttempts") == candidates
        and value.get("nativeCompiled") == compiled
        and value.get("nativeCacheHits") == cache_hits
        and value.get("nativeFailures") == 0
        and type(value.get("nativeStaleArchivesRecovered")) is int
        and value.get("nativeStaleArchivesRecovered", -1) >= 0
        and value.get("nativeLivePipelines") == candidates
        and value.get("nativeDrawAttempts", 0) > 0
        and value.get("offscreenGeneratedMslReplayObserved") is True
    )


def iris_runtime_ownership_check_passed(
    value: Any,
    backend: str,
    graph_ownership_required: bool,
) -> bool:
    return (
        isinstance(value, dict)
        and value.get("evidenceKind")
            == "indirect-runtime-ownership-check"
        and value.get("assertedDrawBackend") == (
            "METAL4_FULL_GRAPH_OWNERSHIP"
            if graph_ownership_required
            else "METAL4_FINAL_CUTOVER_WITH_OPENGL_FALLBACK"
                if backend == "metal4" else "OPENGL")
        and value.get("measuredAtRuntime") is True
        and value.get("passed") is True
    )


def generated_msl_static_boundary_is_explicit(
    value: Any,
    backend: str,
    graph_ownership_required: bool,
) -> bool:
    return (
        isinstance(value, dict)
        and value.get("runtimeTelemetryAvailable") is True
        and value.get("assertedStaticBoundary") == (
            "full-graph-metal-ownership"
            if graph_ownership_required
            else "selective-final-cutover"
                if backend == "metal4"
                else "offscreen-shadow-replay-only")
        and type(value.get("nativeDrawAttempts")) is int
        and (value.get("nativeDrawAttempts", 0) > 0
             if backend == "metal4"
             else value.get("nativeDrawAttempts") == 0)
        and type(value.get("visibleOpenGlDrawsSuppressed")) is int
        and (value.get("visibleOpenGlDrawsSuppressed", 0) > 0
             if backend == "metal4"
             else value.get("visibleOpenGlDrawsSuppressed") == 0)
    )


def msl_library_validation_matches_exact_workload(
    value: Any,
    cache_expectation: str,
) -> bool:
    if not msl_library_validation_is_safe(value):
        return False
    expected_from_translation = (
        EXPECTED_COMPLEMENTARY_STAGES
        if cache_expectation == "cold" else 0
    )
    expected_from_cache = (
        EXPECTED_COMPLEMENTARY_STAGES
        if cache_expectation == "warm" else 0
    )
    exact_counts = {
        "programsAttempted": EXPECTED_COMPLEMENTARY_PROGRAMS,
        "programsSucceeded": EXPECTED_COMPLEMENTARY_PROGRAMS,
        "programsUnsupported": 0,
        "programsFailed": 0,
        "stagesAttempted": EXPECTED_COMPLEMENTARY_STAGES,
        "stagesSucceeded": EXPECTED_COMPLEMENTARY_STAGES,
        "stagesUnsupported": 0,
        "stagesFailed": 0,
        "stagesRejected": 0,
        "stagesPending": 0,
        "stagesInFlight": 0,
        "stagesFromTranslation": expected_from_translation,
        "stagesFromCache": expected_from_cache,
        "nativeCompileAttempts": EXPECTED_COMPLEMENTARY_STAGES,
        "nativeCompileSuccesses": EXPECTED_COMPLEMENTARY_STAGES,
        "nativeCompileUnsupported": 0,
        "nativeCompileFailures": 0,
        "statusLiveLibraries": 0,
    }
    return (
        all(type(value.get(name)) is int and value.get(name) == expected
            for name, expected in exact_counts.items())
        and value.get("compiledArtifactSetComplete") is True
        and isinstance(value.get("compiledArtifactSetSha256"), str)
        and re.fullmatch(
            r"[0-9a-f]{64}", value["compiledArtifactSetSha256"])
            is not None
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
        graph_ownership_required = manifest["graphOwnershipRequired"]
        actual_path = Path(driver_result["exactJarPath"]).resolve()
        expected_path = Path(release["runtimePath"]).resolve()
    except (KeyError, OSError, RuntimeError, TypeError, ValueError):
        return False

    native_faults = driver_result.get("nativeFaultCounters")
    iris_translation = driver_result.get("irisTranslation")
    msl_library_validation = driver_result.get(
        "generatedMslLibraryValidation")
    pipeline_state_capture = driver_result.get(
        "irisPipelineStateCapture")
    resource_reflection = driver_result.get("irisResourceReflection")
    render_graph = driver_result.get("irisRenderGraph")
    graph_resources = driver_result.get("irisMetalGraphResources")
    full_graph = driver_result.get("irisMetalFullGraph")
    shadow_plan = driver_result.get("irisShadowExecutionPlan")
    shadow_replay = driver_result.get("irisMetalShadowReplay")
    visual_parity = driver_result.get("irisMetalVisualParity")
    final_cutover = driver_result.get("irisMetalFinalCutover")
    metal_pipeline_cache = driver_result.get("irisMetalPipelineCache")
    iris_runtime_ownership = driver_result.get("irisRuntimeOwnership")
    generated_msl_boundary = driver_result.get(
        "generatedMslExecutionBoundary")
    if not isinstance(iris_translation, dict):
        return False
    return (
        driver_result.get("schemaVersion") == 14
        and driver_result.get("status") == "PASS"
        and actual_path == expected_path
        and driver_result.get("exactJarSha256") == release.get("sha256")
        and driver_result.get("metalrenderVersion") == release.get("version")
        and driver_result.get("cacheExpectation") == cache_expectation
        and driver_result.get("backendExpectation") == backend
        and driver_result.get("graphOwnershipRequired")
            is graph_ownership_required
        and driver_result.get("backendMode") == expected_backend_mode(
            backend, graph_ownership_required)
        and type(driver_result.get("metal4Active")) is bool
        and driver_result.get("metal4Active") == (backend == "metal4")
        and driver_result.get("metal4DrawPathActive")
            is graph_ownership_required
        and "irisDrawBackend" not in driver_result
        and "generatedMslExecuted" not in driver_result
        and iris_runtime_ownership_check_passed(
            iris_runtime_ownership, backend, graph_ownership_required)
        and generated_msl_static_boundary_is_explicit(
            generated_msl_boundary, backend, graph_ownership_required)
        and iris_translation.get("pipelineStatus") == (
            "cached" if backend == "metal4"
            else "unsupported-safe-fallback")
        and driver_result.get("dimensionRoute") == [
            "minecraft:overworld", "minecraft:the_nether",
            "minecraft:the_end", "minecraft:overworld",
        ]
        and pipeline_state_capture_is_complete(
            pipeline_state_capture, backend)
        and resource_reflection_is_complete(resource_reflection)
        and render_graph_is_complete(
            render_graph, backend, graph_ownership_required)
        and metal_graph_resources_are_valid(
            graph_resources, render_graph, backend)
        and full_metal_graph_is_valid(
            full_graph, backend, graph_ownership_required)
        and shadow_plan_capture_is_valid(
            shadow_plan, render_graph, backend)
        and shadow_replay_is_valid(
            shadow_replay, backend, metal_pipeline_cache)
        and visual_parity_is_valid(visual_parity, backend)
        and final_cutover_is_valid(
            final_cutover, backend, graph_ownership_required)
        and metal_pipeline_cache_is_complete(
            metal_pipeline_cache, backend, cache_expectation)
        and msl_library_validation_is_safe(msl_library_validation)
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


def stage9_lifecycle_is_valid(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    positive_dimensions = (
        "originalWindowWidth", "originalWindowHeight",
        "originalFramebufferWidth", "originalFramebufferHeight",
        "resizedWindowWidth", "resizedWindowHeight",
    )
    return (
        value.get("schemaVersion") == 1
        and value.get("status") == "PASS"
        and all(value.get(name) is True for name in (
            "resizePassed", "fullscreenPassed", "windowedRestorePassed",
            "surfaceSuspendRestorePassed"))
        and all(type(value.get(name)) is int and value[name] > 0
                for name in positive_dimensions)
        and (value["originalWindowWidth"],
             value["originalWindowHeight"])
            != (value["resizedWindowWidth"],
                value["resizedWindowHeight"])
        and type(value.get("ownershipPresentationDelta")) is int
        and value["ownershipPresentationDelta"]
            >= LIFECYCLE_MINIMUM_PRESENTATIONS
        and type(value.get("openGlSuppressionDelta")) is int
        and value["openGlSuppressionDelta"] > 0
        and type(value.get("ownershipInvalidationDelta")) is int
        and 0 <= value["ownershipInvalidationDelta"]
            <= LIFECYCLE_MAXIMUM_INVALIDATIONS
        and value.get("ownershipFailureDelta") == 0
    )


def hardware_display_is_valid(
    value: Any,
    require_retina: bool,
    require_display_migration: bool,
    minimum_refresh_hz: int,
    expected_samples: int,
) -> bool:
    if not isinstance(value, dict):
        return False
    reported_refresh = value.get("reportedRefreshHz")
    measured_refresh = value.get("measuredPresentationHz")
    p50 = value.get("presentationIntervalP50Nanos")
    p95 = value.get("presentationIntervalP95Nanos")
    p99 = value.get("presentationIntervalP99Nanos")
    if (type(reported_refresh) is not int or reported_refresh <= 0
            or not isinstance(measured_refresh, (int, float))
            or type(measured_refresh) is bool or measured_refresh <= 0
            or type(p50) is not int or type(p95) is not int
            or type(p99) is not int or not 0 < p50 <= p95 <= p99):
        return False
    expected_period = 1_000_000_000 / reported_refresh
    required_cadence = minimum_refresh_hz or reported_refresh
    retina_valid = (
        value.get("retinaRequired") is require_retina
        and (not require_retina or (
            value.get("retinaPassed") is True
            and isinstance(value.get("retinaMonitor"), str)
            and value.get("retinaFramebufferScaleX", 0) >= 1.5
            and value.get("retinaFramebufferScaleY", 0) >= 1.5
        ))
    )
    return (
        value.get("schemaVersion") == 1
        and value.get("status") == "PASS"
        and type(value.get("connectedDisplays")) is int
        and value["connectedDisplays"] >= (2 if require_display_migration else 1)
        and value.get("displayMigrationRequired") is require_display_migration
        and (not require_display_migration
             or value.get("displayMigrationPassed") is True)
        and retina_valid
        and value.get("minimumRefreshHz") == minimum_refresh_hz
        and reported_refresh >= max(1, minimum_refresh_hz)
        and isinstance(value.get("refreshMonitor"), str)
        and value.get("presentationMode") == "software-paced-vsync-off"
        and value.get("presentationSamples") == expected_samples
        and p50 <= round(expected_period * 1.35)
        and p95 <= round(expected_period * 3.0)
        and measured_refresh >= required_cadence * 0.80
        and type(value.get("presentCallP50Nanos")) is int
        and value["presentCallP50Nanos"] > 0
        and value.get("presentationStutters") == 0
        and type(value.get("ownershipPresentationDelta")) is int
        and value["ownershipPresentationDelta"] >= expected_samples
        and type(value.get("displayTransitionDelta")) is int
        and value["displayTransitionDelta"] > 0
        and type(value.get("displayResetDelta")) is int
        and value["displayResetDelta"] > 0
        and value.get("ownershipFailureDelta") == 0
    )


def percentile_nanos(values: list[int], percentile: int) -> int:
    ordered = sorted(values)
    index = (percentile * len(ordered) + 99) // 100 - 1
    return ordered[max(0, index)]


def stage9_performance_is_valid(
    value: Any,
    side: str,
    expected_samples: int,
) -> bool:
    if (side not in SUPPORTED_PERFORMANCE_SIDES
            or not isinstance(value, dict)
            or value.get("schemaVersion") != 1
            or value.get("status") != "PASS"
            or value.get("side") != side
            or value.get("samples") != expected_samples
            or not isinstance(value.get("scenarioDescriptor"), str)
            or not value["scenarioDescriptor"]
            or not isinstance(value.get("scenarioSha256"), str)
            or re.fullmatch(r"[0-9a-f]{64}", value["scenarioSha256"])
                is None
            or hashlib.sha256(value["scenarioDescriptor"].encode(
                "utf-8")).hexdigest() != value["scenarioSha256"]):
        return False
    cpu = value.get("cpuNanos")
    gpu = value.get("gpuNanos")
    metrics = value.get("metrics")
    if (not isinstance(cpu, list) or not isinstance(gpu, list)
            or len(cpu) != expected_samples
            or len(gpu) != expected_samples
            or not all(type(sample) is int and sample > 0 for sample in cpu)
            or not all(type(sample) is int and sample > 0 for sample in gpu)
            or not isinstance(metrics, dict)):
        return False
    cpu_stutters = sum(sample >= 100_000_000 for sample in cpu)
    gpu_stutters = sum(sample >= 100_000_000 for sample in gpu)
    expected_metrics = {
        "cpuP50Nanos": percentile_nanos(cpu, 50),
        "cpuP95Nanos": percentile_nanos(cpu, 95),
        "cpuP99Nanos": percentile_nanos(cpu, 99),
        "gpuP50Nanos": percentile_nanos(gpu, 50),
        "gpuP95Nanos": percentile_nanos(gpu, 95),
        "gpuP99Nanos": percentile_nanos(gpu, 99),
        "stutters": max(cpu_stutters, gpu_stutters),
    }
    return (
        all(metrics.get(name) == expected
            for name, expected in expected_metrics.items())
        and value.get("droppedGpuSamples") == 0
        and value.get("instrumentationErrors") == 0
        and value.get("cpuStutters") == cpu_stutters
        and value.get("gpuStutters") == gpu_stutters
        and value.get("metalFeedbackErrors") == 0
    )


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
    require_graph_ownership: bool,
    performance_side: str,
    performance_samples: int,
    hardware_display: bool,
    require_retina: bool,
    require_display_migration: bool,
    minimum_refresh_hz: int,
    presentation_samples: int,
) -> list[str]:
    game = runtime / "game"
    natives = runtime / "natives"
    command = [
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
        f"-Dmetalrender.exactJar.lifecycleEvidencePath="
        f"{runtime / 'evidence' / 'lifecycle-result.json'}",
        f"-Dmetalrender.exactJar.performanceEvidencePath="
        f"{runtime / 'evidence' / 'performance-result.json'}",
        f"-Dmetalrender.exactJar.performanceSide={performance_side}",
        f"-Dmetalrender.exactJar.performanceSamples={performance_samples}",
        "-Dmetalrender.exactJar.hardwareDisplay="
        f"{'true' if hardware_display else 'false'}",
        f"-Dmetalrender.exactJar.hardwareDisplayEvidencePath="
        f"{runtime / 'evidence' / 'hardware-display-result.json'}",
        "-Dmetalrender.exactJar.requireRetina="
        f"{'true' if require_retina else 'false'}",
        "-Dmetalrender.exactJar.requireDisplayMigration="
        f"{'true' if require_display_migration else 'false'}",
        f"-Dmetalrender.exactJar.minimumRefreshHz={minimum_refresh_hz}",
        f"-Dmetalrender.exactJar.presentationSamples={presentation_samples}",
        f"-Dmetalrender.exactJar.shaderPack={shader_pack}",
        "-Dmetalrender.exactJar.fastIrisDrain=true",
        "-Dmetalrender.exactJar.diagnosticDisableFinalCutover=false",
        "-Dmetalrender.exactJar.diagnosticFullGraphCutNode=-1",
        "-Dmetalrender.exactJar.diagnosticFullGraphCutTexture=-1",
        "-Dmetalrender.exactJar.diagnosticFreshTextureProgram=",
        "-Dmetalrender.exactJar.diagnosticGraphReadbackNode=-1",
        "-Dmetalrender.exactJar.diagnosticGraphReadbackProgram=",
        "-Dmetalrender.exactJar.diagnosticGraphReadbackMipLevel=-1",
        "-Dmetalrender.exactJar.cacheExpectation=cold",
        f"-Dmetalrender.exactJar.backend={backend}",
        "-Dmetalrender.exactJar.requireGraphOwnership="
        f"{'true' if require_graph_ownership else 'false'}",
        f"-Dmetalrender.feature.metal4="
        f"{'true' if backend == 'metal4' else 'false'}",
    ]
    if not uses_packaged_stable_iris_metal_defaults(
            parse_project_property("mod_version")):
        command.extend([
            "-Dmetalrender.experimental.irisMetalPipeline=true",
            "-Dmetalrender.experimental.irisMetalTranslation=true",
            "-Dmetalrender.experimental.irisMetalLibraryValidation=true",
            "-Dmetalrender.experimental.irisMetalPipelineCompilation=true",
            "-Dmetalrender.experimental.irisMetalShadowReplay=true",
            "-Dmetalrender.experimental.irisMetalVisualParity=true",
            "-Dmetalrender.experimental.irisMetalFinalCutover=true",
            "-Dmetalrender.experimental.irisMetalGraphResources=true",
            "-Dmetalrender.experimental.irisMetalGraphExecution=true",
            "-Dmetalrender.experimental.irisMetalGraphOwnership="
            f"{'true' if require_graph_ownership else 'false'}",
        ])
    command.extend([
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
    ])
    return command


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


def inspect_translation_cache(
    cache_root: Path,
    backend: str,
) -> dict[str, Any]:
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
    generated_library_files = [
        path for path in regular_files
        if path.suffix == ".metallib"
        or path.name == "metal.binarchive"
    ]
    pipeline_archive_pattern = re.compile(
        r"pipelines\.mtl4archive(?:\.segment-([0-9]{2}))?"
    )
    pipeline_archives = [
        path for path in regular_files
        if pipeline_archive_pattern.fullmatch(path.name)
    ]
    pipeline_archive_bases = [
        path for path in pipeline_archives
        if path.name == "pipelines.mtl4archive"
    ]
    pipeline_archive_segments = [
        path for path in pipeline_archives
        if path.name != "pipelines.mtl4archive"
    ]
    pipeline_archive_segment_indexes = sorted(
        int(match.group(1))
        for path in pipeline_archive_segments
        if (match := pipeline_archive_pattern.fullmatch(path.name))
        and match.group(1) is not None
    )
    pipeline_archive_layout_valid = (
        len(pipeline_archive_bases) == 1
        and all(
            path.parent == pipeline_archive_bases[0].parent
            for path in pipeline_archive_segments
        )
        and pipeline_archive_segment_indexes
            == list(range(1, len(pipeline_archive_segments) + 1))
        and len(pipeline_archive_segments) <= 32
    ) if backend == "metal4" else not pipeline_archives
    pipeline_archives_valid = True
    for path in pipeline_archives:
        integrity = Path(str(path) + ".integrity")
        payload = path.read_bytes()
        hash_value = 1469598103934665603
        for byte in payload:
            hash_value ^= byte
            hash_value = (hash_value * 1099511628211) & 0xffffffffffffffff
        expected_integrity = (
            f"v1:{len(payload)}:{hash_value:016x}\n"
        )
        pipeline_archives_valid = (
            pipeline_archives_valid
            and not path.is_symlink()
            and 64 <= len(payload) <= 1024 * 1024 * 1024
            and payload[:4] == b"\xcb\xfe\xba\xbe"
            and integrity.is_file()
            and not integrity.is_symlink()
            and integrity.read_text(encoding="ascii", errors="strict")
                == expected_integrity
        )
    complete_markers = [
        path for path in regular_files
        if path.name == "translation.complete"
    ]
    manifests = [
        path for path in regular_files if path.name == "manifest.properties"
    ]
    state_manifests = [
        path for path in regular_files if path.name == "state.properties"
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
    referenced_msl_paths: set[Path] = set()
    msl_artifact_identities: list[str] = []
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
        program_key = properties.get("key.sha256")
        if not isinstance(program_key, str) or not re.fullmatch(
                r"[0-9a-f]{64}", program_key):
            artifact_integrity = False
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
                if (artifact == "msl" and path.is_file()
                        and not path.is_symlink()
                        and path.suffix == suffix
                        and isinstance(program_key, str)
                        and re.fullmatch(r"[0-9a-f]{64}", program_key)):
                    actual_msl_sha256 = sha256(path)
                    referenced_msl_paths.add(path)
                    msl_artifact_identities.append(
                        f"{program_key}/{stage}/{actual_msl_sha256}")
    actual_msl_paths = {path.resolve() for path in msl_files}
    unique_msl_artifact_identities = sorted(set(msl_artifact_identities))
    msl_artifact_set_digest = hashlib.sha256()
    for identity in unique_msl_artifact_identities:
        msl_artifact_set_digest.update(identity.encode("ascii"))
        msl_artifact_set_digest.update(b"\n")
    msl_artifact_set_complete = (
        referenced_msl_paths == actual_msl_paths
        and len(msl_artifact_identities) == len(msl_files)
        and len(unique_msl_artifact_identities) == len(msl_files)
    )
    valid_markers = all(
        not path.is_symlink() and 0 < path.stat().st_size <= 4096
        for path in complete_markers
    )
    pipeline_state_integrity = True
    pipeline_state_identities: list[str] = []
    state_domain = b"metalrender.iris.pipeline-state.v1"
    for metadata in state_manifests:
        properties: dict[str, str] = {}
        for line in metadata.read_text(
                encoding="ascii", errors="strict").splitlines():
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            properties[key] = value
        state_path = metadata.parent / "state.bin"
        pipeline_key = properties.get("pipeline.key.sha256", "")
        shader_key = properties.get("shader.key.sha256", "")
        canonical_sha = properties.get("state.canonical.sha256", "")
        valid = (
            properties.get("format")
                == "metalrender-iris-pipeline-state"
            and properties.get("schema") == "1"
            and properties.get("state.canonical") == "state.bin"
            and properties.get("specialization.scanned") == "true"
            and properties.get("pipeline.status") == "pending"
            and properties.get("source.original_glsl_persisted") == "false"
            and re.fullmatch(r"[0-9a-f]{64}", pipeline_key) is not None
            and re.fullmatch(r"[0-9a-f]{64}", shader_key) is not None
            and re.fullmatch(r"[0-9a-f]{64}", canonical_sha) is not None
            and metadata.parent.name == pipeline_key
            and state_path.is_file()
            and not state_path.is_symlink()
        )
        if valid:
            canonical = state_path.read_bytes()
            reconstructed = hashlib.sha256(
                struct.pack(">q", len(state_domain))
                + state_domain
                + struct.pack(">q", len(canonical))
                + canonical
            ).hexdigest()
            valid = (
                len(canonical) > 0
                and properties.get("state.canonical.bytes")
                    == str(len(canonical))
                and hashlib.sha256(canonical).hexdigest() == canonical_sha
                and reconstructed == pipeline_key
                and len(canonical) >= 72
                and struct.unpack(">q", canonical[:8])[0] == 64
                and canonical[8:72].decode("ascii") == shader_key
            )
        pipeline_state_integrity = pipeline_state_integrity and valid
        if valid:
            pipeline_state_identities.append(
                f"{pipeline_key}|{shader_key}|{canonical_sha}")
    unique_pipeline_state_identities = sorted(
        set(pipeline_state_identities))
    pipeline_state_set_digest = hashlib.sha256()
    for identity in unique_pipeline_state_identities:
        pipeline_state_set_digest.update(identity.encode("ascii"))
        pipeline_state_set_digest.update(b"\n")
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
        "mslArtifactSetComplete":
            bool(msl_files) and msl_artifact_set_complete,
        "completionMarkersBounded": valid_markers,
        "hasPipelineStates": bool(state_manifests),
        "pipelineStateIntegrity": bool(state_manifests)
            and pipeline_state_integrity,
        "pipelineStateIdentitySetComplete":
            len(pipeline_state_identities) == len(state_manifests)
            and len(unique_pipeline_state_identities)
                == len(state_manifests),
        "entryCountsMatch":
            len(manifests) == len(complete_markers),
        "noSymlinks": not any(path.is_symlink() for path in all_paths),
        "noRawGlslFiles": not glsl_files,
        "noGeneratedLibraryOrPipelineArtifacts":
            not generated_library_files,
        "pipelineArchiveCountMatchesBackend": pipeline_archive_layout_valid,
        "pipelineArchivesBoundedRegular": pipeline_archives_valid,
    }
    return {
        "path": str(cache_root),
        "status": "PASS" if all(checks.values()) else "FAIL",
        "checks": checks,
        "fileCount": len(regular_files),
        "completeTranslationCount": len(complete_markers),
        "spirvStageCount": len(spirv_files),
        "mslStageCount": len(msl_files),
        "mslArtifactIdentityCount": len(unique_msl_artifact_identities),
        "mslArtifactSetSha256": msl_artifact_set_digest.hexdigest(),
        "pipelineStateCount": len(state_manifests),
        "pipelineStateIdentityCount":
            len(unique_pipeline_state_identities),
        "pipelineStateSetSha256": pipeline_state_set_digest.hexdigest(),
        "generatedLibraryOrPipelineArtifacts": [
            str(path) for path in generated_library_files
        ],
        "metal4PipelineArchives": [
            str(path) for path in pipeline_archives
        ],
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


def set_system_property(
    command: list[str],
    name: str,
    value: str,
) -> list[str]:
    prefix = f"-D{name}="
    if any(argument.startswith(prefix) for argument in command):
        return replace_system_property(command, name, value)
    try:
        classpath_index = command.index("-classpath")
    except ValueError as error:
        raise HarnessError("launch command has no classpath boundary") from error
    return [
        *command[:classpath_index],
        prefix + value,
        *command[classpath_index:],
    ]


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
    graph_ownership_required = manifest.get("graphOwnershipRequired")
    if graph_ownership_required is not args.require_graph_ownership:
        raise HarnessError(
            "requested graph ownership profile does not match prepared "
            "runtime")
    performance_side = manifest.get("performanceSide", "")
    requested_performance_side = args.performance_side or ""
    requested_performance_samples = (
        args.performance_samples if requested_performance_side else 0)
    if (performance_side != requested_performance_side
            or manifest.get("performanceSamples")
                != requested_performance_samples):
        raise HarnessError(
            "requested performance profile does not match prepared runtime")
    hardware_display = manifest.get("hardwareDisplay", False)
    requested_hardware_display = args.hardware_display
    requested_presentation_samples = (
        args.presentation_samples if requested_hardware_display else 0)
    requested_minimum_refresh_hz = (
        args.minimum_refresh_hz if requested_hardware_display else 0)
    if (hardware_display is not requested_hardware_display
            or manifest.get("requireRetina", False) is not
                args.require_retina
            or manifest.get("requireDisplayMigration", False) is not
                args.require_display_migration
            or manifest.get("minimumRefreshHz", 0)
                != requested_minimum_refresh_hz
            or manifest.get("presentationSamples", 0)
                != requested_presentation_samples):
        raise HarnessError(
            "requested hardware display profile does not match prepared "
            "runtime")
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
    lifecycle_result_path = (
        runtime / "evidence" /
        f"lifecycle-result-{cache_expectation}.json"
    )
    performance_result_path = (
        runtime / "evidence" /
        f"performance-result-{cache_expectation}.json"
    )
    hardware_display_result_path = (
        runtime / "evidence" /
        f"hardware-display-result-{cache_expectation}.json"
    )
    for stale in (driver_result_path, lifecycle_result_path,
                  performance_result_path, hardware_display_result_path,
                  result_path):
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
        command, "metalrender.exactJar.lifecycleEvidencePath",
        str(lifecycle_result_path))
    command = replace_system_property(
        command, "metalrender.exactJar.performanceEvidencePath",
        str(performance_result_path))
    command = replace_system_property(
        command, "metalrender.exactJar.performanceSide",
        performance_side)
    command = replace_system_property(
        command, "metalrender.exactJar.performanceSamples",
        str(manifest["performanceSamples"]))
    command = replace_system_property(
        command, "metalrender.exactJar.hardwareDisplay",
        "true" if hardware_display else "false")
    command = replace_system_property(
        command, "metalrender.exactJar.hardwareDisplayEvidencePath",
        str(hardware_display_result_path))
    command = replace_system_property(
        command, "metalrender.exactJar.requireRetina",
        "true" if manifest.get("requireRetina", False) else "false")
    command = replace_system_property(
        command, "metalrender.exactJar.requireDisplayMigration",
        "true" if manifest.get("requireDisplayMigration", False)
        else "false")
    command = replace_system_property(
        command, "metalrender.exactJar.minimumRefreshHz",
        str(manifest.get("minimumRefreshHz", 0)))
    command = replace_system_property(
        command, "metalrender.exactJar.presentationSamples",
        str(manifest.get("presentationSamples", 0)))
    command = replace_system_property(
        command, "metalrender.exactJar.backend", backend)
    command = replace_system_property(
        command, "metalrender.feature.metal4",
        "true" if backend == "metal4" else "false")
    command = replace_system_property(
        command, "metalrender.exactJar.requireGraphOwnership",
        "true" if graph_ownership_required else "false")
    activation = manifest.get("irisMetalActivation")
    if activation == "legacy-explicit-flags":
        command = replace_system_property(
            command, "metalrender.experimental.irisMetalGraphOwnership",
            "true" if graph_ownership_required else "false")
    elif activation == "packaged-stable-default":
        forbidden_enable_flags = [
            argument for argument in command
            if argument.startswith(
                "-Dmetalrender.experimental.irisMetal")
            and argument.endswith("=true")
        ]
        if forbidden_enable_flags:
            raise HarnessError(
                "stable-default launch unexpectedly contains legacy Iris "
                f"Metal enable flags: {forbidden_enable_flags}")
    else:
        raise HarnessError(
            f"prepared manifest has invalid Iris Metal activation: "
            f"{activation}")
    if args.diagnostic_disable_final_cutover:
        # Isolation-only switch: the normal exact-JAR acceptance profile keeps
        # this feature enabled and will not PASS without visible cutover
        # evidence.  This switch lets a crash investigation distinguish the
        # IOSurface presentation bridge from the full Metal graph executor.
        command = set_system_property(
            command, "metalrender.experimental.irisMetalFinalCutover",
            "false")
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticDisableFinalCutover",
            "true")
    if args.diagnostic_full_graph_cut_node >= 0:
        # Isolation-only graph bisection. At this node the replay packet uses
        # the captured OpenGL inputs instead of Metal-owned graph resources,
        # allowing the first divergent stage to be localized without relaxing
        # visual-parity acceptance.
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticFullGraphCutNode",
            str(args.diagnostic_full_graph_cut_node))
    if args.diagnostic_full_graph_cut_texture >= 0:
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticFullGraphCutTexture",
            str(args.diagnostic_full_graph_cut_texture))
    if args.diagnostic_fresh_texture_program:
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticFreshTextureProgram",
            args.diagnostic_fresh_texture_program)
    if args.diagnostic_graph_readback_node >= 0:
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticGraphReadbackNode",
            str(args.diagnostic_graph_readback_node))
    if args.diagnostic_graph_readback_program:
        command = replace_system_property(
            command, "metalrender.exactJar.diagnosticGraphReadbackProgram",
            args.diagnostic_graph_readback_program)
    if args.diagnostic_graph_readback_mip_level >= 0:
        command = replace_system_property(
            command,
            "metalrender.exactJar.diagnosticGraphReadbackMipLevel",
            str(args.diagnostic_graph_readback_mip_level))
    launch_environment = clean_environment()
    if args.diagnostic_native_asan:
        command[0] = str(diagnostic_asan_java(runtime, command))
        xcode_runtimes = sorted(Path(
            "/Applications/Xcode.app/Contents/Developer/Toolchains/"
            "XcodeDefault.xctoolchain/usr/lib/clang"
        ).glob("*/lib/darwin/libclang_rt.asan_osx_dynamic.dylib"))
        if xcode_runtimes:
            asan_runtime = require_file(
                xcode_runtimes[-1], "Xcode AddressSanitizer runtime")
        else:
            resource_dir = run_checked(
                ["xcrun", "clang", "-print-resource-dir"]
            ).stdout.strip()
            asan_runtime = require_file(
                Path(resource_dir) / "lib" / "darwin" /
                "libclang_rt.asan_osx_dynamic.dylib",
                "Clang AddressSanitizer runtime",
            )
        launch_environment["DYLD_INSERT_LIBRARIES"] = str(asan_runtime)
        launch_environment["ASAN_OPTIONS"] = (
            "abort_on_error=1:detect_leaks=0:check_initialization_order=1:"
            "strict_string_checks=1:allocator_may_return_null=1"
        )
    started = dt.datetime.now(dt.timezone.utc)
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            command,
            cwd=runtime / "game",
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            env=launch_environment,
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
    lifecycle_result = (
        read_json(lifecycle_result_path)
        if lifecycle_result_path.is_file() else None
    )
    performance_result = (
        read_json(performance_result_path)
        if performance_result_path.is_file() else None
    )
    hardware_display_result = (
        read_json(hardware_display_result_path)
        if hardware_display_result_path.is_file() else None
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
        runtime / "iris-metal-cache", backend)
    iris_translation = (
        driver_result.get("irisTranslation", {})
        if isinstance(driver_result, dict) else {}
    )
    msl_library_validation = (
        driver_result.get("generatedMslLibraryValidation", {})
        if isinstance(driver_result, dict) else {}
    )
    pipeline_state_capture = (
        driver_result.get("irisPipelineStateCapture", {})
        if isinstance(driver_result, dict) else {}
    )
    metal_pipeline_cache = (
        driver_result.get("irisMetalPipelineCache", {})
        if isinstance(driver_result, dict) else {}
    )
    render_graph = (
        driver_result.get("irisRenderGraph", {})
        if isinstance(driver_result, dict) else {}
    )
    graph_resources = (
        driver_result.get("irisMetalGraphResources", {})
        if isinstance(driver_result, dict) else {}
    )
    full_graph = (
        driver_result.get("irisMetalFullGraph", {})
        if isinstance(driver_result, dict) else {}
    )
    shadow_plan = (
        driver_result.get("irisShadowExecutionPlan", {})
        if isinstance(driver_result, dict) else {}
    )
    shadow_replay = (
        driver_result.get("irisMetalShadowReplay", {})
        if isinstance(driver_result, dict) else {}
    )
    final_cutover = (
        driver_result.get("irisMetalFinalCutover", {})
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
        "requiredScreenshots": len(screenshots) == (
            4 if backend == "metal4" else 3),
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
        "generatedMslLibrariesValidated":
            msl_library_validation_matches_exact_workload(
                msl_library_validation, cache_expectation),
        "compiledMslArtifactSetMatchesCache":
            translation_cache.get("mslArtifactIdentityCount")
                == EXPECTED_COMPLEMENTARY_STAGES
            and msl_library_validation.get("compiledArtifactSetSha256")
                == translation_cache.get("mslArtifactSetSha256"),
        "pipelineStateCaptureCompleted":
            pipeline_state_capture_is_complete(
                pipeline_state_capture, backend),
        "pipelineStateSetMatchesCache":
            (
                translation_cache.get("pipelineStateIdentityCount")
                    == pipeline_state_capture.get("stateIdentityCount")
                and translation_cache.get("pipelineStateSetSha256")
                    == pipeline_state_capture.get("stateSetSha256")
                if cache_expectation == "cold"
                else isinstance(
                    translation_cache.get("pipelineStateIdentityCount"),
                    int)
                and isinstance(
                    pipeline_state_capture.get("stateIdentityCount"), int)
                and translation_cache.get("pipelineStateIdentityCount", 0)
                    >= pipeline_state_capture.get("stateIdentityCount", 0)
            ),
        "shadowExecutionPlanCaptured":
            shadow_plan_capture_is_valid(
                shadow_plan, render_graph, backend),
        "metalGraphResourcesValidated":
            metal_graph_resources_are_valid(
                graph_resources, render_graph, backend),
        "fullMetalGraphValidated":
            full_metal_graph_is_valid(
                full_graph, backend, graph_ownership_required),
        "offscreenIrisMetalReplayValidated":
            shadow_replay_is_valid(
                shadow_replay, backend, metal_pipeline_cache),
        "visibleFinalCutoverValidated":
            final_cutover_is_valid(
                final_cutover, backend, graph_ownership_required),
        "stage9LifecycleValidated":
            (not graph_ownership_required
             and lifecycle_result is None)
            or (graph_ownership_required
                and stage9_lifecycle_is_valid(lifecycle_result)),
        "stage9PerformanceCaptured":
            (performance_side == "" and performance_result is None)
            or stage9_performance_is_valid(
                performance_result, performance_side,
                manifest["performanceSamples"]),
        "hardwareDisplayValidated":
            (not hardware_display and hardware_display_result is None)
            or hardware_display_is_valid(
                hardware_display_result,
                manifest.get("requireRetina", False),
                manifest.get("requireDisplayMigration", False),
                manifest.get("minimumRefreshHz", 0),
                manifest.get("presentationSamples", 0)),
        "stage9LifecycleLogged":
            not graph_ownership_required
            or "METALRENDER_STAGE9_LIFECYCLE PASS" in log_text,
        "stage9PerformanceLogged":
            performance_side == ""
            or ("METALRENDER_STAGE9_PERFORMANCE PASS side="
                + performance_side) in log_text,
        "hardwareDisplayLogged":
            not hardware_display
            or "METALRENDER_HARDWARE_DISPLAY PASS" in log_text,
        "metalPipelineCacheCompleted":
            metal_pipeline_cache_is_complete(
                metal_pipeline_cache, backend, cache_expectation),
        "metalPipelineArchiveValidated":
            translation_cache.get("checks", {}).get(
                "pipelineArchiveCountMatchesBackend") is True
            and translation_cache.get("checks", {}).get(
                "pipelineArchivesBoundedRegular") is True,
        "preparedManifestPresent": manifest_path.is_file(),
        "releaseSourceUnchangedAfterRun":
            release_source_matches(manifest),
    }
    run_result = {
        "schemaVersion": 4,
        "cacheExpectation": cache_expectation,
        "qaBackend": backend,
        "status": "PASS" if all(checks.values()) else "FAIL",
        "startedAt": started.isoformat(),
        "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "timeoutSeconds": args.timeout,
        "exitCode": return_code,
        "checks": checks,
        "driverResult": driver_result,
        "stage9Lifecycle": lifecycle_result,
        "stage9Performance": performance_result,
        "hardwareDisplay": hardware_display_result,
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
        "--require-graph-ownership", action="store_true",
        help=(
            "require Stage 9 full-frame Metal ownership, asynchronous "
            "IOSurface presentation, and upstream OpenGL suppression"
        ),
    )
    result.add_argument(
        "--performance-side", choices=SUPPORTED_PERFORMANCE_SIDES,
        help=(
            "capture uncapped raw Stage 9 timings; 'opengl' requires the "
            "Metal 3 fallback profile and 'metal' requires full Metal 4 "
            "graph ownership"
        ),
    )
    result.add_argument(
        "--performance-samples", type=performance_sample_count,
        default=MINIMUM_PERFORMANCE_SAMPLES,
        help=(
            "raw CPU/GPU samples for a matched performance side "
            f"({MINIMUM_PERFORMANCE_SAMPLES}-"
            f"{MAXIMUM_PERFORMANCE_SAMPLES})"
        ),
    )
    result.add_argument(
        "--hardware-display", action="store_true",
        help=(
            "run exact-JAR Retina, monitor migration, display lifecycle and "
            "real window-presentation cadence QA; requires Metal 4 full "
            "graph ownership"
        ),
    )
    result.add_argument(
        "--require-retina", action="store_true",
        help=(
            "require migration to a connected display with a 2x-class "
            "content and framebuffer scale"
        ),
    )
    result.add_argument(
        "--require-display-migration", action="store_true",
        help="require the QA window to visit two distinct connected displays",
    )
    result.add_argument(
        "--minimum-refresh-hz", type=positive_int, default=0,
        help=(
            "minimum active display refresh for hardware cadence QA "
            "(for example 200)"
        ),
    )
    result.add_argument(
        "--presentation-samples", type=presentation_sample_count,
        default=600,
        help=(
            "completed GLFW window presentations retained for hardware QA "
            f"({MINIMUM_PRESENTATION_SAMPLES}-"
            f"{MAXIMUM_PRESENTATION_SAMPLES})"
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
    result.add_argument(
        "--diagnostic-disable-final-cutover", action="store_true",
        help=(
            "diagnostic isolation only: launch with selective FINAL cutover "
            "disabled; this profile is not release-acceptance eligible"
        ),
    )
    result.add_argument(
        "--diagnostic-cache-seed",
        help=(
            "diagnostic isolation only: seed iris-metal-cache from an "
            "earlier build-local runtime to shorten iteration; this profile "
            "is not release-acceptance eligible"
        ),
    )
    result.add_argument(
        "--diagnostic-full-graph-cut-node", type=nonnegative_int,
        default=-1,
        help=(
            "diagnostic isolation only: at this render-graph node sample "
            "captured OpenGL inputs instead of Metal-owned graph resources; "
            "this profile is not release-acceptance eligible"
        ),
    )
    result.add_argument(
        "--diagnostic-fresh-texture-program",
        type=diagnostic_program_name, default="",
        help=(
            "diagnostic isolation only: recapture the selected Iris "
            "program's sampled textures immediately before its draw; pair "
            "with --diagnostic-full-graph-cut-node"
        ),
    )
    result.add_argument(
        "--diagnostic-full-graph-cut-texture", type=nonnegative_int,
        default=-1,
        help=(
            "diagnostic isolation only: make every full-graph consumer of "
            "this OpenGL texture name use its captured OpenGL snapshot"
        ),
    )
    result.add_argument(
        "--diagnostic-graph-readback-node", type=nonnegative_int,
        default=-1,
        help=(
            "diagnostic isolation only: stop the Metal graph after this "
            "draw node and read back its primary RGBA8 target"
        ),
    )
    result.add_argument(
        "--diagnostic-graph-readback-program",
        type=diagnostic_program_name, default="",
        help=(
            "diagnostic isolation only: capture the matching OpenGL draw "
            "output for --diagnostic-graph-readback-node"
        ),
    )
    result.add_argument(
        "--diagnostic-graph-readback-mip-level", type=diagnostic_mip_level,
        default=-1,
        help=(
            "diagnostic isolation only: read the selected graph texture "
            "mip level after glGenerateMipmap (0-30)"
        ),
    )
    result.add_argument(
        "--diagnostic-native-asan", action="store_true",
        help=(
            "diagnostic isolation only: preload Clang AddressSanitizer for "
            "a JAR built with METALRENDER_NATIVE_SANITIZER=address"
        ),
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


def performance_sample_count(value: str) -> int:
    parsed = positive_int(value)
    if not MINIMUM_PERFORMANCE_SAMPLES <= parsed <= MAXIMUM_PERFORMANCE_SAMPLES:
        raise argparse.ArgumentTypeError(
            "must be between "
            f"{MINIMUM_PERFORMANCE_SAMPLES} and "
            f"{MAXIMUM_PERFORMANCE_SAMPLES}")
    return parsed


def presentation_sample_count(value: str) -> int:
    parsed = positive_int(value)
    if not MINIMUM_PRESENTATION_SAMPLES <= parsed <= MAXIMUM_PRESENTATION_SAMPLES:
        raise argparse.ArgumentTypeError(
            "must be between "
            f"{MINIMUM_PRESENTATION_SAMPLES} and "
            f"{MAXIMUM_PRESENTATION_SAMPLES}")
    return parsed


def nonnegative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            "must be a non-negative integer") from error
    if parsed < 0:
        raise argparse.ArgumentTypeError("must not be negative")
    return parsed


def diagnostic_mip_level(value: str) -> int:
    parsed = nonnegative_int(value)
    if parsed > 30:
        raise argparse.ArgumentTypeError("must not exceed 30")
    return parsed


def diagnostic_program_name(value: str) -> str:
    if value == "":
        return value
    if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", value):
        raise argparse.ArgumentTypeError("invalid diagnostic program name")
    return value


def main() -> int:
    try:
        args = parser().parse_args()
        if args.require_graph_ownership and args.backend != "metal4":
            raise HarnessError(
                "--require-graph-ownership requires --backend metal4")
        if (args.performance_side == "metal"
                and (args.backend != "metal4"
                     or not args.require_graph_ownership)):
            raise HarnessError(
                "--performance-side metal requires --backend metal4 "
                "--require-graph-ownership")
        if (args.performance_side == "opengl"
                and (args.backend != "metal3"
                     or args.require_graph_ownership)):
            raise HarnessError(
                "--performance-side opengl requires --backend metal3")
        if args.hardware_display and (
                args.backend != "metal4"
                or not args.require_graph_ownership):
            raise HarnessError(
                "--hardware-display requires --backend metal4 "
                "--require-graph-ownership")
        if args.hardware_display and args.performance_side:
            raise HarnessError(
                "--hardware-display cannot share --performance-side")
        if not args.hardware_display and (
                args.require_retina
                or args.require_display_migration
                or args.minimum_refresh_hz != 0):
            raise HarnessError(
                "Retina, display migration and refresh requirements need "
                "--hardware-display")
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
                if manifest.get("graphOwnershipRequired") \
                        is not args.require_graph_ownership:
                    raise HarnessError(
                        "warm graph ownership expectation does not match "
                        "the prepared runtime"
                    )
                requested_performance_side = args.performance_side or ""
                requested_performance_samples = (
                    args.performance_samples
                    if requested_performance_side else 0)
                if (manifest.get("performanceSide")
                        != requested_performance_side
                        or manifest.get("performanceSamples")
                            != requested_performance_samples):
                    raise HarnessError(
                        "warm performance profile does not match the "
                        "prepared runtime")
                if (manifest.get("hardwareDisplay") is not
                        args.hardware_display
                        or manifest.get("requireRetina") is not
                        args.require_retina
                        or manifest.get("requireDisplayMigration") is not
                        args.require_display_migration
                        or manifest.get("minimumRefreshHz") != (
                            args.minimum_refresh_hz
                            if args.hardware_display else 0)
                        or manifest.get("presentationSamples") != (
                            args.presentation_samples
                            if args.hardware_display else 0)):
                    raise HarnessError(
                        "warm hardware display profile does not match the "
                        "prepared runtime")
                runtime_jar = require_file(
                    Path(manifest["release"]["runtimePath"]),
                    "prepared exact release JAR")
                if sha256(runtime_jar) != manifest["release"]["sha256"]:
                    raise HarnessError(
                        "prepared exact release JAR changed before warm run")
                verify_cold_result_for_warm(runtime, manifest)
            else:
                manifest = prepare(args)
                if args.diagnostic_cache_seed:
                    seed = safe_runtime_path(
                        Path(args.diagnostic_cache_seed))
                    if not seed.is_dir():
                        raise HarnessError(
                            f"diagnostic cache seed is not a directory: "
                            f"{seed}")
                    destination = runtime / "iris-metal-cache"
                    if destination.exists():
                        raise HarnessError(
                            "fresh runtime unexpectedly contains a cache")
                    shutil.copytree(seed, destination)
                    print(
                        "Seeded diagnostic Iris cache (not acceptance "
                        f"eligible): {seed}")
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
