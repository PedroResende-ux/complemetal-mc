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

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/metalrender-smoke.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/classes" "$tmp_dir/empty-native-path" "$tmp_dir/home"

javac --release 21 \
  -cp "$jar_path" \
  -d "$tmp_dir/classes" \
  "$project_dir/scripts/ReleasePayloadSmoke.java"

java \
  --enable-native-access=ALL-UNNAMED \
  -Djava.library.path="$tmp_dir/empty-native-path" \
  -cp "$tmp_dir/classes:$jar_path" \
  ReleasePayloadSmoke "$tmp_dir/home"
