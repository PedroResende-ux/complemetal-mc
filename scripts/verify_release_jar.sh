#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar_path="${1:-}"

if [[ -z "$jar_path" ]]; then
  mod_version="$(sed -n 's/^mod_version=//p' "$project_dir/gradle.properties" |
    head -n 1)"
  archives_base_name="$(
    sed -n 's/^archives_base_name=//p' "$project_dir/gradle.properties" |
      head -n 1
  )"
  if [[ -z "$mod_version" || -z "$archives_base_name" ]]; then
    echo "Could not resolve the release JAR name from gradle.properties" >&2
    exit 1
  fi
  jar_path="$project_dir/build/libs/$archives_base_name-$mod_version.jar"
fi

if [[ -z "$jar_path" || ! -f "$jar_path" ]]; then
  echo "Release JAR was not found" >&2
  exit 1
fi
jar_path="$(cd "$(dirname "$jar_path")" && pwd)/$(basename "$jar_path")"

entries="$(jar tf "$jar_path")"
required_entries=(
  "META-INF/MANIFEST.MF"
  "META-INF/neoforge.mods.toml"
  "metalrender.mixins.json"
  "metalrender.iris.mixins.json"
  "libmetalrender.dylib"
  "shaders.metallib"
  "assets/complemetal/icon.png"
  "LICENSE"
  "NOTICE"
)

for required in "${required_entries[@]}"; do
  if ! grep -Fxq "$required" <<<"$entries"; then
    echo "Missing required JAR entry: $required" >&2
    exit 1
  fi
done

if grep -Fxq "libmetalrender_debug_v2.dylib" <<<"$entries"; then
  echo "Release JAR contains the obsolete duplicate debug dylib" >&2
  exit 1
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/complemetal-jar.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
(cd "$tmp_dir" && jar xf "$jar_path" \
  libmetalrender.dylib shaders.metallib \
  META-INF/neoforge.mods.toml metalrender.mixins.json metalrender.iris.mixins.json \
  assets/complemetal/icon.png LICENSE NOTICE META-INF/MANIFEST.MF)

if ! file "$tmp_dir/libmetalrender.dylib" | grep -q 'Mach-O 64-bit.*arm64'; then
  echo "Packaged native library is not macOS arm64" >&2
  exit 1
fi

if otool -L "$tmp_dir/libmetalrender.dylib" |
   grep -Eq 'libclang_rt\.(asan|tsan|ubsan|msan)'; then
  echo "Release JAR contains a sanitizer-instrumented native library" >&2
  exit 1
fi

shader_size="$(wc -c < "$tmp_dir/shaders.metallib" | tr -d '[:space:]')"
shader_magic="$(LC_ALL=C od -An -tx1 -N4 "$tmp_dir/shaders.metallib" |
  tr -d '[:space:]')"
if [[ "$shader_size" -lt 4096 || "$shader_magic" != "4d544c42" ]]; then
  echo "Packaged shaders.metallib is empty or is not a compiled Metal library" >&2
  exit 1
fi

mod_version="$(sed -n 's/^mod_version=//p' "$project_dir/gradle.properties" |
  head -n 1)"
if [[ -z "$mod_version" ]]; then
  echo "Could not resolve mod_version from gradle.properties" >&2
  exit 1
fi

if ! grep -Eq '^modId="complemetal"$' "$tmp_dir/META-INF/neoforge.mods.toml" ||
   ! grep -Eq '^displayName="Complemetal"$' "$tmp_dir/META-INF/neoforge.mods.toml" ||
   ! grep -Eq '^version="[^"]*"$' "$tmp_dir/META-INF/neoforge.mods.toml" ||
   ! grep -Fq 'versionRange="[1.21.1]"' "$tmp_dir/META-INF/neoforge.mods.toml" ||
   ! grep -Fq 'modId="neoforge"' "$tmp_dir/META-INF/neoforge.mods.toml"; then
  echo "NeoForge metadata does not contain the expected Complemetal 1.21.1 identity" >&2
  exit 1
fi

icon_size="$(wc -c < "$tmp_dir/assets/complemetal/icon.png" | tr -d '[:space:]')"
icon_width="$(sips -g pixelWidth "$tmp_dir/assets/complemetal/icon.png" 2>/dev/null |
  sed -n 's/.*pixelWidth: //p')"
icon_height="$(sips -g pixelHeight "$tmp_dir/assets/complemetal/icon.png" 2>/dev/null |
  sed -n 's/.*pixelHeight: //p')"
if [[ "$icon_size" -gt 262144 || "$icon_width" != "256" ||
      "$icon_height" != "256" ]]; then
  echo "Complemetal icon must be a 256x256 image no larger than 256 KiB" >&2
  exit 1
fi

if ! grep -Fq 'https://github.com/webblepebbles/MetalRender' \
  "$tmp_dir/NOTICE"; then
  echo "NOTICE does not retain upstream MetalRender attribution" >&2
  exit 1
fi

if grep -Fq '${version}' "$tmp_dir/META-INF/neoforge.mods.toml"; then
  echo "NeoForge metadata still contains an unexpanded version placeholder" >&2
  exit 1
fi

neoforge_version_in_jar="$(sed -n 's/^version="\([^"]*\)"/\1/p'   "$tmp_dir/META-INF/neoforge.mods.toml" | head -n 1)"
manifest_version="$(tr -d '\r' < "$tmp_dir/META-INF/MANIFEST.MF" |
  sed -n 's/^Implementation-Version: //p' | head -n 1)"
manifest_title="$(tr -d '\r' < "$tmp_dir/META-INF/MANIFEST.MF" |
  sed -n 's/^Implementation-Title: //p' | head -n 1)"
if [[ -z "$neoforge_version_in_jar" ||
      "$manifest_version" != "$neoforge_version_in_jar" ||
      "$manifest_title" != "Complemetal" ]]; then
  echo "JAR manifest and NeoForge metadata versions do not match" >&2
  exit 1
fi
if [[ "$neoforge_version_in_jar" != "$mod_version" ]]; then
  echo "Packaged NeoForge version does not match gradle.properties" >&2
  exit 1
fi

if ! grep -Fq '"compatibilityLevel": "JAVA_21"' \
  "$tmp_dir/metalrender.mixins.json"; then
  echo "Mixin configuration does not target Java 21" >&2
  exit 1
fi

if ! grep -Fq '"compatibilityLevel": "JAVA_21"' \
  "$tmp_dir/metalrender.iris.mixins.json"; then
  echo "Optional Iris mixin configuration does not target Java 21" >&2
  exit 1
fi

if ! grep -Fq '"required": false' \
  "$tmp_dir/metalrender.iris.mixins.json"; then
  echo "Iris mixin configuration must remain optional" >&2
  exit 1
fi

for tool in javac nm grep sips; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required release verification tool is unavailable: $tool" >&2
    exit 1
  fi
done

jni_dir="$tmp_dir/jni"
mkdir -p "$jni_dir"
javac -h "$jni_dir" -d "$jni_dir" \
  "$project_dir/src/client/java/com/pebbles_boon/metalrender/nativebridge/NativeBridge.java"
jni_header="$jni_dir/com_pebbles_boon_metalrender_nativebridge_NativeBridge.h"
jni_expected="$jni_dir/expected.txt"
jni_actual="$jni_dir/actual.txt"
jni_missing="$jni_dir/missing.txt"
jni_orphaned="$jni_dir/orphaned.txt"

grep -oE \
  'Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_[A-Za-z0-9_]+' \
  "$jni_header" | sort -u > "$jni_expected"
nm -gU "$tmp_dir/libmetalrender.dylib" |
  grep -oE \
    'Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_[A-Za-z0-9_]+' |
  sort -u > "$jni_actual"
comm -23 "$jni_expected" "$jni_actual" > "$jni_missing"
comm -13 "$jni_expected" "$jni_actual" > "$jni_orphaned"

required_release_qa_exports=(
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetGpuCommandBufferErrorCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetInFlightFrameTimeoutCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetNoIOSurfaceSlotSkipCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nValidateIrisMslLibrary"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nIsIrisMslCompilerReady"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileAttemptCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileSuccessCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileUnsupportedCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslCompileFailureCount"
  "Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_nGetIrisMslLiveLibraryCount"
)
for required in "${required_release_qa_exports[@]}"; do
  if ! grep -Fxq "$required" "$jni_expected" ||
     ! grep -Fxq "$required" "$jni_actual"; then
    echo "Packaged JAR is missing required release-QA JNI telemetry: $required" >&2
    exit 1
  fi
done

if [[ -s "$jni_missing" || -s "$jni_orphaned" ]]; then
  if [[ -s "$jni_missing" ]]; then
    echo "Packaged dylib is missing JNI exports:" >&2
    sed 's/^/  - /' "$jni_missing" >&2
  fi
  if [[ -s "$jni_orphaned" ]]; then
    echo "Packaged dylib has orphaned JNI exports:" >&2
    sed 's/^/  - /' "$jni_orphaned" >&2
  fi
  exit 1
fi

if command -v vtool >/dev/null 2>&1 &&
   ! vtool -show-build "$tmp_dir/libmetalrender.dylib" |
     grep -Eq 'minos[[:space:]]+14\.0'; then
  echo "Packaged native library does not declare macOS 14.0 compatibility" >&2
  exit 1
fi

if command -v codesign >/dev/null 2>&1; then
  codesign --verify "$tmp_dir/libmetalrender.dylib"
fi

echo "Release JAR verified: $jar_path"
shasum -a 256 "$jar_path"
shasum -a 256 "$tmp_dir/shaders.metallib"
