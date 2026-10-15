#!/usr/bin/env bash
# Boot gate (BOOT-01): one Pano with every migrated plugin on /api/v1, walked route by route, then frozen as the demo bundle.
#
#   scripts/api-v1/boot-gate.sh [--no-build] [--skip-browser] [--keep-instance] [--full]
#
# --upgrade-only runs just step 6b (it builds nothing and freezes nothing; use --no-build is implied by the missing steps).
# --full (BOOT-02) is the complete gate of doc 04 section 9 and adds, around the steps below:
#   0. before the build: `extract-routes --check` over core and every gate plugin, `check-paths` (against a fresh route
#      extraction of this checkout) in every JS repo, `migrate-v1 --check` on this checkout (advisory: the literals it lists
#      are named in the log);
#   4. the route walk also asserts the three kinds of OpenAPI document (operations equal the static extract, `doc` on every
#      public operation, ListShape on the schemas), runs with list shapes NOT waived, and writes the snapshots
#      (Pano/api/openapi-core.json, openapi-internal.json; <umbrella>/.open-frontend-run/snapshots/<pluginId>/openapi.json),
#      then a second read-only pass proves they match what is served;
#   6b. after the gate instance is stopped: the upgrade run. upgrade-fixture/fixture.mjs builds a pre-cutover install (alpha.537
#      jar on JRE 11, comments, market, blaze, a linked server, a node, old /api/ URLs in the database), the local store stub
#      starts, the new jar replaces the old one, gate 0 installs the compatible versions, `route-walk.mjs --upgrade` asserts
#      the same theme and plugins run and the server and node are listed under `agents`, and column-scan.mjs finds no `/api/`
#      without `v1/` in any text column;
#   7. the bundle is frozen only when everything above passed.
#
# Runs only inside a test-instance slot (tools/README.md): /home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh scripts/api-v1/boot-gate.sh [options]
# The instance, its ports, database and the upgrade run (second Pano port, store stub port, second database) all come from the slot.
#
# Steps:
#   1. build (Gradle, locked + capped, `build -x test` and the four :Node exclusions) unless --no-build;
#   2. e2e-instance.sh start --ui external:<theme>,<panel>   (the slot's isolated database, never the dev one);
#   3. stop the JVM by its recorded PID, add every migrated plugin jar, start it again by the same recorded PID file;
#   4. route-walk.mjs against the instance;
#   5. market's `bun run e2e:browser smoke/` (headless Chromium, no jsbuild lock: the slot bounds it);
#   6. e2e-instance.sh stop (always, also on error) -> no Pano process of this run is left;
#   7. only when 4 and 5 passed: freeze the bundle into <umbrella>/.open-frontend-run/bundle/ (+ manifest.json).
#
# Secrets: the MariaDB root password is read from docker-compose.yml into the environment, never printed.
# Exit codes: 0 gate passed and bundle frozen; 1 a check failed (the logs say which route/scenario and its owning unit);
#             2 the gate could not run (no slot, missing jar, port, instance start).
set -uo pipefail

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
PWP=$(cd "$SELF/../.." && pwd -P)
UMB=$(cd "$PWP/.." && pwd -P)
RUN="$UMB/.open-frontend-run"
BUNDLE="$RUN/bundle"
LOGS="$BUNDLE/_gate"
MARKET="$PWP/plugins/pano-plugin-market"
E2E="$MARKET/scripts/e2e-instance.sh"
SDK="$UMB/theme-core/packages/sdk"

BUILD=1
BROWSER=1
KEEP=0
FULL=0
UPGRADE_ONLY=0
# Market's browser smoke set (e2e-browser/) still speaks the pre-cutover API (lib/api.mjs logs in at /api/auth/login) and is not
# owned by this unit, so its result is ADVISORY: recorded in the log and in manifest.json, it does not stop the freeze. Set
# BOOT_GATE_BROWSER=blocking to make it fatal as soon as the market unit has migrated it.
BROWSER_MODE=${BOOT_GATE_BROWSER:-advisory}
ROUTE_WALK_RESULT="not run"
BROWSER_RESULT="not run"
for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --skip-browser) BROWSER=0 ;;
    --keep-instance) KEEP=1 ;;
    --full) FULL=1 ;;
    --upgrade-only) FULL=1; UPGRADE_ONLY=1 ;; # only the upgrade run of --full (no walk, no snapshots, no bundle): for iterating on it
    *) echo "boot-gate: unknown option $a" >&2; exit 2 ;;
  esac
done

[ "$FULL" = 0 ] || [ "$KEEP" = 0 ] || { echo "boot-gate: --full and --keep-instance exclude each other (the upgrade run needs the instance slot)" >&2; exit 2; }

say() { echo "boot-gate: $*" >&2; }
die() { local c=$1; shift; say "FAIL($c) $*"; exit "$c"; }

# ------------------------------------------------------------------------------------------ the slot
# The slot (of-slot.sh) holds the lock for as long as this script runs and gives the names and ports.
if [ -z "${PANO_OF_SLOT:-}" ] || [ -z "${PANO_OF_SLOT_HTTP_PORT:-}" ]; then
  echo "boot-gate: no test-instance slot; run it as  /home/kahverengi/Projects/Pano/pano-open-frontend-spec/tools/of-slot.sh $0 $*" >&2
  exit 2
fi
INSTANCE="$MARKET/build/market-e2e/instance-$PANO_OF_SLOT_NAME"

# ------------------------------------------------------------------------------------------ plugins in the gate
# Migrated plugins (their routes live on /api/plugins/<id>). Since BOOT-03 the list also holds pano-plugin-social-login
# (PL-12) and pano-plugin-whitelist (PL-11). The two gateway plugins with a UI (paynow, tebex) are loaded as extra jars when their
# jars exist (built here, see the build step); they own no route walk entry of their own. The market's own fake provider jar
# is added by e2e-instance.sh.
MIGRATED=(announcement auth-guard avatar bans comments cookies countdown-timer faq link-redirects media-page pages premium-login slider social-login staff-page whitelist)
PLUGIN_IDS=(pano-plugin-market)
for p in "${MIGRATED[@]}"; do PLUGIN_IDS+=("pano-plugin-$p"); done
GATEWAYS=(paynow tebex)

# ------------------------------------------------------------------------------------------ preconditions
command -v node >/dev/null || die 2 "node is required"
command -v bun >/dev/null || die 2 "bun is required"
[ -f "$SDK/bin/pano-api.js" ] || die 2 "theme-core sdk not found at $SDK"
[ -x "$E2E" ] || die 2 "$E2E missing"

if [ -z "${PANO_IT_MARIADB_PASSWORD:-}" ]; then
  PANO_IT_MARIADB_PASSWORD=$(sed -n 's/^[[:space:]]*MYSQL_ROOT_PASSWORD:[[:space:]]*//p' "$PWP/docker-compose.yml" | head -n 1 | tr -d "\"' \r")
  [ -n "$PANO_IT_MARIADB_PASSWORD" ] || die 2 "MYSQL_ROOT_PASSWORD not found in docker-compose.yml and PANO_IT_MARIADB_PASSWORD is not set"
  export PANO_IT_MARIADB_PASSWORD
fi

mkdir -p "$LOGS"
rm -f "$BUNDLE/manifest.json"
FAILED=0
STATIC_RESULT="not run"
UPGRADE_RESULT="not run"
SNAPSHOT_RESULT="not run"

# ------------------------------------------------------------------------------------------ static checks (--full)
# Doc 04 section 9 steps 1, 2 and 4, no JVM needed. check-paths runs against a FRESH extraction of this checkout: the copy
# shipped in the sdk package (api/routes.core.json) is regenerated by the sdk unit and is checked for staleness below.
JS_REPOS=()
static_checks() {
  local fresh="$LOGS/routes.fresh.json" log="$LOGS/static.log" bad=0 n=0 out rc id r
  : >"$log"
  say "static checks: extract-routes, check-paths in every JS repo, migrate-v1 --check (log $log)"
  export PANO_SDK_DIR="$SDK"
  if ! "$SELF/tool.sh" extract-routes --check >>"$log" 2>&1; then say "FAIL extract-routes --check (core)"; bad=$((bad + 1)); fi
  for id in "${PLUGIN_IDS[@]}"; do
    if ! "$SELF/tool.sh" extract-routes --check "plugins/$id" >>"$log" 2>&1; then say "FAIL extract-routes --check ($id)"; bad=$((bad + 1)); fi
  done
  "$SELF/tool.sh" extract-routes >"$fresh" 2>>"$log" || { say "FAIL: extract-routes could not write the fresh route list"; STATIC_RESULT="failed (no route list)"; return 1; }

  JS_REPOS=("$UMB/panel-ui" "$UMB/setup-ui" "$UMB/themes/vanilla-theme" "$UMB/themes/blaze-theme" "$UMB/themes/blocky-theme" "$UMB/themes/frost-theme" "$UMB/themes/banana-theme" "$UMB/theme-core" "$UMB/pano-showcase" "$UMB/pano-starter-sveltekit" "$UMB/pano-boilerplate-plugin")
  for r in "$PWP"/plugins/pano-plugin-*; do
    [ -f "$r/package.json" ] && JS_REPOS+=("$r")
  done
  for r in "${GATEWAYS[@]}"; do [ -f "$PWP/plugins/pano-plugin-market-payments/$r/package.json" ] && JS_REPOS+=("$PWP/plugins/pano-plugin-market-payments/$r"); done
  for r in "${JS_REPOS[@]}"; do
    if [ ! -d "$r" ]; then say "FAIL check-paths: $r does not exist"; bad=$((bad + 1)); continue; fi
    out=$("$SELF/tool.sh" check-paths --routes "$fresh" "$r" 2>&1)
    rc=$?
    printf '== %s\n%s\n' "$r" "$out" >>"$log"
    n=$((n + 1))
    if [ $rc -ne 0 ]; then
      say "FAIL check-paths in ${r#"$UMB"/}: $(printf '%s\n' "$out" | tail -n 3 | tr '\n' ' ')"
      bad=$((bad + 1))
    fi
  done

  # advisory: the Kotlin grep gate (verification 2) and the freshness of the route list the sdk package ships
  if ! out=$("$SELF/tool.sh" migrate-v1 --check 2>&1); then
    printf '== migrate-v1 --check\n%s\n' "$out" >>"$log"
    say "NOTE migrate-v1 --check: $(printf '%s\n' "$out" | tail -n 1) (the literals are listed in $log; advisory)"
  fi
  if [ -f "$SDK/api/routes.core.json" ]; then
    out=$(node -e '
      const fs = require("node:fs");
      const key = (r) => r.method + " " + r.path;
      const fresh = new Set(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).map(key));
      const shipped = new Set(JSON.parse(fs.readFileSync(process.argv[2], "utf8")).map(key));
      const missing = [...fresh].filter((k) => !shipped.has(k));
      const gone = [...shipped].filter((k) => !fresh.has(k));
      if (missing.length || gone.length) console.log("theme-core/packages/sdk/api/routes.core.json is stale: " + missing.length + " route(s) of this checkout missing from it, " + gone.length + " listed that no longer exist");
    ' "$fresh" "$SDK/api/routes.core.json")
    [ -z "$out" ] || { say "NOTE $out (owner: theme-core; regenerate with pano-api extract-routes --core; advisory, check-paths here ran against the fresh list)"; echo "$out" >>"$log"; }
  fi

  if [ $bad -ne 0 ]; then STATIC_RESULT="failed ($bad of $((n + 1 + ${#PLUGIN_IDS[@]})) checks)"; return 1; fi
  STATIC_RESULT="extract-routes clean for core + ${#PLUGIN_IDS[@]} plugins; check-paths clean in $n JS repos"
  say "static checks passed: $STATIC_RESULT"
}
if [ "$FULL" = 1 ] && [ "$UPGRADE_ONLY" = 0 ]; then static_checks || FAILED=1; fi

# ------------------------------------------------------------------------------------------ build
if [ "$BUILD" = 1 ] && [ "$UPGRADE_ONLY" = 0 ]; then
  say "building (Gradle; log $LOGS/build.log)"
  tasks=(:Pano:build)
  for id in "${PLUGIN_IDS[@]}"; do tasks+=(":plugins:$id:build"); done
  tasks+=(:plugins:pano-plugin-market:fakeProviderJar)
  for g in "${GATEWAYS[@]}"; do [ -f "$PWP/plugins/pano-plugin-market-payments/$g/build.gradle.kts" ] && tasks+=(":plugins:pano-plugin-market-payments:$g:build"); done
  (
    cd "$PWP" || exit 1
    flock /tmp/pano-of-gradle.lock systemd-run --user --scope -q -p MemoryMax=6G \
      env JAVA_HOME=/usr/lib/jvm/java-21-openjdk PANO_SDK_DIR="$SDK" \
      ./gradlew --max-workers=3 "${tasks[@]}" -x test -x :Node:shadowJar -x :Node:zipNode -x :Node:copyNodeZip -x :Node:panoNodeJarChecksum
  ) >"$LOGS/build.log" 2>&1 || die 2 "the Gradle build failed (see $LOGS/build.log)"
fi

BUILT_JAR="$PWP/build/libs/Pano-local-build.jar"
[ -f "$BUILT_JAR" ] || die 2 "Pano jar missing: $BUILT_JAR"
EXTRA_JARS=()
for id in "${PLUGIN_IDS[@]}"; do
  j="$PWP/build/plugins/$id-local-build.jar"
  [ -f "$j" ] || die 2 "plugin jar missing: $j"
  [ "$id" = pano-plugin-market ] || EXTRA_JARS+=("$j")
done
GATEWAY_LOADED=()
for g in "${GATEWAYS[@]}"; do
  j="$PWP/build/plugins/pano-plugin-market-$g-local-build.jar"
  if [ -f "$j" ]; then EXTRA_JARS+=("$j"); GATEWAY_LOADED+=("pano-plugin-market-$g"); else say "NOTE: gateway jar $j does not exist; $g is not loaded"; fi
done

# ------------------------------------------------------------------------------------------ the gate jar
# The Pano jar embeds the owner's staged UI zips (UIFiles/*, pre-cutover versions that still call the old /api paths; the
# theme hangs on `/` against this backend). The gate therefore runs a COPY of the jar whose panel-ui and vanilla-theme zips
# are replaced by zips of the migrated builds (panel-ui/build, themes/vanilla-theme/build). The built jar and the staged
# zips in the checkout are never touched. This copy is what the bundle freezes.
GATE_DIR="$RUN/gate-$PANO_OF_SLOT_NAME"
PANO_JAR="$GATE_DIR/Pano-local-build.jar"
JAR_TOOL=/usr/lib/jvm/java-21-openjdk/bin/jar
[ -x "$JAR_TOOL" ] || die 2 "$JAR_TOOL missing"
for ui in panel-ui:"$UMB/panel-ui/build" vanilla-theme:"$UMB/themes/vanilla-theme/build"; do
  [ -f "${ui#*:}/manifest.json" ] && [ -f "${ui#*:}/index.js" ] || die 2 "${ui#*:} is not a built adapter-node UI (run bun run build under the jsbuild lock)"
done
rm -rf -- "$GATE_DIR"
mkdir -p "$GATE_DIR/zips/UIFiles" || die 2 "cannot create $GATE_DIR"
cp --reflink=never -- "$BUILT_JAR" "$PANO_JAR" || die 2 "cannot copy the Pano jar"
check_jar() { # a damaged gate jar shows up later as NoClassDefFoundError inside the running JVM; fail here instead
  unzip -tq "$1" >/dev/null 2>&1 || die 2 "$1 is damaged (unzip -t fails): $2"
}
check_jar "$BUILT_JAR" "the built jar"
sha_built=$(sha256sum "$BUILT_JAR" | cut -d' ' -f1)
for ui in panel-ui:"$UMB/panel-ui/build" vanilla-theme:"$UMB/themes/vanilla-theme/build"; do
  id=${ui%%:*}
  entry=$(unzip -Z1 "$BUILT_JAR" | grep -E "^UIFiles/$id-v[^/]*\.zip$" | head -n 1)
  [ -n "$entry" ] || die 2 "the Pano jar has no UIFiles/$id-v*.zip entry to replace"
  "$JAR_TOOL" --create --no-manifest --file "$GATE_DIR/zips/$entry" -C "${ui#*:}" . || die 2 "zipping the $id build failed"
  (cd "$GATE_DIR/zips" && "$JAR_TOOL" --update --file "$PANO_JAR" "$entry") || die 2 "replacing $entry in the gate jar failed"
  say "gate jar: $entry replaced by the migrated $id build"
done
rm -rf -- "$GATE_DIR/zips"
check_jar "$PANO_JAR" "the gate jar after replacing the UI zips"
[ "$(sha256sum "$BUILT_JAR" | cut -d' ' -f1)" = "$sha_built" ] || die 2 "the built jar changed while the gate jar was made (another Gradle build?)"
export MARKET_E2E_PANO_JAR="$PANO_JAR"
# pano-plugin-premium-login is compiled for Java 21 (class file 65), so the instance runs on JRE 21 (Pano itself runs on 11+).
JAVA_BIN=/usr/lib/jvm/java-21-openjdk/bin/java
[ -x "$JAVA_BIN" ] || die 2 "$JAVA_BIN missing"
export MARKET_E2E_JAVA="$JAVA_BIN"

# ------------------------------------------------------------------------------------------ instance helpers
PID_FILE="$INSTANCE/pano.pid"
STARTED=0

stop_instance() {
  [ "$STARTED" = 1 ] || return 0
  local pid kids k
  pid=$(tr -d ' \n' <"$PID_FILE" 2>/dev/null || true)
  say "stopping the instance (recorded PID ${pid:-none})"
  kids=""
  if [ -n "$pid" ] && is_instance_java "$pid"; then kids=$(descendants "$pid"); fi
  "$E2E" stop >>"$LOGS/instance.log" 2>&1 || say "WARNING: e2e-instance.sh stop reported a problem (see $LOGS/instance.log)"
  # A JVM that ignores SIGTERM would keep the slot's lock (the lock descriptor is inherited) and block the slot:
  # escalate on the recorded PID, and on the UI processes it started, never on a pattern.
  if [ -n "$pid" ] && is_instance_java "$pid"; then
    say "WARNING: the instance JVM $pid did not exit after SIGTERM; sending SIGKILL to it and to its children"
    kill -KILL "$pid" 2>/dev/null
    sleep 1
  fi
  for k in $kids; do kill -0 "$k" 2>/dev/null && kill -TERM "$k" 2>/dev/null; done
  sleep 1
  for k in $kids; do kill -0 "$k" 2>/dev/null && kill -KILL "$k" 2>/dev/null; done
  STARTED=0
}
# the upgrade run (--full) starts a second Pano (the fixture, other port) and the local store stub; both are stopped by the PIDs
# this script recorded, never by a pattern
UPG="$PWP/build/upgrade-fixture-$PANO_OF_SLOT_NAME"
UPG_DB=$PANO_OF_SLOT_DB2
UPG_HTTP=$PANO_OF_SLOT_HTTP2_PORT
UPG_STUB=$PANO_OF_SLOT_STUB_PORT
UPG_PID_FILE="$UPG/instance/pano.pid"
STUB_PID=""

descendants() { # every descendant PID of $1, found through the parent links in /proc (no name matching)
  local p kids
  kids=$(ps -o pid= --ppid "$1" 2>/dev/null | tr -d ' ' | tr '\n' ' ')
  for p in $kids; do echo "$p"; descendants "$p"; done
}

stop_upgrade_instance() {
  local pid kids k
  [ -f "$UPG_PID_FILE" ] || return 0
  pid=$(tr -d ' \n' <"$UPG_PID_FILE" 2>/dev/null || true)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$UPG/instance" ]; then
    kids=$(descendants "$pid")
    kill -TERM "$pid" 2>/dev/null
    for ((i = 0; i < 180; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && { kill -KILL "$pid" 2>/dev/null; sleep 1; }
    for k in $kids; do kill -0 "$k" 2>/dev/null && kill -TERM "$k" 2>/dev/null; done # children the JVM left behind (UI engines it started)
    sleep 1
    for k in $kids; do kill -0 "$k" 2>/dev/null && kill -KILL "$k" 2>/dev/null; done
  fi
  rm -f "$UPG_PID_FILE"
}

stop_stub() {
  [ -z "$STUB_PID" ] || { kill -TERM "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; STUB_PID=""; }
}

cleanup_all() { stop_instance; stop_stub; [ "$FULL" = 0 ] || stop_upgrade_instance; }
trap 'cleanup_all' EXIT
trap 'say "interrupted"; cleanup_all; exit 130' INT TERM

is_instance_java() { # the recorded PID is trusted only while it is the JVM running from the instance directory
  local pid=$1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
  [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$INSTANCE" ] || return 1
  tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -q -- '-jar'
}

PORT_HTTP=$PANO_OF_SLOT_HTTP_PORT
PORT_GW=$PANO_OF_SLOT_GATEWAY_PORT
PORT_THEME=$PANO_OF_SLOT_THEME_PORT
PORT_PANEL=$PANO_OF_SLOT_PANEL_PORT
URL="http://127.0.0.1:$PORT_HTTP"

if [ "$UPGRADE_ONLY" = 0 ]; then # ---- steps 2 to 6: the gate instance
# ------------------------------------------------------------------------------------------ 2. start
# WORKAROUND (not a fix), found by BOOT-02: since c2015e13 Pano's UI proxy sends the authority the visitor used as X-Forwarded-Host,
# so a theme reached at http://127.0.0.1:<port> believes its origin is http://127.0.0.1:<port>, which is exactly the origin of its
# API_URL (http://127.0.0.1:<port>/api). The theme's handleFetch (theme-core src/kit/hooks-server.js) rewrites the backend request
# and returns SvelteKit's own `fetch(request)`, and SvelteKit answers a same-origin request from the theme app itself instead of the
# network: GET /api/v1/site-info is a 404 inside the theme, preparePlugins() throws 503 "Pano backend is not reachable yet" and `/`
# never serves (the install smoke times out). The gate pins the UIs' origin to a different spelling of the same host through
# adapter-node's ORIGIN variable (the UI processes inherit the JVM's environment). Owner of the real fix: theme-core (use the global
# `fetch` for requests handleFetch sends to the backend). Set BOOT_GATE_NO_ORIGIN_WORKAROUND=1 to run without it.
if [ -z "${BOOT_GATE_NO_ORIGIN_WORKAROUND:-}" ]; then
  export ORIGIN="http://localhost:$PORT_HTTP"
  say "NOTE: UI origin pinned to $ORIGIN (workaround for the same-origin SSR fetch loop, see the comment in this script)"
fi
say "starting the isolated instance (log $LOGS/instance.log)"
STARTED=1
# smoke-install.sh has no timeout of its own on some requests, so the whole start is bounded here
timeout --signal=TERM --kill-after=30 1200 "$E2E" start --ui external >"$LOGS/instance.log" 2>&1
rc=$?
if [ $rc -ne 0 ]; then
  tail -n 5 "$LOGS/instance.log" >&2
  STARTED=1 # make sure leftovers (UIs, JVM) are reaped by the recorded PIDs
  stop_instance
  die 2 "e2e-instance.sh start exited $rc"
fi

# ------------------------------------------------------------------------------------------ 3. add the other plugin jars
say "adding ${#EXTRA_JARS[@]} plugin jars and restarting the JVM"
pid=$(tr -d ' \n' <"$PID_FILE" 2>/dev/null || true)
is_instance_java "$pid" || die 2 "recorded PID '$pid' is not the instance JVM"
kill -TERM "$pid"
for ((i = 0; i < 180; i++)); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
kill -0 "$pid" 2>/dev/null && die 2 "the instance JVM $pid did not stop"
cp -- "${EXTRA_JARS[@]}" "$INSTANCE/plugins/" || die 2 "copying the plugin jars failed"
mv -f "$INSTANCE/pano.log" "$INSTANCE/pano-first-boot.log" 2>/dev/null || true
(
  cd "$INSTANCE" || exit 1
  export JAVA_BIN
  export PANO_DB_HOST=127.0.0.1 PANO_DB_PORT=3306 PANO_DB_NAME=$PANO_OF_SLOT_DB PANO_DB_USER=root
  export PANO_DB_PASSWORD=$PANO_IT_MARIADB_PASSWORD PANO_HTTP_PORT=$PORT_HTTP
  exec setsid "$JAVA_BIN" -XX:MaxRAMPercentage=40 \
    -Dpano.market.fakeProvider=true -Dpano.market.jobScale=5 -Dpf4j.pluginsDir=plugins -jar "$INSTANCE/pano.jar" -nogui \
    </dev/null >pano.log 2>&1
) &
echo $! >"$PID_FILE"
sleep 1
is_instance_java "$(cat "$PID_FILE")" || die 2 "the restarted JVM is not the recorded PID"

ready=0
for ((i = 0; i < 180; i++)); do
  if [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL/api/v1/health")" = 200 ] \
    && [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL/api/plugins/pano-plugin-market/store")" = 200 ]; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" = 1 ] || { tail -n 20 "$INSTANCE/pano.log" >&2; die 2 "the instance did not come back up with all plugins (log $INSTANCE/pano.log)"; }
sleep 5 # let the remaining plugins finish their start hooks

cp -f "$INSTANCE/pano.log" "$LOGS/pano.log"
LOADED=()
for id in "${PLUGIN_IDS[@]}"; do
  if grep -q "$id" "$LOGS/pano.log"; then LOADED+=("$id"); else say "WARNING: plugin $id is not mentioned in the Pano log"; fi
done
say "log mentions ${#LOADED[@]} of ${#PLUGIN_IDS[@]} plugins"
sed 's/\x1b\[[0-9;]*m//g' "$LOGS/pano.log" | grep -E '(^|[^A-Za-z])ERROR([^A-Za-z]|$)' >"$LOGS/pano-errors.log"
if [ -s "$LOGS/pano-errors.log" ]; then
  say "NOTE: $(wc -l <"$LOGS/pano-errors.log") ERROR line(s) in the Pano log, kept in $LOGS/pano-errors.log"
fi

# ------------------------------------------------------------------------------------------ 4. route walk
say "route walk (log $LOGS/route-walk.log)"
WALK_ARGS=(--url "$URL" --admin-env "$INSTANCE/admin.env" --plugin-ids "$(IFS=,; echo "${PLUGIN_IDS[*]}")")
if [ "$FULL" = 1 ]; then
  WALK_ARGS+=(--specs --strict-lists --write-snapshots --core-dir "$PWP/Pano/api" --snapshots-dir "$RUN/snapshots")
fi
(
  cd "$PWP" || exit 1
  PANO_SDK_DIR="$SDK" node scripts/api-v1/route-walk.mjs "${WALK_ARGS[@]}" --report "$LOGS/route-walk.json"
) >"$LOGS/route-walk.log" 2>&1
rw=$?
grep -E '^route-walk: (OK|[0-9]+ failure|[0-9]+ KNOWN|specs:)' "$LOGS/route-walk.log" >&2
if [ $rw -ne 0 ]; then
  say "route walk FAILED (exit $rw); failures are grouped by owning unit in $LOGS/route-walk.log"
  FAILED=1
  ROUTE_WALK_RESULT="failed (exit $rw)"
else
  ROUTE_WALK_RESULT=$(grep -E '^route-walk: OK' "$LOGS/route-walk.log" | head -n 1 | sed 's/^route-walk: //')
fi

# --full: a second, read-only pass proves the snapshots just written are what the instance serves (openapi-snapshot --check)
if [ "$FULL" = 1 ]; then
  if [ $rw -le 1 ]; then # 0 = clean, 1 = failures: both fetched the documents; 2 = the walk could not run
    say "snapshot check (log $LOGS/snapshot-check.log)"
    (
      cd "$PWP" || exit 1
      PANO_SDK_DIR="$SDK" node scripts/api-v1/route-walk.mjs --url "$URL" --admin-env "$INSTANCE/admin.env" --plugin-ids "$(IFS=,; echo "${PLUGIN_IDS[*]}")" \
        --specs-only --core-dir "$PWP/Pano/api" --snapshots-dir "$RUN/snapshots"
    ) >"$LOGS/snapshot-check.log" 2>&1
    sc=$?
    grep -E '^route-walk: (OK|[0-9]+ failure|specs:)' "$LOGS/snapshot-check.log" >&2
    if [ $sc -ne 0 ]; then
      SNAPSHOT_RESULT="check failed (exit $sc)"
      FAILED=1
    else
      SNAPSHOT_RESULT=$(grep -E '^route-walk: specs:' "$LOGS/snapshot-check.log" | head -n 1 | sed 's/^route-walk: specs: //')
    fi
  else
    SNAPSHOT_RESULT="not checked (the route walk failed)"
  fi
fi

# ------------------------------------------------------------------------------------------ 5. market browser smoke
if [ "$BROWSER" = 1 ]; then
  say "market e2e:browser smoke (log $LOGS/e2e-browser.log)"
  (
    cd "$MARKET" || exit 1
    export MARKET_E2E_URL="$URL" MARKET_E2E_DIR="$INSTANCE" MARKET_E2E_GATEWAY_PORT="$PORT_GW" MARKET_E2E_DB="$PANO_OF_SLOT_DB"
    export MARKET_E2E_THEME_URL="http://127.0.0.1:$PORT_THEME" MARKET_E2E_PANEL_URL="http://127.0.0.1:$PORT_PANEL"
    export E2E_BROWSER_SHOTS="$LOGS/shots"
    systemd-run --user --scope -q -p MemoryMax=4G bun run e2e:browser smoke/ # no jsbuild lock: the slot bounds Playwright runs to two
  ) >"$LOGS/e2e-browser.log" 2>&1
  eb=$?
  if [ $eb -ne 0 ]; then
    BROWSER_RESULT="failed (exit $eb): $(grep -m1 -E '^error: ' "$LOGS/e2e-browser.log" | cut -c1-160)"
    if [ "$BROWSER_MODE" = blocking ]; then
      say "e2e:browser smoke FAILED (exit $eb): $BROWSER_RESULT"
      FAILED=1
    else
      say "WARNING e2e:browser smoke FAILED (exit $eb), advisory: $BROWSER_RESULT"
    fi
  else
    BROWSER_RESULT="passed"
    say "e2e:browser smoke passed"
  fi
else
  say "browser smoke skipped (--skip-browser); the gate cannot freeze the bundle"
  BROWSER_RESULT="skipped"
  FAILED=1
fi

# ------------------------------------------------------------------------------------------ 5b. behaviour smoke (BOOT-03, blocking)
# behaviour-smoke.mjs (CX-05) restarts the slot's JVM once (e-mail verification off, webhook private targets on), so it runs last,
# on the gate instance, after the route walk and the browser smoke.
BEHAVIOUR_RESULT="not run"
if [ "$FULL" = 1 ] && [ "${BOOT_GATE_NO_BEHAVIOUR:-}" = "" ]; then
  say "behaviour smoke (log $LOGS/behaviour.log)"
  (
    cd "$PWP" || exit 1
    # shellcheck disable=SC1090
    set -a; . "$INSTANCE/admin.env"; set +a
    export PANO_URL="$URL" PANO_ADMIN_USER="${SMOKE_ADMIN_USER:-}" PANO_ADMIN_PASSWORD="${SMOKE_ADMIN_PASSWORD:-}" PANO_OF_SLOT_DIR="$INSTANCE"
    timeout --signal=TERM --kill-after=30 1500 node scripts/api-v1/behaviour-smoke.mjs --out "$LOGS/behaviour.json"
  ) >"$LOGS/behaviour.log" 2>&1
  bs=$?
  cp -f "$LOGS/behaviour.json" "$RUN/gate/behaviour.json" 2>/dev/null || true
  grep -E '^(ok|FAIL|skip) ' "$LOGS/behaviour.log" >&2
  if [ $bs -ne 0 ]; then
    BEHAVIOUR_RESULT="failed (exit $bs): $(grep -c '^FAIL ' "$LOGS/behaviour.log") check(s) FAIL"
    say "behaviour smoke FAILED: $BEHAVIOUR_RESULT"
    FAILED=1
  else
    BEHAVIOUR_RESULT="passed: $(grep -c '^ok ' "$LOGS/behaviour.log") ok, $(grep -c '^skip ' "$LOGS/behaviour.log") skip"
    say "behaviour smoke $BEHAVIOUR_RESULT"
  fi
fi

# ------------------------------------------------------------------------------------------ 6. stop
if [ "$KEEP" = 1 ]; then
  say "--keep-instance: leaving the instance running (stop it with $E2E stop)"
  STARTED=0
else
  stop_instance
fi

fi # UPGRADE_ONLY

# ------------------------------------------------------------------------------------------ 6b. the upgrade run (--full)
# Decision 36, doc 04 section 9 step 5. The gate instance is stopped above, so this is the only Pano running.
#   mode boot   (the gate): the store stub is up when the new jar boots, gate 0 has to install the compatible versions during the boot.
#   mode retry  (diagnostic, never a pass): the stub is down at boot; once Pano is up it starts and the panel's "Retry"
#               (POST /panel/compatibility/reconcile) runs gate 0, then Pano restarts and the end state is asserted. It exists so a
#               boot-time failure of gate 0 does not hide whether the rest of the upgrade works.
UPG_URL="http://127.0.0.1:$UPG_HTTP"

start_upgraded() { # boots the new jar in the fixture instance on JRE 21, records the PID; 1 when it is not the recorded JVM
  local inst="$UPG/instance" pid log=${1:-pano-upgraded.log}
  (
    cd "$inst" || exit 1
    export PANO_DB_HOST=127.0.0.1 PANO_DB_PORT=3306 PANO_DB_NAME=$UPG_DB PANO_DB_USER=root
    export PANO_DB_PASSWORD=$PANO_IT_MARIADB_PASSWORD PANO_HTTP_PORT=$UPG_HTTP
    if [ -z "${BOOT_GATE_NO_ORIGIN_WORKAROUND:-}" ]; then export ORIGIN="http://localhost:$UPG_HTTP"; fi
    exec setsid "$JAVA_BIN" -XX:MaxRAMPercentage=40 -Dpf4j.pluginsDir=plugins -jar Pano-local-build.jar -nogui \
      </dev/null >"$log" 2>&1
  ) &
  echo $! >"$UPG_PID_FILE"
  sleep 1
  pid=$(cat "$UPG_PID_FILE")
  [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$inst" ]
}

wait_upgraded() { # $1 = seconds; 0 once /api/v1/health answers 200
  local i pid
  pid=$(cat "$UPG_PID_FILE" 2>/dev/null)
  for ((i = 0; i < $1; i += 2)); do
    [ "$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$UPG_URL/api/v1/health")" = 200 ] && return 0
    kill -0 "$pid" 2>/dev/null || return 1
    sleep 2
  done
  return 1
}

start_stub() {
  local fx="$UPG/fixture.json" arts a args=() copy
  arts=$(node -e 'for (const a of JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).storeStub.artifacts) console.log(a)' "$fx") || return 1
  while IFS= read -r a; do
    if [ -d "$a" ] && [ -f "$a/manifest.json" ]; then
      # The theme build is a premium resource ("premium": true): installing it needs the platform license check, which a
      # fixture that is not linked to an account cannot pass (INSTALL_FAILED ... license check failed: not-connected). The
      # stub serves a test copy of the same build with premium switched off (doc 04 section 9 step 5 is about the version gate,
      # not about licensing); the build folder itself is not touched.
      copy="$UPG/stub-artifacts/$(basename "$(dirname "$a")")"
      rm -rf -- "$copy"; mkdir -p "$copy"; cp -a -- "$a" "$copy/build"
      node -e 'const fs = require("fs"), f = process.argv[1] + "/manifest.json"; const m = JSON.parse(fs.readFileSync(f, "utf8")); m.premium = false; fs.writeFileSync(f, JSON.stringify(m, null, 2));' "$copy/build" || return 1
      a="$copy/build"
    fi
    [ -z "$a" ] || args+=(--artifact "$a")
  done <<<"$arts"
  node "$SELF/store-stub.mjs" "${args[@]}" --port "$UPG_STUB" >"$LOGS/store-stub.log" 2>&1 &
  STUB_PID=$!
  local i
  for ((i = 0; i < 30; i++)); do
    [ "$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$UPG_STUB/__stub/health")" = 200 ] && return 0
    kill -0 "$STUB_PID" 2>/dev/null || break
    sleep 1
  done
  tail -n 5 "$LOGS/store-stub.log" >&2
  return 1
}

upgrade_run() {
  local mode=${1:-boot} fx="$UPG/fixture.json" inst="$UPG/instance" rc uc=0 oldjar
  UPGRADE_RESULT="failed"
  say "upgrade run ($mode) 1/5: building the pre-cutover fixture (log $LOGS/upgrade-fixture.log; this boots the alpha.537 jar on JRE 11)"
  timeout --signal=TERM --kill-after=30 1500 node "$SELF/upgrade-fixture/fixture.mjs" build --out "$UPG" --db "$UPG_DB" --http-port "$UPG_HTTP" --stub-port "$UPG_STUB" >"$LOGS/upgrade-fixture.log" 2>&1
  rc=$?
  if [ $rc -ne 0 ]; then
    tail -n 6 "$LOGS/upgrade-fixture.log" >&2
    UPGRADE_RESULT="fixture build failed (exit $rc): $(tail -n 1 "$LOGS/upgrade-fixture.log" | cut -c1-200) [owner PF-31]"
    return 1
  fi
  say "$(tail -n 1 "$LOGS/upgrade-fixture.log")"

  if [ "$mode" = boot ]; then
    say "upgrade run ($mode) 2/5: starting the local store stub on $UPG_STUB"
    start_stub || { UPGRADE_RESULT="the store stub did not start (owner PF-31)"; return 1; }
  else
    say "upgrade run ($mode) 2/5: the store stub stays down for the first boot"
  fi

  say "upgrade run ($mode) 3/5: swapping in the new jar and booting (log $inst/pano-upgraded.log)"
  oldjar=$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")).pano.copiedAs)' "$fx")
  rm -f -- "$inst/$oldjar"
  cp --reflink=never -- "$PANO_JAR" "$inst/Pano-local-build.jar" || { UPGRADE_RESULT="cannot copy the new jar"; return 1; }
  start_upgraded || { UPGRADE_RESULT="the upgraded JVM is not the recorded PID"; return 1; }
  if ! wait_upgraded "${BOOT_GATE_UPGRADE_BOOT_WAIT:-240}"; then
    # evidence before the JVM is stopped: where every thread is
    local pid
    pid=$(cat "$UPG_PID_FILE" 2>/dev/null)
    timeout 30 "$(dirname "$JAVA_BIN")/jcmd" "$pid" Thread.print >"$LOGS/pano-upgraded-threads.txt" 2>&1 || true
    cp -f "$inst/pano-upgraded.log" "$LOGS/pano-upgraded.log" 2>/dev/null || true
    tail -n 6 "$inst/pano-upgraded.log" | cut -c1-240 >&2
    if grep -q "Main.reconcileCompatibility" "$LOGS/pano-upgraded-threads.txt" 2>/dev/null; then
      UPGRADE_RESULT="the upgraded Pano never came up: the boot-time gate 0 reconcile is stuck (Main.reconcileCompatibility runBlocking inside Vert.x executeBlocking while InstallManager awaits its own nested executeBlocking; threads in $LOGS/pano-upgraded-threads.txt) [owner D4: CompatibilityReconciler / Main.kt]"
    else
      UPGRADE_RESULT="the upgraded Pano did not come up (log $LOGS/pano-upgraded.log, threads $LOGS/pano-upgraded-threads.txt) [owner: the unit named by the first ERROR line]"
    fi
    return 1
  fi
  sleep 5

  say "upgrade run ($mode) 4/5: asserting (log $LOGS/upgrade-check.log)"
  : >"$LOGS/upgrade-check.log"
  local walk=(node scripts/api-v1/route-walk.mjs --url "$UPG_URL" --upgrade "$fx" --stub-url "http://127.0.0.1:$UPG_STUB")
  if [ "$mode" = boot ]; then
    (cd "$PWP" && PANO_SDK_DIR="$SDK" "${walk[@]}") >>"$LOGS/upgrade-check.log" 2>&1 || uc=$?
  else
    start_stub || { UPGRADE_RESULT="the store stub did not start (owner PF-31)"; return 1; }
    (cd "$PWP" && PANO_SDK_DIR="$SDK" "${walk[@]}" --reconcile) >>"$LOGS/upgrade-check.log" 2>&1 || uc=$?
    say "restarting the upgraded Pano to serve what the reconcile installed"
    stop_upgrade_instance
    start_upgraded pano-upgraded-2.log || { UPGRADE_RESULT="the restarted JVM is not the recorded PID"; return 1; }
    if wait_upgraded 360; then
      sleep 5
      (cd "$PWP" && PANO_SDK_DIR="$SDK" "${walk[@]}" --after-restart) >>"$LOGS/upgrade-check.log" 2>&1 || uc=$?
    else
      uc=1
      echo "route-walk: the upgraded Pano did not come back after the restart" >>"$LOGS/upgrade-check.log"
    fi
  fi
  grep -E '^\s+\[FAIL\]|^route-walk: upgrade check' "$LOGS/upgrade-check.log" >&2
  cp -f "$inst"/pano-upgraded*.log "$LOGS/" 2>/dev/null || true
  sed 's/\x1b\[[0-9;]*m//g' "$LOGS/pano-upgraded.log" 2>/dev/null | grep -E '(^|[^A-Za-z])ERROR([^A-Za-z]|$)' >"$LOGS/pano-upgraded-errors.log"
  [ ! -s "$LOGS/pano-upgraded-errors.log" ] || say "NOTE: $(wc -l <"$LOGS/pano-upgraded-errors.log") ERROR line(s) in the upgraded log, kept in $LOGS/pano-upgraded-errors.log"

  say "upgrade run ($mode) 5/5: stopping the upgraded Pano and the stub, column scan, config and disabled list"
  stop_upgrade_instance
  stop_stub
  local scan=0 extra=""
  # The scheme_version table keeps each migration's description, and the description of the stored-URL migration names the old
  # prefix on purpose ("Rewrite stored /api file URLs to /api/v1"): that column is by design, so it joins the fixture's allow-list.
  node -e '
    const fs = require("fs");
    const base = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    base.columns = [...new Set([...(base.columns || []), "pano_scheme_version.extra"])];
    fs.writeFileSync(process.argv[2], JSON.stringify(base, null, 2));
  ' "$SELF/upgrade-fixture/column-scan.allow.json" "$LOGS/column-scan.allow.json"
  node "$SELF/column-scan.mjs" --db "$UPG_DB" --allow "$LOGS/column-scan.allow.json" >"$LOGS/column-scan.log" 2>&1 || scan=$?
  tail -n 3 "$LOGS/column-scan.log" >&2
  if [ $scan -ne 0 ]; then extra="$extra; column scan found old /api/ URLs (exit $scan, owner H1 / the plugin stored-URL migrations)"; fi
  if ! grep -Eq '^[[:space:]]*current-theme[[:space:]]*=[[:space:]]*"?blaze-theme' "$inst/config.conf"; then
    extra="$extra; config.conf no longer names blaze-theme as current-theme (owner D1)"
  fi
  if [ -f "$inst/plugins/disabled.txt" ] && grep -Eq 'pano-plugin-(comments|market)' "$inst/plugins/disabled.txt"; then
    extra="$extra; a gated plugin was written to disabled.txt (owner D1)"
  fi
  if [ $uc -ne 0 ] || [ -n "$extra" ]; then
    UPGRADE_RESULT="failed ($mode path): upgrade check exit $uc${extra}"
    return 1
  fi
  UPGRADE_RESULT="passed ($mode path): gate 0 installed the compatible comments, market and blaze from the stub; the same theme and plugins run; server and node listed under agents; column scan clean"
  say "upgrade run ($mode) passed"
}
if [ "$FULL" = 1 ]; then
  if [ "$FAILED" = 0 ] || [ "${BOOT_GATE_UPGRADE_ANYWAY:-1}" = 1 ]; then
    if [ "${BOOT_GATE_UPGRADE_MODE:-boot}" = retry ]; then
      upgrade_run retry || { say "UPGRADE RUN (retry path, diagnostic) FAILED: $UPGRADE_RESULT"; FAILED=1; }
    else
      upgrade_run boot || {
        say "UPGRADE RUN FAILED: $UPGRADE_RESULT"
        FAILED=1
        BOOT_RESULT=$UPGRADE_RESULT
        # diagnostic: does the rest of the upgrade work when gate 0 runs through the panel's Retry instead of the boot?
        stop_upgrade_instance
        stop_stub
        if [ "${BOOT_GATE_NO_RETRY_DIAGNOSTIC:-}" = "" ]; then
          if upgrade_run retry; then
            UPGRADE_RESULT="FAILED on the boot path: ${BOOT_RESULT}. Diagnostic retry path (panel Retry + restart): $UPGRADE_RESULT"
          else
            UPGRADE_RESULT="FAILED on the boot path: ${BOOT_RESULT}. Diagnostic retry path also failed: $UPGRADE_RESULT"
          fi
        fi
      }
    fi
    stop_upgrade_instance
    stop_stub
  fi
fi

if [ "$UPGRADE_ONLY" = 1 ]; then
  say "upgrade-only: $UPGRADE_RESULT"
  [ "$FAILED" = 0 ] && exit 0 || exit 1
fi

if [ "$FAILED" = 1 ]; then
  say "GATE FAILED: no bundle frozen. Logs: $LOGS"
  [ "$FULL" = 0 ] || say "full gate: static=[$STATIC_RESULT] route walk=[$ROUTE_WALK_RESULT] snapshots=[$SNAPSHOT_RESULT] upgrade=[$UPGRADE_RESULT]"
  exit 1
fi

# ------------------------------------------------------------------------------------------ 7. freeze
say "freezing the demo bundle into $BUNDLE"
for d in pano plugins panel-ui vanilla-theme; do rm -rf -- "${BUNDLE:?}/$d"; done
mkdir -p "$BUNDLE/pano" "$BUNDLE/plugins" || die 2 "cannot create the bundle"
cp -- "$PANO_JAR" "$BUNDLE/pano/" || die 2 "copy Pano jar"
for id in "${PLUGIN_IDS[@]}"; do cp -- "$PWP/build/plugins/$id-local-build.jar" "$BUNDLE/plugins/" || die 2 "copy $id"; done
for id in "${GATEWAY_LOADED[@]}"; do cp -- "$PWP/build/plugins/$id-local-build.jar" "$BUNDLE/plugins/" || die 2 "copy $id"; done
for ui in "panel-ui:$UMB/panel-ui/build:panel-ui" "vanilla-theme:$UMB/themes/vanilla-theme/build:vanilla-theme"; do
  IFS=: read -r name src dst <<<"$ui"
  [ -d "$src" ] || die 2 "$src is missing: build it first (bun run build under the jsbuild lock)"
  cp -a -- "$src" "$BUNDLE/$dst" || die 2 "copy $src"
done

PLUGIN_LIST=$(IFS=,; echo "${PLUGIN_IDS[*]}")
BUNDLE="$BUNDLE" UMB="$UMB" PWP="$PWP" PLUGINS="$PLUGIN_LIST" BUILT_JAR="$BUILT_JAR" ROUTE_WALK_RESULT="$ROUTE_WALK_RESULT" BROWSER_RESULT="$BROWSER_RESULT" BEHAVIOUR_RESULT="$BEHAVIOUR_RESULT" GATEWAYS_LOADED="${GATEWAY_LOADED[*]:-}" BROWSER_MODE="$BROWSER_MODE" FULL="$FULL" STATIC_RESULT="$STATIC_RESULT" SNAPSHOT_RESULT="$SNAPSHOT_RESULT" UPGRADE_RESULT="$UPGRADE_RESULT" UI_ORIGIN_PIN="${ORIGIN:-}" node --input-type=module -e '
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
const { BUNDLE, UMB, PWP, PLUGINS, BUILT_JAR, ROUTE_WALK_RESULT, BROWSER_RESULT, BEHAVIOUR_RESULT, GATEWAYS_LOADED, BROWSER_MODE, FULL, STATIC_RESULT, SNAPSHOT_RESULT, UPGRADE_RESULT, UI_ORIGIN_PIN } = process.env;
const git = (dir, ...a) => {
  const r = spawnSync("git", ["-C", dir, ...a], { encoding: "utf8" });
  return r.status === 0 ? r.stdout.trim() : null;
};
const repo = (dir) => ({ commit: git(dir, "rev-parse", "HEAD"), branch: git(dir, "rev-parse", "--abbrev-ref", "HEAD"), dirty: (git(dir, "status", "--porcelain") || "").split("\n").filter(Boolean).length });
const sha = (f) => crypto.createHash("sha256").update(fs.readFileSync(f)).digest("hex");
const repos = {
  "pano-web-platform": repo(PWP),
  "theme-core": repo(path.join(UMB, "theme-core")),
  "panel-ui": repo(path.join(UMB, "panel-ui")),
  "themes/vanilla-theme": repo(path.join(UMB, "themes", "vanilla-theme")),
};
for (const id of PLUGINS.split(",")) repos[`pano-web-platform/plugins/${id}`] = repo(path.join(PWP, "plugins", id));
const files = {};
const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) { if (e.name !== "_gate") walk(p); } else if (e.name !== "manifest.json") files[path.relative(BUNDLE, p)] = sha(p); } };
walk(BUNDLE);
const manifest = {
  unit: FULL === "1" ? "BOOT-03" : "BOOT-01",
  frozenAt: new Date().toISOString(),
  note: "Demo backend frozen after boot-gate.sh passed: the route walk ran against one isolated Pano with every migrated plugin (see gate for the market browser smoke result).",
  gate: {
    routeWalk: ROUTE_WALK_RESULT,
    routeWalkAllowList: "scripts/api-v1/route-walk.allow.json in pano-web-platform lists every recorded deviation with its owning unit",
    marketBrowserSmoke: BROWSER_RESULT,
    marketBrowserSmokeMode: BROWSER_MODE,
    behaviourSmoke: BEHAVIOUR_RESULT,
    gatewayPlugins: GATEWAYS_LOADED,
    jre: "JRE 21 (pano-plugin-premium-login is compiled for Java 21)",
    ...(UI_ORIGIN_PIN ? { uiOriginPinnedTo: UI_ORIGIN_PIN, uiOriginPinNote: "workaround for the same-origin SSR fetch loop of the theme behind the Pano proxy (see boot-gate.sh); the bundle itself runs fine behind a proxy host that differs from the API_URL host" } : {}),
    ...(FULL === "1" ? { full: true, staticChecks: STATIC_RESULT, snapshots: SNAPSHOT_RESULT, upgradeRun: UPGRADE_RESULT } : {}),
  },
  repos,
  entrypoints: { pano: "pano/Pano-local-build.jar", plugins: "plugins/", panelUi: "panel-ui/", vanillaTheme: "vanilla-theme/" },
  panoJar: {
    note: "A copy of the built jar whose UIFiles panel-ui and vanilla-theme zips are the migrated panel-ui/ and vanilla-theme/ builds in this bundle (the built jar embeds pre-cutover UI zips that cannot talk to /api/v1).",
    builtJarSha256: sha(BUILT_JAR),
  },
  howToRun: "Start pano/Pano-local-build.jar on JRE 11+ with -Dpf4j.pluginsDir=<dir holding plugins/*.jar> -jar ... -nogui, a fresh MariaDB database and plugins/pano-plugin-market (+ its fake provider jar for tests). boot-gate.sh shows the exact sequence.",
  files,
};
fs.writeFileSync(path.join(BUNDLE, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
' || die 2 "writing manifest.json failed"

say "OK: gate passed, bundle frozen ($BUNDLE/manifest.json)"
exit 0
