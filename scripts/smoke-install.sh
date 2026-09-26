#!/usr/bin/env bash
# Installs a freshly booted Pano through the setup API and checks it serves afterwards.
#   PANO_URL=http://127.0.0.1:8088 PANO_DB_HOST=127.0.0.1 PANO_DB_PORT=3306 PANO_DB_NAME=pano \
#   PANO_DB_USER=pano PANO_DB_PASSWORD=... scripts/smoke-install.sh
# The database is the one Pano was configured with through env; the script only proves that the setup
# wizard, the DB driver, the migrations, password hashing (argon2 natives) and the UIs work on this JRE.
# The admin is smokeadmin / SMOKE_ADMIN_PASSWORD (default Sm0ke-Test-Passw0rd). Needs curl and jq. Waits up to SMOKE_TIMEOUT seconds (default 300) for each phase.
set -euo pipefail

url=${PANO_URL:-http://127.0.0.1:8088}
timeout=${SMOKE_TIMEOUT:-300}
db_host="${PANO_DB_HOST:-127.0.0.1}:${PANO_DB_PORT:-3306}"

say() { echo "smoke: $*" >&2; }
die() { say "FAIL $*"; exit 1; }

wait_until() { # $1 description, then a command
  local what=$1 i; shift
  for ((i = 0; i < timeout; i += 2)); do "$@" && return 0; sleep 2; done
  die "timed out after ${timeout}s waiting for $what"
}

step_json() { curl -fsS "$url/api/setup/step"; }
put_step() { curl -fsS -X PUT -H 'Content-Type: application/json' --data "$1" "$url/api/setup/step"; }
current_step() { step_json | jq -r '.step'; }

wait_until "the setup API" step_json >/dev/null 2>&1
say "setup API is up: $(step_json | jq -c '{step, version, databaseManaged}')"
[ "$(current_step)" = 0 ] || die "expected a fresh install at step 0, got step $(current_step)"

put_step '{"clientStep":0,"locale":"en-US","usageMode":"BOTH"}' >/dev/null
[ "$(current_step)" = 1 ] || die "step 0 did not advance"

put_step "{\"clientStep\":1,\"websiteName\":\"Smoke\",\"websiteDescription\":\"CI smoke test\",\"websiteUrl\":\"$url\"}" >/dev/null
step=$(current_step)
if [ "$step" = 2 ]; then
  body=$(jq -nc --arg host "$db_host" --arg db "${PANO_DB_NAME:-pano}" --arg user "${PANO_DB_USER:-pano}" \
    --arg pass "${PANO_DB_PASSWORD:-}" '{host: $host, dbName: $db, username: $user, password: $pass}')
  [ "$(curl -fsS -X POST -H 'Content-Type: application/json' --data "$body" "$url/api/setup/steps/2/verify" | jq -r .result)" = ok ] \
    || die "the database from env did not verify"
  put_step "$(jq -c '. + {clientStep: 2, dbType: "mariadb"}' <<<"$body")" >/dev/null
  step=$(current_step)
fi
[ "$step" = 3 ] || die "expected step 3 after the database step, got $step"

put_step '{"clientStep":3,"hostname":"smtp.invalid","port":587,"ssl":false,"starttls":"REQUIRED","username":"smoke","password":"smoke","sender":"smoke@example.com","authMethods":""}' >/dev/null
[ "$(current_step)" = 4 ] || die "step 3 did not advance"

say "finishing the install"
finish=$(curl -sS -X POST -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg pass "${SMOKE_ADMIN_PASSWORD:-Sm0ke-Test-Passw0rd}" \
    '{username: "smokeadmin", email: "smoke@example.com", password: $pass, setupLocale: "en-US", telemetryEnabled: false}')" \
  "$url/api/setup/finish")
[ "$(jq -r .result <<<"$finish")" = ok ] || die "finish failed: $(jq -c 'del(.jwt, .token, .csrfToken)' <<<"$finish" 2>/dev/null || echo "$finish" | head -c 300)"

installed() { [ "$(curl -sS "$url/api/setup/step" | jq -r '.error // empty')" = PLATFORM_ALREADY_INSTALLED ]; }
wait_until "the setup API to report the install" installed

served() { local code; code=$(curl -s -o /dev/null -w '%{http_code}' "$url/"); [ "$code" = 200 ]; }
wait_until "the theme to serve /" served
# the panel's pages depend on the session and usage mode; its SvelteKit version file only on panel-ui being up
served_panel() { local code; code=$(curl -s -o /dev/null -w '%{http_code}' "$url/panel/_app/version.json"); [ "$code" = 200 ]; }
wait_until "panel-ui behind /panel" served_panel

say "ok: installed and serving on $url"
