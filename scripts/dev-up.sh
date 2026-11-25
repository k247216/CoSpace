#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -ntp -f backend/pom.xml -pl r-pan-api-services -am -DskipTests package
npm --prefix frontend ci
build_log=$(mktemp)
trap 'rm -f "$build_log"' EXIT
bash scripts/package-azure-release.sh > "$build_log"
bundle=$(tail -1 "$build_log")
release=$(dirname "$bundle")
if [[ ! -f runtime/dev.env ]]; then
    python3 scripts/init-env.py --output runtime/dev.env --port "${COSPACE_DEV_PORT:-18081}"
fi
cp runtime/dev.env "$release/.env"
chmod 600 "$release/.env"
docker compose -p cospace-dev --env-file "$release/.env" -f "$release/compose.yaml" up -d
ln -sfn "$(basename "$release")" runtime/dev-current
printf 'Local CoSpace: http://127.0.0.1:%s\n' "${COSPACE_DEV_PORT:-18081}"
printf 'Logs: docker compose -p cospace-dev --env-file %q/.env -f %q/compose.yaml logs -f app\n' "$release" "$release"
