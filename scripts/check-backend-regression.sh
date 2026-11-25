#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
: "${RPAN_TEST_MYSQL_USER:?Set the local MySQL test user}"
: "${RPAN_TEST_MYSQL_PASSWORD?Set the local MySQL test password, or an empty value}"
: "${RPAN_TEST_MQ_NAMESERVER:?Set the isolated RocketMQ NameServer address}"

if [[ ! "$RPAN_TEST_MQ_NAMESERVER" =~ ^(127\.0\.0\.1|localhost):[0-9]+$ ]]; then
    echo 'The regression suite requires a local RocketMQ NameServer.' >&2
    exit 2
fi

mysql_port=${RPAN_TEST_MYSQL_PORT:-3306}
database="r_pan_cleanup_test_$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
mysql_args=(--no-defaults --protocol=TCP -h 127.0.0.1 -P "$mysql_port" -u "$RPAN_TEST_MYSQL_USER")
mysql_test() {
    MYSQL_PWD="$RPAN_TEST_MYSQL_PASSWORD" mysql "${mysql_args[@]}" "$@"
}
mysql_test -e "CREATE DATABASE \`$database\` CHARACTER SET utf8mb4"
filesystem=
cleanup() {
    if [[ -n "$filesystem" ]]; then
        rm -rf -- "$filesystem"
    fi
    mysql_test -e "DROP DATABASE \`$database\`"
}
trap cleanup EXIT
mkdir -p runtime
filesystem=$(mktemp -d "$PWD/runtime/.cospace-regression.XXXXXXXX")

mvn "$@" -f backend/pom.xml -pl r-pan-api-services -am verify \
    '-Dtest=Cleanup*Test,FileLifecycle*Test,FileOwnershipBoundary*Test,LocalCleanupStorageTest,SecUploadSecurityTest,Upload*Test,UserSpaceQuota*Test,Storage*Test,Workspace*Test' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "-Dcleanup.mysql.url=jdbc:mysql://127.0.0.1:$mysql_port/$database?connectionTimeZone=%2B00:00&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false" \
    -Dworkspace.mysql.test=true \
    -Dupload.redis.integration=true -Dupload.redis.test=true \
    -Dcleanup.mq.integration=true "-Dcleanup.mq.test.nameserver=$RPAN_TEST_MQ_NAMESERVER" \
    "-Dupload.fs.root=$filesystem/upload-fs-check"
