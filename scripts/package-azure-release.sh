#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

jar=backend/r-pan-api-services/target/r-pan-api-services.jar
env_example=deploy/azure/.env.example
if [[ ! -f "$jar" ]]; then
    echo 'Build the backend JAR before packaging.' >&2
    exit 2
fi
if grep -Eq '^(OSS_ACCESS_KEY_ID|OSS_ACCESS_KEY_SECRET|OSS_SESSION_TOKEN|COSPACE_MYSQL_PASSWORD|COSPACE_MYSQL_ROOT_PASSWORD|COSPACE_REDIS_PASSWORD|COSPACE_JWT_SECRET)=.+' "$env_example"; then
    echo 'Clear credential values from the Azure environment template before packaging.' >&2
    exit 2
fi
(cd frontend && VITE_API_BASE_URL=/api npm run build)
mkdir -p runtime
release=$(mktemp -d "$PWD/runtime/cospace-azure.XXXXXXXX")
bundle="$release/cospace-azure.tar.gz"
cp "$jar" "$release/cospace.jar"
cp -R frontend/dist "$release/portal"
cp deploy/azure/{compose.yaml,broker.conf,nginx.conf} "$release/"
cp "$env_example" "$release/env.example"
mkdir "$release/migrations"
cp backend/r-pan-api-services/src/main/resources/sql/migrations/2026112{4_workspace_governance,5_workspace_files,5_workspace_sharing,5_workspace_collections,5_collection_inbox,5_collection_reporting}.sql "$release/migrations/"
cp backend/r-pan-api-services/src/main/resources/sql/db.sql "$release/schema.sql"
cp backend/r-pan-api-services/src/main/resources/sql/migrations/20261123_oss_direct_upload.sql "$release/"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$bundle" -C "$release" \
    cospace.jar portal compose.yaml broker.conf nginx.conf env.example schema.sql 20261123_oss_direct_upload.sql migrations
(
    cd "$release"
    shasum -a 256 cospace-azure.tar.gz > cospace-azure.tar.gz.sha256
)
printf '%s\n' "$bundle"
