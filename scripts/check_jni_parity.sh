#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bridge_source="$project_dir/src/client/java/com/pebbles_boon/metalrender/nativebridge/NativeBridge.java"
native_dir="$project_dir/src/main/resources/native"
scratch_dir="$(mktemp -d "${TMPDIR:-/tmp}/metalrender-jni.XXXXXX")"
trap 'rm -rf "$scratch_dir"' EXIT

javac -h "$scratch_dir" -d "$scratch_dir" "$bridge_source"

header="$scratch_dir/com_pebbles_boon_metalrender_nativebridge_NativeBridge.h"
expected="$scratch_dir/expected.txt"
actual="$scratch_dir/actual.txt"
missing="$scratch_dir/missing.txt"
orphaned="$scratch_dir/orphaned.txt"

rg -o 'Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_[A-Za-z0-9_]+' \
  "$header" | sort -u > "$expected"
rg --no-filename -o 'Java_com_pebbles_1boon_metalrender_nativebridge_NativeBridge_[A-Za-z0-9_]+' \
  "$native_dir"/*.mm | sort -u > "$actual"

comm -23 "$expected" "$actual" > "$missing"
comm -13 "$expected" "$actual" > "$orphaned"

if [[ -s "$missing" || -s "$orphaned" ]]; then
  if [[ -s "$missing" ]]; then
    echo "Missing native JNI implementations:"
    sed 's/^/  - /' "$missing"
  fi
  if [[ -s "$orphaned" ]]; then
    echo "Native JNI exports without Java declarations:"
    sed 's/^/  - /' "$orphaned"
  fi
  exit 1
fi

echo "JNI parity OK ($(wc -l < "$expected" | tr -d ' ') methods)"
