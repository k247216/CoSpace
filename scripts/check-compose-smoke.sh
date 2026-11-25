#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
release=${1:?Pass the extracted release directory}
release=$(cd "$release" && pwd)
mkdir -p runtime
smoke=$(mktemp -d "$PWD/runtime/.compose-smoke.XXXXXXXX")
project="cospace-smoke-$(basename "$smoke" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
port=${COSPACE_SMOKE_PORT:-18083}
cp "$release/"{compose.yaml,broker.conf,nginx.conf,schema.sql,cospace.jar} "$smoke/"
cp -R "$release/portal" "$smoke/portal"
chmod a+r "$smoke/"{schema.sql,broker.conf,nginx.conf,cospace.jar}
chmod -R a+rX "$smoke/portal"
python3 scripts/init-env.py --output "$smoke/.env" --port "$port"
dc() { docker compose -p "$project" --env-file "$smoke/.env" -f "$smoke/compose.yaml" "$@"; }
finish() {
    result=$?
    trap - EXIT
    if (( result != 0 )); then dc logs --tail=40 mysql app >&2 || true; fi
    dc down -v --remove-orphans
    rm -rf -- "$smoke"
    exit "$result"
}
trap finish EXIT
dc config -q
dc up -d
ready=0
for _ in {1..90}; do
    if curl -fsS -m 2 "http://127.0.0.1:$port/api/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then ready=1; break; fi
    sleep 1
done
if (( ! ready )); then echo 'Fresh application did not become healthy' >&2; exit 1; fi
base="http://127.0.0.1:$port/api"
python3 scripts/benchmark.py prepare --base "$base" --items 3 --fixture "$smoke/fixture.json"
python3 scripts/benchmark.py verify --base "$base" --fixture "$smoke/fixture.json"
python3 scripts/benchmark.py cleanup --base "$base" --fixture "$smoke/fixture.json"
printf 'PASS fresh database, local storage, team upload/download, replay and cleanup\n'
