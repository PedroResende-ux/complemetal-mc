#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../../.." && pwd)"
BUILD_SHADERS=0 "$project_root/compile_native.sh"
