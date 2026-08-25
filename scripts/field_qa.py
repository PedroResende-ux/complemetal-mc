#!/usr/bin/env python3
"""Run Complemetal's release-safe, full-modpack Minecraft field QA.

This harness launches the official Minecraft 26.2/Fabric production runtime in
an isolated game directory. It never edits a launcher profile or a real save.
The enabled and disabled sides use the same exact release JAR, shader settings,
compatibility mods, world scenarios and fullscreen display.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import platform
import shutil
import signal
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

import exact_jar_qa as exact


PROJECT = Path(__file__).resolve().parents[1]
SOURCES = PROJECT / "scripts" / "field-qa"
DEFAULT_ROOT = PROJECT / "build" / "field-qa" / "release-stability"
QA_MOD_ID = "complemetal-field-qa"
REQUIRED_MOD_IDS = {
    "fabric-api",
    "fabric-language-kotlin",
    "sodium",
    "iris",
    "immediatelyfast",
    "entityculling",
    "ferritecore",
    "lithium",
    "modmenu",
    "placeholder-api",
    "yet_another_config_lib_v3",
    "zoomify",
}
FORBIDDEN_LOG_MARKERS = (
    "GPU_ERROR:",
    "this.viewArea is null",
    "Iris-to-Metal translation and graph worker enabled",
    "graph-frame-packet-invalid",
    "graph-native-packet-rejected",
    "OpenGL=SUPPRESSED",
    "Frame skipped: in-flight GPU limit timed out",
    "Frame skipped: no IOSurface slot is safe for Metal",
    "[iosurface] frame not ready after bounded wait",
    "Mixin apply failed",
    "Mixin transformation of",
)


class FieldQaError(RuntimeError):
    pass


def safe_root(path: Path) -> Path:
    result = path.expanduser().resolve()
    build = (PROJECT / "build").resolve()
    if result == build or build not in result.parents:
        raise FieldQaError(f"field QA root must be below {build}: {result}")
    return result


def launcher_metadata(minecraft_home: Path) -> tuple[dict[str, Any],
                                                     dict[str, Any],
                                                     list[Path], Path]:
    vanilla_path = exact.require_file(
        minecraft_home / "versions" / exact.MC_VERSION /
        f"{exact.MC_VERSION}.json", "Minecraft version JSON")
    fabric_path = exact.require_file(
        minecraft_home / "versions" / exact.FABRIC_PROFILE /
        f"{exact.FABRIC_PROFILE}.json", "Fabric version JSON")
    vanilla = exact.read_json(vanilla_path)
    fabric = exact.read_json(fabric_path)
    libraries = exact.resolve_libraries(minecraft_home, vanilla, fabric)
    java = exact.java_home(minecraft_home)
    exact.require_file(java / "bin" / "java", "official Java executable")
    exact.require_file(java / "bin" / "javac", "official javac executable")
    exact.java_version(java)
    return vanilla, fabric, libraries, java


def launcher_mods(minecraft_home: Path) -> list[tuple[Path, dict[str, Any]]]:
    by_id: dict[str, list[tuple[Path, dict[str, Any]]]] = {}
    for candidate in sorted((minecraft_home / "mods").glob("*.jar")):
        try:
            metadata = exact.read_mod_metadata(candidate)
        except exact.HarnessError:
            continue
        by_id.setdefault(str(metadata.get("id")), []).append(
            (candidate.resolve(), metadata))
    missing = sorted(REQUIRED_MOD_IDS - set(by_id))
    if missing:
        raise FieldQaError(
            "launcher modpack is missing required mods: " + ", ".join(missing))
    duplicates = {
        mod_id: entries for mod_id, entries in by_id.items()
        if mod_id in REQUIRED_MOD_IDS and len(entries) != 1
    }
    if duplicates:
        raise FieldQaError("launcher modpack has duplicate required mod IDs: "
                           + ", ".join(sorted(duplicates)))
    return [by_id[mod_id][0] for mod_id in sorted(REQUIRED_MOD_IDS)]


def compile_driver(staging: Path, java: Path, classpath: list[Path],
                   release_jar: Path, iris: Path, gametest_api: Path) -> Path:
    classes = staging / "driver-classes"
    classes.mkdir(parents=True)
    compile_classpath = os.pathsep.join(str(item) for item in [
        *classpath, release_jar, iris, gametest_api,
    ])
    sources = sorted(SOURCES.glob("*.java"))
    if len(sources) != 3:
        raise FieldQaError(
            f"expected three field-QA Java sources, found {len(sources)}")
    exact.run_checked([
        str(java / "bin" / "javac"),
        "--release", "25",
        "-encoding", "UTF-8",
        "-classpath", compile_classpath,
        "-d", str(classes),
        *(str(source) for source in sources),
    ])
    driver = staging / "complemetal-field-qa-driver.jar"
    exact.run_checked([
        str(java / "bin" / "jar"),
        "--create", "--file", str(driver),
        "-C", str(classes), ".",
        "-C", str(SOURCES), "fabric.mod.json",
        "-C", str(SOURCES), "complemetal-field-qa.mixins.json",
    ])
    return driver


def copy_configuration(minecraft_home: Path, game: Path,
                       shader_pack: Path) -> None:
    source_config = minecraft_home / "config"
    if source_config.is_dir():
        shutil.copytree(source_config, game / "config", dirs_exist_ok=True)
    for flag in (
        "complemetal-debug-next-run.flag",
        "metalrender-debug-next-run.flag",
    ):
        (game / "config" / flag).unlink(missing_ok=True)
    (game / "config" / "iris.properties").write_text(
        "# Generated by Complemetal full-modpack field QA\n"
        "allowUnknownShaders=false\n"
        "colorSpace=SRGB\n"
        "disableUpdateMessage=true\n"
        "enableDebugOptions=false\n"
        "enableShaders=true\n"
        "maxShadowRenderDistance=32\n"
        f"shaderPack={shader_pack.name}\n",
        encoding="utf-8",
    )
    (game / "options.txt").write_text(
        "enableVsync:false\n"
        "fullscreen:false\n"
        "maxFps:260\n"
        "inactivityFpsLimit:\"minimized\"\n"
        "renderDistance:15\n"
        "simulationDistance:13\n"
        "pauseOnLostFocus:false\n"
        "onboardAccessibility:false\n"
        "soundCategory_master:0.0\n",
        encoding="utf-8",
    )


def launch_command(*, runtime: Path, minecraft_home: Path, java: Path,
                   classpath: list[Path], release_jar: Path,
                   release_sha256: str, shader_pack: Path, asset_index: str,
                   side: str,
                   minimum_refresh_hz: int) -> list[str]:
    game = runtime / "game"
    natives = runtime / "natives"
    result_path = runtime / "evidence" / "field-result.json"
    command = [
        str(java / "bin" / "java"),
        "-XstartOnFirstThread",
        "-Xms2G", "-Xmx6G", "-Xss1M",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        f"-Duser.home={runtime / 'home'}",
        f"-Djava.io.tmpdir={runtime / 'tmp'}",
        f"-Djava.library.path={natives / 'java'}",
        f"-Djna.tmpdir={natives / 'jna'}",
        f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives / 'lwjgl'}",
        f"-Dio.netty.native.workdir={natives / 'netty'}",
        "-Dminecraft.launcher.brand=complemetal-field-qa",
        "-Dminecraft.launcher.version=1",
        "-DFabricMcEmu= net.minecraft.client.main.Main ",
        "-Dfabric.development=false",
        "-Dfabric.client.gametest=true",
        f"-Dfabric.client.gametest.modid={QA_MOD_ID}",
        f"-Dcomplemetal.fieldQa.side={side}",
        f"-Dcomplemetal.fieldQa.resultPath={result_path}",
        f"-Dcomplemetal.fieldQa.expectedPath={release_jar}",
        f"-Dcomplemetal.fieldQa.expectedSha256={release_sha256}",
        f"-Dcomplemetal.fieldQa.shaderPack={shader_pack.name}",
        f"-Dcomplemetal.fieldQa.minimumRefreshHz={minimum_refresh_hz}",
        "-Dmetalrender.feature.metal4=true",
    ]
    if side == "baseline":
        command.append("-Dmetalrender.enabled=false")
    command.extend([
        "-classpath", os.pathsep.join(str(item) for item in classpath),
        "net.fabricmc.loader.impl.launch.knot.KnotClient",
        "--username", ("MetalQAEnabled" if side == "enabled"
                       else "MetalQABaseline"),
        "--version", exact.FABRIC_PROFILE,
        "--gameDir", str(game),
        "--assetsDir", str(minecraft_home / "assets"),
        "--assetIndex", asset_index,
        "--uuid", ("00000000-0000-4000-8000-000000000041"
                   if side == "enabled"
                   else "00000000-0000-4000-8000-000000000042"),
        "--accessToken", "0",
        "--clientId", "0",
        "--xuid", "0",
        "--versionType", "release",
        "--width", "1280", "--height", "720",
    ])
    return command


def prepare_side(*, side: str, root: Path, release_jar: Path,
                 minecraft_home: Path, shader_pack_name: str,
                 minimum_refresh_hz: int) -> dict[str, Any]:
    runtime = root / side
    runtime.parent.mkdir(parents=True, exist_ok=True)
    vanilla, fabric, classpath, java = launcher_metadata(minecraft_home)
    modpack = launcher_mods(minecraft_home)
    iris = next(path for path, metadata in modpack
                if metadata.get("id") == "iris")
    fapi_version = exact.parse_project_property("fabric_api_version")
    gametest_api, gametest_metadata = exact.find_client_gametest_api(
        Path.home(), fapi_version)
    shader_pack = exact.require_file(
        minecraft_home / "shaderpacks" / shader_pack_name,
        "Complementary shader pack")
    shader_options = shader_pack.with_name(shader_pack.name + ".txt")

    staging = Path(tempfile.mkdtemp(prefix=f".field-qa-{side}-",
                                    dir=root.parent))
    installed = False
    try:
        game = staging / "game"
        mods = game / "mods"
        for directory in (
            mods, game / "config", game / "shaderpacks",
            staging / "evidence", staging / "natives", staging / "home",
            staging / "tmp",
        ):
            directory.mkdir(parents=True, exist_ok=True)
        final_jar = mods / release_jar.name
        shutil.copy2(release_jar, final_jar)
        copied_mods: list[dict[str, str]] = []
        for source, metadata in modpack:
            destination = mods / source.name
            shutil.copy2(source, destination)
            copied_mods.append({
                "id": str(metadata["id"]),
                "version": str(metadata["version"]),
                "path": str(destination),
                "sha256": exact.sha256(destination),
            })
        gametest_destination = mods / gametest_api.name
        shutil.copy2(gametest_api, gametest_destination)
        copied_shader = game / "shaderpacks" / shader_pack.name
        shutil.copy2(shader_pack, copied_shader)
        if shader_options.is_file():
            shutil.copy2(shader_options,
                         game / "shaderpacks" / shader_options.name)
        copy_configuration(minecraft_home, game, shader_pack)
        driver = compile_driver(
            staging, java, classpath, release_jar, iris, gametest_api)
        driver_destination = mods / driver.name
        shutil.move(driver, driver_destination)

        final_runtime_jar = runtime / "game" / "mods" / release_jar.name
        command = launch_command(
            runtime=runtime, minecraft_home=minecraft_home, java=java,
            classpath=classpath, release_jar=final_runtime_jar,
            release_sha256=exact.sha256(release_jar),
            shader_pack=shader_pack, asset_index=vanilla["assetIndex"]["id"],
            side=side, minimum_refresh_hz=minimum_refresh_hz)
        manifest = {
            "schemaVersion": 1,
            "status": "PREPARED",
            "side": side,
            "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "productionFabricEnvironment": True,
            "networkDownloadsRequired": False,
            "minecraft": exact.MC_VERSION,
            "fabricProfile": exact.FABRIC_PROFILE,
            "release": {
                "sourcePath": str(release_jar),
                "runtimePath": str(final_runtime_jar),
                "sha256": exact.sha256(release_jar),
                "metadata": exact.read_mod_metadata(release_jar),
            },
            "shaderPack": {
                "name": shader_pack.name,
                "sha256": exact.sha256(shader_pack),
            },
            "compatibilityMods": copied_mods,
            "clientGametestApi": {
                "version": str(gametest_metadata["version"]),
                "sha256": exact.sha256(gametest_destination),
            },
            "qaDriverSha256": exact.sha256(driver_destination),
            "minimumRefreshHz": minimum_refresh_hz,
            "launchCommand": command,
        }
        (staging / "prepare-manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")
        if runtime.exists():
            shutil.rmtree(runtime)
        staging.rename(runtime)
        installed = True
    finally:
        if not installed:
            shutil.rmtree(staging, ignore_errors=True)
    return json.loads((runtime / "prepare-manifest.json").read_text(
        encoding="utf-8"))


def terminate(process: subprocess.Popen[bytes]) -> int:
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


def run_side(manifest: dict[str, Any], root: Path,
             timeout: int) -> dict[str, Any]:
    side = str(manifest["side"])
    runtime = root / side
    command = [str(item) for item in manifest["launchCommand"]]
    log_path = runtime / "client-stdout.log"
    result_path = runtime / "evidence" / "field-result.json"
    started = dt.datetime.now(dt.timezone.utc)
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            command, cwd=runtime / "game", stdout=log,
            stderr=subprocess.STDOUT, start_new_session=True,
            env=exact.clean_environment())
        try:
            return_code = process.wait(timeout=timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            timed_out = True
            return_code = terminate(process)
        finally:
            if process.poll() is None:
                return_code = terminate(process)
    log_text = log_path.read_text(encoding="utf-8", errors="replace")
    result = (json.loads(result_path.read_text(encoding="utf-8"))
              if result_path.is_file() else None)
    crashes = sorted((runtime / "game" / "crash-reports").glob("*.txt"))
    screenshots = sorted((runtime / "game" / "screenshots").glob(
        "*complemetal-field-qa-*.png"))
    forbidden = [marker for marker in FORBIDDEN_LOG_MARKERS
                 if marker in log_text]
    expected_screenshots = 14 if side == "enabled" else 13
    checks = {
        "processExitedZero": return_code == 0,
        "didNotTimeout": not timed_out,
        "driverReportedPass": bool(result and result.get("status") == "PASS"),
        "normalShutdownLogged": "Stopping!" in log_text,
        "noCrashReports": not crashes,
        "noForbiddenDiagnostics": not forbidden,
        "requiredScreenshots": len(screenshots) == expected_screenshots,
        "allScreenshotsNonBlack": bool(result)
            and len(result.get("screenshots", [])) == expected_screenshots
            and all(item.get("meanLuminance", 0) >= 0.005
                    and item.get("luminanceStdDev", 0) >= 0.008
                    and item.get("blackPixelFraction", 1) < 0.985
                    for item in result.get("screenshots", [])),
        "fullScreenHighRefresh": bool(result)
            and result.get("display", {}).get("fullscreen") is True
            and result.get("display", {}).get("refreshHz", 0)
                >= manifest["minimumRefreshHz"],
        "fullCompatibilityModSet": bool(result)
            and REQUIRED_MOD_IDS.issubset(result.get("mods", {})),
        "stableIrisPathStayedDisabled": bool(result)
            and result.get("experimentalIrisMetalEnabled") is False,
        "sustainedPastOneMinute": bool(result)
            and result.get("durationNanos", 0) >= 60_000_000_000,
    }
    run_result = {
        "schemaVersion": 1,
        "status": "PASS" if all(checks.values()) else "FAIL",
        "side": side,
        "startedAt": started.isoformat(),
        "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "timeoutSeconds": timeout,
        "exitCode": return_code,
        "checks": checks,
        "driverResult": result,
        "forbiddenDiagnostics": forbidden,
        "crashReports": [str(path) for path in crashes],
        "stdoutLog": {"path": str(log_path), "sha256": exact.sha256(log_path)},
        "screenshots": [
            {"path": str(path), "sha256": exact.sha256(path),
             "bytes": path.stat().st_size}
            for path in screenshots
        ],
    }
    run_path = runtime / "run-result.json"
    run_path.write_text(json.dumps(run_result, indent=2,
                                    ensure_ascii=False) + "\n",
                        encoding="utf-8")
    if run_result["status"] != "PASS":
        raise FieldQaError(
            f"{side} field QA failed; inspect {run_path} and {log_path}")
    return run_result


def comparison(enabled: dict[str, Any], baseline: dict[str, Any],
               root: Path) -> dict[str, Any]:
    enabled_perf = enabled["driverResult"]["stationaryPerformance"]
    baseline_perf = baseline["driverResult"]["stationaryPerformance"]
    enabled_fps = float(enabled_perf["averageFps"])
    baseline_fps = float(baseline_perf["averageFps"])
    enabled_low = float(enabled_perf["onePercentLowFps"])
    baseline_low = float(baseline_perf["onePercentLowFps"])
    fps_delta = (enabled_fps / baseline_fps - 1.0) * 100.0
    low_delta = (enabled_low / baseline_low - 1.0) * 100.0
    enabled_heap = int(enabled["driverResult"]["memory"]["heapUsedBytes"])
    baseline_heap = int(baseline["driverResult"]["memory"]["heapUsedBytes"])
    heap_delta = enabled_heap - baseline_heap
    checks = {
        "enabledPassed": enabled["status"] == "PASS",
        "baselinePassed": baseline["status"] == "PASS",
        "averageFpsWithinTenPercent": enabled_fps >= baseline_fps * 0.90,
        "onePercentLowWithinTwentyFivePercent":
            enabled_low >= baseline_low * 0.75,
        "sameFullscreenMode":
            enabled["driverResult"]["display"]
            == baseline["driverResult"]["display"],
        "sameShaderPack":
            enabled["driverResult"]["shaderPack"]
            == baseline["driverResult"]["shaderPack"],
        "retainedHeapWithin256MiB":
            enabled_heap <= baseline_heap + 256 * 1024 * 1024,
    }
    value = {
        "schemaVersion": 1,
        "status": "PASS" if all(checks.values()) else "FAIL",
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "checks": checks,
        "enabled": {
            "averageFps": enabled_fps,
            "onePercentLowFps": enabled_low,
            "p50Nanos": enabled_perf["p50Nanos"],
            "p95Nanos": enabled_perf["p95Nanos"],
            "p99Nanos": enabled_perf["p99Nanos"],
        },
        "baseline": {
            "averageFps": baseline_fps,
            "onePercentLowFps": baseline_low,
            "p50Nanos": baseline_perf["p50Nanos"],
            "p95Nanos": baseline_perf["p95Nanos"],
            "p99Nanos": baseline_perf["p99Nanos"],
        },
        "deltaPercent": {
            "averageFps": fps_delta,
            "onePercentLowFps": low_delta,
        },
        "retainedHeapBytes": {
            "enabled": enabled_heap,
            "baseline": baseline_heap,
            "delta": heap_delta,
        },
        "display": enabled["driverResult"]["display"],
        "shaderPack": enabled["driverResult"]["shaderPack"],
    }
    path = root / "comparison.json"
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")
    if value["status"] != "PASS":
        raise FieldQaError(f"enabled/baseline comparison failed: {path}")
    return value


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Run real-client Complemetal full-modpack field QA")
    result.add_argument(
        "--jar", default=str(PROJECT / "build" / "libs" /
                             f"{exact.parse_project_property('archives_base_name')}-"
                             f"{exact.parse_project_property('mod_version')}.jar"))
    result.add_argument("--root", default=str(DEFAULT_ROOT))
    result.add_argument("--minecraft-home", default=str(
        Path.home() / "Library" / "Application Support" / "minecraft"))
    result.add_argument("--shader-pack", default=exact.DEFAULT_SHADER_PACK)
    result.add_argument("--minimum-refresh-hz", type=positive_int, default=200)
    result.add_argument("--timeout", type=positive_int, default=720)
    result.add_argument("--side", choices=("enabled", "baseline", "both"),
                        default="both")
    return result


def main() -> int:
    args = parser().parse_args()
    if platform.system() != "Darwin" or exact.normalize_arch(
            platform.machine()) != "arm64":
        raise FieldQaError("field QA requires Apple Silicon macOS")
    root = safe_root(Path(args.root))
    root.parent.mkdir(parents=True, exist_ok=True)
    release_jar = exact.require_file(Path(args.jar), "release JAR")
    minecraft_home = Path(args.minecraft_home).expanduser().resolve()
    requested = ("enabled", "baseline") if args.side == "both" else (args.side,)
    results: dict[str, dict[str, Any]] = {}
    with exact.runtime_lock(root):
        for side in requested:
            print(f"[field-qa] preparing {side}", flush=True)
            manifest = prepare_side(
                side=side, root=root, release_jar=release_jar,
                minecraft_home=minecraft_home,
                shader_pack_name=args.shader_pack,
                minimum_refresh_hz=args.minimum_refresh_hz)
            print(f"[field-qa] running {side}", flush=True)
            results[side] = run_side(manifest, root, args.timeout)
            perf = results[side]["driverResult"]["stationaryPerformance"]
            print(
                f"[field-qa] {side} PASS: "
                f"{perf['averageFps']:.2f} FPS, "
                f"1% low {perf['onePercentLowFps']:.2f} FPS",
                flush=True)
        if args.side == "both":
            value = comparison(results["enabled"], results["baseline"], root)
            print(
                "[field-qa] A/B PASS: average FPS delta "
                f"{value['deltaPercent']['averageFps']:+.2f}%, "
                "1% low delta "
                f"{value['deltaPercent']['onePercentLowFps']:+.2f}%",
                flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FieldQaError, exact.HarnessError) as error:
        print(f"field QA error: {error}", file=os.sys.stderr)
        raise SystemExit(1)
