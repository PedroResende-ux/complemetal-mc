#!/usr/bin/env python3
"""Evaluate matched warm-cache OpenGL/Metal exact-JAR Stage 9 evidence."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Any


DEFAULT_MINIMUM_IMPROVEMENT = 0.05
DEFAULT_MAXIMUM_TAIL_REGRESSION = 0.05
STUTTER_NANOS = 100_000_000


class GateError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as error:
        raise GateError(f"could not read {path}: {error}") from error
    if not isinstance(value, dict):
        raise GateError(f"expected a JSON object in {path}")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: list[int], value: int) -> int:
    ordered = sorted(values)
    index = (value * len(ordered) + 99) // 100 - 1
    return ordered[max(0, index)]


def metrics(cpu: list[int], gpu: list[int]) -> dict[str, int]:
    cpu_stutters = sum(sample >= STUTTER_NANOS for sample in cpu)
    gpu_stutters = sum(sample >= STUTTER_NANOS for sample in gpu)
    return {
        "samples": len(cpu),
        "cpuP50Nanos": percentile(cpu, 50),
        "cpuP95Nanos": percentile(cpu, 95),
        "cpuP99Nanos": percentile(cpu, 99),
        "gpuP50Nanos": percentile(gpu, 50),
        "gpuP95Nanos": percentile(gpu, 95),
        "gpuP99Nanos": percentile(gpu, 99),
        "cpuStutters": cpu_stutters,
        "gpuStutters": gpu_stutters,
        "stutters": max(cpu_stutters, gpu_stutters),
    }


def require_samples(value: Any, side: str) -> tuple[list[int], list[int]]:
    if (not isinstance(value, dict)
            or value.get("status") != "PASS"
            or value.get("side") != side
            or not isinstance(value.get("scenarioSha256"), str)
            or re.fullmatch(r"[0-9a-f]{64}", value["scenarioSha256"])
                is None
            or value.get("droppedGpuSamples") != 0
            or value.get("instrumentationErrors") != 0
            or value.get("metalFeedbackErrors") != 0):
        raise GateError(f"invalid {side} Stage 9 performance evidence")
    cpu = value.get("cpuNanos")
    gpu = value.get("gpuNanos")
    expected = value.get("samples")
    if (type(expected) is not int or expected < 600
            or not isinstance(cpu, list) or not isinstance(gpu, list)
            or len(cpu) != expected or len(gpu) != expected
            or not all(type(sample) is int and sample > 0 for sample in cpu)
            or not all(type(sample) is int and sample > 0 for sample in gpu)):
        raise GateError(f"invalid raw {side} CPU/GPU sample arrays")
    return cpu, gpu


def require_run(path: Path, side: str) -> tuple[dict[str, Any], dict[str, Any]]:
    run = read_json(path)
    expected_backend = "metal3" if side == "opengl" else "metal4"
    driver = run.get("driverResult")
    performance = run.get("stage9Performance")
    if (run.get("status") != "PASS"
            or run.get("cacheExpectation") != "warm"
            or run.get("qaBackend") != expected_backend
            or not isinstance(driver, dict)
            or driver.get("status") != "PASS"):
        raise GateError(
            f"{side} input is not a PASS warm-cache {expected_backend} run")
    ownership_required = driver.get("graphOwnershipRequired")
    if ownership_required is not (side == "metal"):
        raise GateError(f"{side} input has the wrong ownership profile")
    visual = driver.get("irisMetalVisualParity")
    if (side == "metal" and (not isinstance(visual, dict)
            or visual.get("validated") is not True
            or visual.get("framesFailed") != 0)):
        raise GateError("Metal input lacks passed visual parity evidence")
    if side == "metal":
        lifecycle = run.get("stage9Lifecycle")
        if (not isinstance(lifecycle, dict)
                or lifecycle.get("status") != "PASS"
                or lifecycle.get("resizePassed") is not True
                or lifecycle.get("fullscreenPassed") is not True
                or lifecycle.get("windowedRestorePassed") is not True
                or lifecycle.get("surfaceSuspendRestorePassed") is not True
                or lifecycle.get("ownershipFailureDelta") != 0):
            raise GateError("Metal input lacks passed lifecycle evidence")
    require_samples(performance, side)
    return run, performance


def improvement(baseline: int, candidate: int) -> float:
    if baseline <= 0:
        raise GateError("baseline timing is not positive")
    return (baseline - candidate) / baseline


def regression(baseline: int, candidate: int) -> float:
    if baseline <= 0:
        raise GateError("baseline timing is not positive")
    return (candidate - baseline) / baseline


def ratio(value: str) -> float:
    try:
        parsed = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a decimal ratio") from error
    if not 0.0 <= parsed < 1.0:
        raise argparse.ArgumentTypeError("must be in [0, 1)")
    return parsed


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Evaluate matched Stage 9 exact-JAR A/B timings")
    result.add_argument("--opengl", required=True,
                        help="warm Metal 3/OpenGL run-result JSON")
    result.add_argument("--metal", required=True,
                        help="warm Metal 4 ownership run-result JSON")
    result.add_argument("--output", required=True,
                        help="destination Stage 9 gate JSON")
    result.add_argument(
        "--minimum-cpu-p50-improvement", type=ratio,
        default=DEFAULT_MINIMUM_IMPROVEMENT)
    result.add_argument(
        "--minimum-cpu-p95-improvement", type=ratio,
        default=DEFAULT_MINIMUM_IMPROVEMENT)
    result.add_argument(
        "--maximum-tail-regression", type=ratio,
        default=DEFAULT_MAXIMUM_TAIL_REGRESSION)
    return result


def main() -> int:
    args = parser().parse_args()
    output = Path(args.output).expanduser().resolve()
    try:
        open_gl_path = Path(args.opengl).expanduser().resolve()
        metal_path = Path(args.metal).expanduser().resolve()
        open_gl_run, open_gl_evidence = require_run(open_gl_path, "opengl")
        metal_run, metal_evidence = require_run(metal_path, "metal")
        if (open_gl_run["driverResult"].get("exactJarSha256")
                != metal_run["driverResult"].get("exactJarSha256")):
            raise GateError("A/B runs used different MetalRender JARs")
        if (open_gl_evidence["scenarioSha256"]
                != metal_evidence["scenarioSha256"]):
            raise GateError("A/B performance scenarios do not match")
        if open_gl_evidence["samples"] != metal_evidence["samples"]:
            raise GateError("A/B performance sample counts do not match")

        open_gl_cpu, open_gl_gpu = require_samples(
            open_gl_evidence, "opengl")
        metal_cpu, metal_gpu = require_samples(metal_evidence, "metal")
        baseline = metrics(open_gl_cpu, open_gl_gpu)
        candidate = metrics(metal_cpu, metal_gpu)
        comparisons = {
            "cpuP50Improvement": improvement(
                baseline["cpuP50Nanos"], candidate["cpuP50Nanos"]),
            "cpuP95Improvement": improvement(
                baseline["cpuP95Nanos"], candidate["cpuP95Nanos"]),
            "cpuP99Regression": regression(
                baseline["cpuP99Nanos"], candidate["cpuP99Nanos"]),
            "gpuP95Regression": regression(
                baseline["gpuP95Nanos"], candidate["gpuP95Nanos"]),
            "gpuP99Regression": regression(
                baseline["gpuP99Nanos"], candidate["gpuP99Nanos"]),
        }
        checks = {
            "sameExactJar": True,
            "sameScenario": True,
            "sameSampleCount": True,
            "cpuP50Benefit": comparisons["cpuP50Improvement"]
                >= args.minimum_cpu_p50_improvement,
            "cpuP95Benefit": comparisons["cpuP95Improvement"]
                >= args.minimum_cpu_p95_improvement,
            "cpuP99Bounded": comparisons["cpuP99Regression"]
                <= args.maximum_tail_regression,
            "gpuP95Bounded": comparisons["gpuP95Regression"]
                <= args.maximum_tail_regression,
            "gpuP99Bounded": comparisons["gpuP99Regression"]
                <= args.maximum_tail_regression,
            "stuttersNotRegressed": candidate["stutters"]
                <= baseline["stutters"],
        }
        result = {
            "schemaVersion": 1,
            "status": "PASS" if all(checks.values()) else "FAIL",
            "createdAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "exactJarSha256":
                open_gl_run["driverResult"]["exactJarSha256"],
            "scenarioSha256": open_gl_evidence["scenarioSha256"],
            "thresholds": {
                "minimumCpuP50Improvement":
                    args.minimum_cpu_p50_improvement,
                "minimumCpuP95Improvement":
                    args.minimum_cpu_p95_improvement,
                "maximumTailRegression": args.maximum_tail_regression,
            },
            "checks": checks,
            "openGl": baseline,
            "metal": candidate,
            "comparisons": comparisons,
            "inputs": {
                "openGl": {"path": str(open_gl_path),
                            "sha256": sha256(open_gl_path)},
                "metal": {"path": str(metal_path),
                          "sha256": sha256(metal_path)},
            },
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_name(output.name + ".tmp")
        temporary.write_text(
            json.dumps(result, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8")
        os.replace(temporary, output)
        print(f"Stage 9 performance gate {result['status']}: {output}")
        return 0 if result["status"] == "PASS" else 1
    except GateError as error:
        print(f"Stage 9 performance gate error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
