#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" ]]; then
  java_home="$(/usr/libexec/java_home -v 25)"
fi
if [[ ! -x "$java_home/bin/java" ]]; then
  echo "Java 25 launcher was not found under JAVA_HOME: $java_home" >&2
  exit 1
fi

developer_dir="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
if [[ ! -d "$developer_dir" ]]; then
  echo "Full Xcode developer directory was not found: $developer_dir" >&2
  exit 1
fi
export DEVELOPER_DIR="$developer_dir"

asan_runtime="$(
  xcrun --sdk macosx clang++ \
    -print-file-name=libclang_rt.asan_osx_dynamic.dylib
)"
if [[ ! -f "$asan_runtime" ]]; then
  echo "Xcode AddressSanitizer runtime was not found: $asan_runtime" >&2
  exit 1
fi
temporary_jdk_root="$(
  mktemp -d "${TMPDIR:-/tmp}/metalrender-asan-jdk.XXXXXX"
)"
restore_release_payload=0

cleanup() {
  local status=$?
  trap - EXIT

  if [[ "$restore_release_payload" -eq 1 ]]; then
    if ! env \
        JAVA_HOME="$java_home" \
        DEVELOPER_DIR="$developer_dir" \
        METALRENDER_NATIVE_SANITIZER= \
        ./gradlew --no-daemon \
          buildReleaseNativePayload processResources; then
      echo "Failed to restore the optimized release native payload" >&2
      status=1
    fi
  fi

  case "$temporary_jdk_root" in
    */metalrender-asan-jdk.*)
      if [[ -d "$temporary_jdk_root" ]]; then
        if ! find "$temporary_jdk_root" -depth -delete; then
          status=1
        fi
      fi
      ;;
    *)
      echo "Refusing to clean unexpected temporary path: $temporary_jdk_root" >&2
      status=1
      ;;
  esac

  exit "$status"
}
trap cleanup EXIT

# APFS clone-copy keeps the temporary JDK cheap while allowing only the Java
# launcher signature to be replaced. The stock Temurin launcher uses Hardened
# Runtime and macOS otherwise rejects loading the sanitizer runtime.
cp -cR "$java_home" "$temporary_jdk_root/Home"
codesign --force --sign - "$temporary_jdk_root/Home/bin/java"

# Start from a verified release JAR. The temporary ASan JAR below is never
# written to build/libs, so a failed sanitizer run cannot replace the
# publishable artifact.
env \
  JAVA_HOME="$java_home" \
  DEVELOPER_DIR="$developer_dir" \
  METALRENDER_NATIVE_SANITIZER= \
  ./gradlew --no-daemon releaseCheck

restore_release_payload=1
env \
  JAVA_HOME="$java_home" \
  DEVELOPER_DIR="$developer_dir" \
  BUILD_SHADERS=0 \
  METALRENDER_NATIVE_SANITIZER=address \
  ./compile_native.sh

mod_version="$(
  sed -n 's/^mod_version=//p' gradle.properties | head -n 1
)"
archives_base_name="$(
  sed -n 's/^archives_base_name=//p' gradle.properties | head -n 1
)"
release_jar="$project_dir/build/libs/$archives_base_name-$mod_version.jar"
if [[ ! -f "$release_jar" ]]; then
  echo "Verified release JAR was not produced: $release_jar" >&2
  exit 1
fi

asan_jar="$temporary_jdk_root/metalrender-asan-smoke.jar"
classes_dir="$temporary_jdk_root/classes"
isolated_home="$temporary_jdk_root/home"
empty_native_path="$temporary_jdk_root/empty-native-path"
cp "$release_jar" "$asan_jar"
jar uf "$asan_jar" -C src/main/resources libmetalrender.dylib
mkdir -p "$classes_dir" "$isolated_home" "$empty_native_path"

"$java_home/bin/javac" --release 25 \
  -cp "$asan_jar" \
  -d "$classes_dir" \
  scripts/ReleasePayloadSmoke.java

env \
  DYLD_INSERT_LIBRARIES="$asan_runtime" \
  ASAN_OPTIONS="${ASAN_OPTIONS:-detect_leaks=0:halt_on_error=1:abort_on_error=1:strict_string_checks=1}" \
  "$temporary_jdk_root/Home/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    "-Djava.library.path=$empty_native_path" \
    -cp "$classes_dir:$asan_jar" \
    ReleasePayloadSmoke "$isolated_home"
