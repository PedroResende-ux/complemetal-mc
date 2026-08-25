#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mod_version="$(sed -n 's/^mod_version=//p' "$project_dir/gradle.properties" | head -n 1)"
base_name="$(sed -n 's/^archives_base_name=//p' "$project_dir/gradle.properties" | head -n 1)"

if [[ -z "$mod_version" || -z "$base_name" ]]; then
  echo "Could not resolve release identity from gradle.properties" >&2
  exit 1
fi

jar="$project_dir/build/libs/$base_name-$mod_version.jar"
sources="$project_dir/build/libs/$base_name-$mod_version-sources.jar"
icon="$project_dir/src/main/resources/assets/complemetal/icon.png"
release_root="$project_dir/build/release"
destination="$release_root/$base_name-$mod_version"
field_root="$project_dir/build/field-qa/release-stability-final"
field_enabled="$field_root/enabled/run-result.json"
field_baseline="$field_root/baseline/run-result.json"
field_comparison="$field_root/comparison.json"

for required in "$jar" "$sources" "$icon" \
  "$field_enabled" "$field_baseline" "$field_comparison" \
  "$project_dir/docs/RELEASE_CHECKLIST_0.4.1.md"; do
  if [[ ! -f "$required" ]]; then
    echo "Required release artifact is missing: $required" >&2
    exit 1
  fi
done

if [[ -e "$destination" ]]; then
  echo "Release bundle already exists; inspect or move it first: $destination" >&2
  exit 1
fi

"$project_dir/scripts/verify_release_jar.sh" "$jar"

mkdir -p "$release_root"
staging="$(mktemp -d "$release_root/.complemetal-release.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

cp "$jar" "$staging/"
cp "$sources" "$staging/"
cp "$icon" "$staging/complemetal-icon-256.png"
cp "$project_dir/LICENSE" "$staging/"
cp "$project_dir/NOTICE" "$staging/"
cp "$project_dir/release/GITHUB_RELEASE_NOTES_0.4.1.md" \
  "$staging/RELEASE_NOTES.md"
cp "$project_dir/release/MODRINTH_DESCRIPTION.md" "$staging/"
cp "$project_dir/docs/RELEASE_CHECKLIST_0.4.1.md" "$staging/"
mkdir -p "$staging/qa-evidence"
cp "$field_enabled" "$staging/qa-evidence/field-enabled.json"
cp "$field_baseline" "$staging/qa-evidence/field-baseline.json"
cp "$field_comparison" "$staging/qa-evidence/field-comparison.json"

evidence_archive="$base_name-$mod_version-qa-evidence.zip"
(
  cd "$staging"
  zip -X -q "$evidence_archive" qa-evidence/*.json
)

(
  cd "$staging"
  shasum -a 256 \
    "$base_name-$mod_version.jar" \
    "$base_name-$mod_version-sources.jar" \
    "complemetal-icon-256.png" \
    "$evidence_archive" > SHA256SUMS
)

mv "$staging" "$destination"
trap - EXIT
echo "Release bundle prepared: $destination"
