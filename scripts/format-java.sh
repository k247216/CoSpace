#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=1.24.0
jar="runtime/tools/google-java-format-$version-all-deps.jar"
mkdir -p runtime/tools
if [[ ! -f "$jar" ]]; then
    curl --fail --location --retry 2 "https://github.com/google/google-java-format/releases/download/v$version/google-java-format-$version-all-deps.jar" -o "$jar"
fi
mode=(--dry-run --set-exit-if-changed)
if [[ "${1:-}" == --write ]]; then mode=(--replace); fi
find backend -type f -name '*.java' -not -path '*/target/*' -print0 | xargs -0 java -jar "$jar" "${mode[@]}"
