#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")" && pwd)"
cd "$project_root"

metal_tool_usable() {
  local metal_path
  metal_path="$(xcrun --sdk macosx --find metal 2>/dev/null)" || return 1
  [[ -x "$metal_path" ]] || return 1
  "$metal_path" --version >/dev/null 2>&1
}

# Do not mutate the machine-wide xcode-select setting. When Command Line Tools
# are selected, prefer the standard full-Xcode installation for this process.
if [[ -z "${DEVELOPER_DIR:-}" ]] &&
   ! metal_tool_usable &&
   [[ -d "/Applications/Xcode.app/Contents/Developer" ]]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
  echo "Using full Xcode from $DEVELOPER_DIR"
fi

deployment_target="${MACOSX_DEPLOYMENT_TARGET:-14.0}"
shader_dir="$project_root/src/main/resources/native/shaders"
shader_build_dir="$project_root/build/native/shaders"
native_build_dir="$project_root/build/native"
resource_dir="$project_root/src/main/resources"
java_root="${JAVA_HOME:-$(/usr/libexec/java_home)}"
sdk_root="$(xcrun --sdk macosx --show-sdk-path)"

mkdir -p "$shader_build_dir" "$native_build_dir"

if [[ "${BUILD_SHADERS:-1}" != "0" ]]; then
  metallib_path="$(xcrun --sdk macosx --find metallib 2>/dev/null || true)"
  if ! metal_tool_usable || [[ -z "$metallib_path" ]] ||
     [[ ! -x "$metallib_path" ]]; then
    echo "Metal shader tools are unavailable in the selected Xcode." >&2
    echo "Install the Metal Toolchain component with:" >&2
    echo "  DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -downloadComponent MetalToolchain" >&2
    echo "For a development-only native build, use BUILD_SHADERS=0." >&2
    exit 1
  fi

  shader_sources=(
    metalrender.metal
    entity.metal
    culling.metal
    hiz.metal
    occlusion_culling.metal
    visibility_buffer.metal
    oit_transparency.metal
    cull_and_encode.metal
    mesh_terrain.metal
  )
  air_files=()
  for shader_source in "${shader_sources[@]}"; do
    if [[ ! -f "$shader_dir/$shader_source" ]]; then
      echo "Missing Metal shader source: $shader_dir/$shader_source" >&2
      exit 1
    fi
    air_file="$shader_build_dir/${shader_source%.metal}.air"
    xcrun -sdk macosx metal \
      -std=metal3.0 \
      -mmacosx-version-min="$deployment_target" \
      -c "$shader_dir/$shader_source" \
      -o "$air_file"
    air_files+=("$air_file")
  done
  shader_output="$shader_build_dir/shaders.metallib"
  xcrun -sdk macosx metallib "${air_files[@]}" \
    -o "$shader_output"
  shader_magic="$(LC_ALL=C od -An -tx1 -N4 "$shader_output" |
    tr -d '[:space:]')"
  if [[ ! -s "$shader_output" || "$shader_magic" != "4d544c42" ]]; then
    echo "Metal compiler produced an invalid shader library: $shader_output" >&2
    exit 1
  fi
  install -m 0644 "$shader_output" "$resource_dir/shaders.metallib"
  echo "Shaders compiled: $resource_dir/shaders.metallib"
fi

if [[ "${BUILD_NATIVE:-1}" != "0" ]]; then
  native_output="$native_build_dir/libmetalrender.dylib"
  sanitizer_mode="${METALRENDER_NATIVE_SANITIZER:-}"
  native_compile_flags=(-O3 -DNDEBUG)
  if [[ "${METALRENDER_NATIVE_DEBUG:-0}" == "1" ]]; then
    native_compile_flags=(-O1 -g -DMETALRENDER_DEBUG=1)
    echo "Building native library with debug diagnostics"
  fi
  if [[ -n "$sanitizer_mode" ]]; then
    if [[ "$sanitizer_mode" != "address" ]]; then
      echo "Unsupported native sanitizer: $sanitizer_mode" >&2
      exit 1
    fi
    native_compile_flags=(
      -O1
      -g
      -fno-omit-frame-pointer
      -fsanitize=address
    )
    echo "Building native library with AddressSanitizer"
  fi

  xcrun --sdk macosx clang++ -arch arm64 "${native_compile_flags[@]}" \
    -std=c++17 -dynamiclib -fblocks \
    -mmacosx-version-min="$deployment_target" \
    -Wl,-install_name,@rpath/libmetalrender.dylib \
    -isysroot "$sdk_root" \
    -DMETALRENDER_HAS_METALFX=1 \
    -framework Metal \
    -framework MetalFX \
    -framework Foundation \
    -framework Cocoa \
    -framework IOKit \
    -framework IOSurface \
    -framework OpenGL \
    -framework QuartzCore \
    -I"$java_root/include" \
    -I"$java_root/include/darwin" \
    -I"$project_root/src/main/resources/native" \
    "$project_root/src/main/resources/native/metalrender.mm" \
    "$project_root/src/main/resources/native/meshshader.mm" \
    -o "$native_output"

  if command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "$native_output"
  fi
  cp "$native_output" "$resource_dir/libmetalrender.dylib"
  echo "Native library compiled (minimum macOS $deployment_target): $native_output"
fi
