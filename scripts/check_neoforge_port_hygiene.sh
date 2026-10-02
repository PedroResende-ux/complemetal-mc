#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

patterns=(
  '26\.2'
  'mc26\.2'
  'Java[[:space:]]+25'
  'JAVA_25'
  '--release[[:space:]]+25'
  'net\.fabricmc'
  'fabric\.mod\.json'
)

scan_paths=(
  '.github/workflows'
  'build.gradle'
  'compile_native.sh'
  'gradle.properties'
  'settings.gradle'
  'scripts'
  'src/client/java'
  'src/test/java'
  'src/main/resources'
)

violations=0
for pattern in "${patterns[@]}"; do
  matches="$(
    git grep -nI -E "$pattern" -- "${scan_paths[@]}" \
      ':!scripts/check_neoforge_port_hygiene.sh' || true
  )"
  if [[ -n "$matches" ]]; then
    echo "NeoForge 1.21.1 port hygiene violation for /$pattern/:" >&2
    printf '%s\n' "$matches" >&2
    violations=1
  fi
done

if [[ "$violations" -ne 0 ]]; then
  exit 1
fi

echo "NeoForge 1.21.1 port hygiene check passed"
