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
metal4_cold="$project_dir/build/exact-jar-qa-0.4.0-metal4/evidence/run-result-cold.json"
metal4_warm="$project_dir/build/exact-jar-qa-0.4.0-metal4/evidence/run-result-warm.json"
metal3_cold="$project_dir/build/exact-jar-qa-0.4.0-metal3/evidence/run-result-cold.json"
metal3_warm="$project_dir/build/exact-jar-qa-0.4.0-metal3/evidence/run-result-warm.json"

for required in "$jar" "$sources" "$icon" \
  "$metal4_cold" "$metal4_warm" "$metal3_cold" "$metal3_warm" \
  "$project_dir/docs/RELEASE_CHECKLIST_0.4.0.md"; do
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
cp "$project_dir/release/GITHUB_RELEASE_NOTES_0.4.0.md" \
  "$staging/RELEASE_NOTES.md"
cp "$project_dir/release/MODRINTH_DESCRIPTION.md" "$staging/"
cp "$project_dir/docs/RELEASE_CHECKLIST_0.4.0.md" "$staging/"
mkdir -p "$staging/qa-evidence"
cp "$metal4_cold" "$staging/qa-evidence/metal4-cold.json"
cp "$metal4_warm" "$staging/qa-evidence/metal4-warm.json"
cp "$metal3_cold" "$staging/qa-evidence/metal3-cold.json"
cp "$metal3_warm" "$staging/qa-evidence/metal3-warm.json"

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
