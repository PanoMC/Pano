#!/usr/bin/env bash
# Boots a real Pano release from the full image with docker/compose.yaml (the file the docs publish), runs
# scripts/smoke-install.sh against it, and checks that /data and the database survive a down/up.
#   cp build/libs/Pano-<version>.jar* docker/pano/release/ && docker/pano/compose-test.sh
# Builds linux/amd64 runtime-jre11 + the full image locally as $PH_PREFIX-pano:{runtime-jre11,test}
# (PH_PREFIX default ph-compose), host port $PANO_PORT (default 18088), Pano capped at 1g / 2 cpus and MariaDB
# at 1536m. Everything it creates (containers, volumes, network, images) is removed on exit.
# Needs docker with compose v2, curl and jq.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
prefix=${PH_PREFIX:-ph-compose}
port=${PANO_PORT:-18088}
runtime="$prefix-pano:runtime-jre11"
image="$prefix-pano:test"
work=$(mktemp -d)
password=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')

say() { echo "compose-test: $*" >&2; }
die() { say "FAIL $*"; exit 1; }

ls "$here"/release/Pano-*.jar >/dev/null 2>&1 || die "put a release jar (+ .sha256) into docker/pano/release first"

cat > "$work/override.yaml" <<YAML
services:
  pano:
    image: $image
    mem_limit: 1g
    memswap_limit: 1g
    cpus: 2
  db:
    mem_limit: 1536m
    memswap_limit: 1536m
    cpus: 2
YAML

compose() {
  PANO_DB_PASSWORD=$password PANO_PORT=$port \
    docker compose -p "$prefix" -f "$root/docker/compose.yaml" -f "$work/override.yaml" "$@"
}

cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  docker rmi -f "$image" "$runtime" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

say "building $runtime (linux/amd64)"
docker build --platform linux/amd64 --build-arg JRE=11 -t "$runtime" "$here/../runtime" >"$work/build.log" 2>&1 \
  || { cat "$work/build.log"; die "runtime build"; }
say "building $image"
docker build --platform linux/amd64 --build-arg "RUNTIME=$runtime" -t "$image" "$here" >"$work/build.log" 2>&1 \
  || { cat "$work/build.log"; die "image build"; }

say "compose up on port $port"
compose up -d --wait db >/dev/null
compose up -d pano >/dev/null

if ! PANO_URL="http://127.0.0.1:$port" PANO_DB_HOST=db PANO_DB_PORT=3306 PANO_DB_NAME=pano PANO_DB_USER=pano \
  PANO_DB_PASSWORD=$password "$root/scripts/smoke-install.sh"; then
  compose logs --tail 80 pano >&2 || true
  die "smoke install"
fi

jar_before=$(compose exec -T pano cat /data/.pano-jar)
[ -n "$jar_before" ] || die "/data/.pano-jar is empty"

say "down (volumes kept) and up again"
compose down >/dev/null
compose up -d --wait db >/dev/null
compose up -d pano >/dev/null

installed() { [ "$(curl -sS "http://127.0.0.1:$port/api/setup/step" 2>/dev/null | jq -r '.error // empty' 2>/dev/null)" = PLATFORM_ALREADY_INSTALLED ]; }
for ((i = 0; i < ${SMOKE_TIMEOUT:-300}; i += 2)); do installed && break; sleep 2; done
installed || { compose logs --tail 80 pano >&2 || true; die "Pano did not come back installed after down/up"; }
[ "$(compose exec -T pano cat /data/.pano-jar)" = "$jar_before" ] || die "/data/.pano-jar changed across down/up"
! compose logs pano 2>&1 | grep -q 'installed the bundled' || die "the restarted container seeded the release again"

say "ok: $jar_before installed, serving on :$port and persisted across down/up"
