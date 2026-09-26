#!/usr/bin/env bash
# End-to-end boot test of a real Pano jar in the pano-runtime image, run exactly like the Portal agent
# runs a Pano Instance with runtimeLauncher=image (ContainerPlan flags + env), against a memory-limited
# MariaDB and a fake Pano Host control plane (python3, bound to the test network's gateway).
#   docker/runtime/e2e.sh <Pano jar> [JRE...]     (default JREs: 11 21; linux/amd64 only)
# The first JRE runs the full scenario: env -> config, HTTP on 8088, setup (scripts/smoke-install.sh),
# setup never visits the env-managed mail step (1 -> 4), capabilities announce, /panel/host-sso (single use),
# GET /api/panel/hosted, mail delivered through a fake Portal mail relay (plain SMTP + AUTH on 2525, no STARTTLS),
# in-panel restart as an exit-75 relaunch (RestartCount stays 0), a simulated self-update through /data/.pano-jar,
# a local Pano backup restored in the panel (the post-restore restart is an exit-75 relaunch too), clean stop.
# Every further JRE boots the installed instance again and checks it serves (smoke).
# PH_KEEP=1 leaves everything running for debugging. Needs docker, python3, curl, jq. Resources are named ph-w4-* (PH_PREFIX) and removed on exit.
# The fake control plane and the fake mail relay bind the test network's gateway on PH_CP_PORT (18601) and PH_SMTP_PORT (2525).
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
jar=${1:?usage: e2e.sh <Pano jar> [JRE...]}
shift
jres=("$@")
[ ${#jres[@]} -eq 0 ] && jres=(11 21)
prefix=${PH_PREFIX:-ph-w4}
http_port=${PH_HTTP_PORT:-18088}
cp_port=${PH_CP_PORT:-18601}
smtp_port=${PH_SMTP_PORT:-2525}
net="$prefix-net"
db="$prefix-db"
pano="$prefix-pano"
uid=10123
workload=p-e2e00001
work=$(mktemp -d)
data="$work/data"
base="http://127.0.0.1:$http_port"
failures=0
cp_pid=
smtp_pid=
last_image=

# agent-generated secrets are random; so are these (never printed)
secret=$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
db_pass=$(head -c 18 /dev/urandom | base64 | tr '+/' 'xy' | tr -d '=')
admin_pass="E2e-$(head -c 9 /dev/urandom | base64 | tr '+/' 'xy')1"

cleanup() {
  if [ -n "${PH_KEEP:-}" ]; then echo "PH_KEEP: left $pano, $db, $net, $work and the fake control plane (pid $cp_pid) and mail relay (pid $smtp_pid) running"; return; fi
  docker rm -f "$pano" >/dev/null 2>&1 || true
  if [ -n "$last_image" ] && [ -d "$data" ]; then
    docker run --rm --user 0 --network none --entrypoint sh -v "$data:/data" "$last_image" -c 'rm -rf /data/* /data/.[!.]*' >/dev/null 2>&1 || true
  fi
  docker rm -f "$db" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  [ -n "$cp_pid" ] && kill "$cp_pid" 2>/dev/null || true
  [ -n "$smtp_pid" ] && kill "$smtp_pid" 2>/dev/null || true
  for jre in "${jres[@]}"; do docker rmi -f "$prefix-runtime:jre$jre" >/dev/null 2>&1 || true; done
  rm -rf "$work" 2>/dev/null || true
}
trap cleanup EXIT

pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; failures=$((failures + 1)); }
check() { local name=$1; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
wait_for() { # seconds, command...
  local n=$1 i; shift
  for ((i = 0; i < n; i++)); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
http_code() { curl -s -o /dev/null -w '%{http_code}' "$base$1"; }
serves() { [ "$(http_code "$1")" = "$2" ]; }
in_pano() { docker exec "$pano" sh -c "$1"; }
restart_count() { docker inspect -f '{{.RestartCount}}' "$pano"; }
running() { [ "$(docker inspect -f '{{.State.Running}}' "$pano" 2>/dev/null)" = true ]; }
no_secret_in_logs() { ! docker logs "$pano" 2>&1 | grep -qF -e "$secret" -e "$db_pass"; }
not_matching() { ! grep -Eq "$1" <<<"$2"; }
jqok() { jq -e "$@" >/dev/null; }
not_contains() { ! grep -qF -- "$2" <<<"$1"; }
logs_have() { docker logs "$pano" 2>&1 | grep -q -- "$1"; }

# ---- network, MariaDB, fake control plane --------------------------------------------------------
docker network create "$net" >/dev/null
gateway=$(docker network inspect -f '{{(index .IPAM.Config 0).Gateway}}' "$net")

docker run -d --name "$db" --network "$net" --memory 1024m --memory-swap 1024m --cpus 2 \
  -e MARIADB_RANDOM_ROOT_PASSWORD=1 -e MARIADB_DATABASE=pano_w -e MARIADB_USER=pano_w -e "MARIADB_PASSWORD=$db_pass" \
  mariadb:11 >/dev/null

cat > "$work/cp.py" <<'PY'
import json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
host, port, secret, log = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
tickets, lock = {}, threading.Lock()
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        try: body = json.loads(raw or b"null")
        except ValueError: body = None
        if self.path == "/_issue":  # test hook, not a W3 route
            with lock: tickets[body["ticket"]] = body["data"]
            return self.reply(200, {"result": "ok"})
        authed = self.headers.get("Authorization") == "Bearer " + secret
        with open(log, "a") as f: f.write(json.dumps({"path": self.path, "authed": authed, "keys": sorted(body) if isinstance(body, dict) else None}) + "\n")
        if self.path not in ("/api/host/instance/capabilities", "/api/host/sso/redeem"): return self.reply(404, {"result": "error", "error": "NOT_EXISTS"})
        if not isinstance(body, dict): return self.reply(400, {"result": "error", "error": "BAD_REQUEST"})
        if not authed: return self.reply(401, {"result": "error", "error": "INVALID_TOKEN"})
        if self.path.endswith("/capabilities"):
            if set(body) != {"ssoSupported"}: return self.reply(400, {"result": "error", "error": "BAD_REQUEST"})
            return self.reply(200, {"result": "ok", "data": {"ssoSupported": body["ssoSupported"]}})
        if set(body) != {"ticket"}: return self.reply(400, {"result": "error", "error": "BAD_REQUEST"})
        with lock: data = tickets.pop(body["ticket"], None)
        if data is None: return self.reply(401, {"result": "error", "error": "INVALID_TOKEN"})
        return self.reply(200, {"result": "ok", "data": data})
ThreadingHTTPServer((host, port), H).serve_forever()
PY
touch "$work/cp.log"
python3 "$work/cp.py" "$gateway" "$cp_port" "$secret" "$work/cp.log" &
cp_pid=$!
wait_for 10 curl -fsS -X POST --data '{"ticket":"warmup-warmup-warmup","data":{}}' "http://$gateway:$cp_port/_issue" \
  || { echo "fake control plane did not start"; exit 1; }

# A fake Portal mail relay: plain SMTP that offers AUTH but no STARTTLS (like the agent's relay on 2525).
# Logs one JSON line per accepted message (auth user, whether STARTTLS was asked for, recipients, size).
cat > "$work/relay.py" <<'PY'
import base64, json, socketserver, sys
host, port, log = sys.argv[1], int(sys.argv[2]), sys.argv[3]
class H(socketserver.StreamRequestHandler):
    def send(self, line): self.wfile.write((line + "\r\n").encode()); self.wfile.flush()
    def handle(self):
        user, rcpts, starttls = None, [], False
        self.send("220 relay.e2e ESMTP")
        while True:
            raw = self.rfile.readline(65536)
            if not raw: return
            line = raw.decode("utf-8", "replace").rstrip("\r\n"); verb = line.split(" ", 1)[0].upper()
            if verb in ("EHLO", "HELO"): self.send("250-relay.e2e\r\n250-AUTH PLAIN LOGIN\r\n250-8BITMIME\r\n250 SIZE 26214400")
            elif verb == "STARTTLS": starttls = True; self.send("502 5.5.1 STARTTLS not offered")
            elif verb == "AUTH":
                parts = line.split()
                if parts[1].upper() == "PLAIN":
                    if len(parts) > 2: resp = parts[2]
                    else: self.send("334 "); resp = self.rfile.readline().decode().strip()
                    user = base64.b64decode(resp).split(b"\0")[1].decode()
                else:
                    self.send("334 VXNlcm5hbWU6"); user = base64.b64decode(self.rfile.readline().decode().strip()).decode()
                    self.send("334 UGFzc3dvcmQ6"); self.rfile.readline()
                self.send("235 2.7.0 Authentication successful")
            elif verb == "MAIL": rcpts = []; self.send("250 2.1.0 Ok")
            elif verb == "RCPT": rcpts.append(line.split(":", 1)[1].strip().strip("<>").split(">")[0]); self.send("250 2.1.5 Ok")
            elif verb == "DATA":
                self.send("354 End data with <CR><LF>.<CR><LF>"); size = 0
                while True:
                    chunk = self.rfile.readline(1 << 20)
                    if not chunk or chunk in (b".\r\n", b".\n"): break
                    size += len(chunk)
                with open(log, "a") as f: f.write(json.dumps({"user": user, "starttls": starttls, "rcpt": rcpts, "size": size}) + "\n")
                self.send("250 2.0.0 Ok: queued")
            elif verb == "RSET": rcpts = []; self.send("250 Ok")
            elif verb == "NOOP": self.send("250 Ok")
            elif verb == "QUIT": self.send("221 Bye"); return
            else: self.send("502 5.5.2 Command not recognized")
class S(socketserver.ThreadingTCPServer): allow_reuse_address = True; daemon_threads = True
S((host, port), H).serve_forever()
PY
touch "$work/relay.log"
python3 "$work/relay.py" "$gateway" "$smtp_port" "$work/relay.log" &
smtp_pid=$!
wait_for 10 sh -c "exec 3<>/dev/tcp/$gateway/$smtp_port" || { echo "fake mail relay did not start"; exit 1; }

# ---- the Pano Instance container (ContainerPlan.runArgs with runtimeLauncher=image) --------------
mkdir -p "$data"
cp "$jar" "$data/Pano-e2e-a.jar"
printf 'Pano-e2e-a.jar' > "$data/.pano-jar"

export PANO_HOSTED=pano-host PANO_HOST_WORKLOAD_ID=$workload PANO_HOST_INSTANCE_SECRET=$secret \
  PANO_HOST_API_URL="http://$gateway:$cp_port/api" PANO_DB_HOST=$db PANO_DB_PORT=3306 PANO_DB_NAME=pano_w \
  PANO_DB_USER=pano_w PANO_DB_PASSWORD=$db_pass PANO_SMTP_HOST=$gateway PANO_SMTP_PORT=$smtp_port \
  PANO_SMTP_USER=pano_w PANO_SMTP_PASSWORD=smtp-e2e PANO_JVM_ARGS="-Xmx512m -XX:+UseSerialGC"

run_pano() { # image
  docker rm -f "$pano" >/dev/null 2>&1 || true
  docker run -d -i --init --name "$pano" --hostname "$pano" \
    --label pano.host.workload=true --label "pano.host.id=$workload" \
    --user "$uid:$uid" --cap-drop ALL --security-opt no-new-privileges --read-only \
    --tmpfs /tmp:rw,nosuid,nodev,size=256m,mode=1777 --mount "type=bind,source=$data,target=/data" --workdir /data \
    --log-driver local --log-opt max-size=10m --log-opt max-file=3 --network "$net" --restart unless-stopped --stop-timeout 30 \
    --env HOME=/data --env PANO_DB_HOST --env PANO_DB_NAME --env PANO_DB_PASSWORD --env PANO_DB_PORT --env PANO_DB_USER \
    --env PANO_HOSTED --env PANO_HOST_API_URL --env PANO_HOST_INSTANCE_SECRET --env PANO_HOST_WORKLOAD_ID \
    --env PANO_JVM_ARGS --env PANO_SMTP_HOST --env PANO_SMTP_PASSWORD --env PANO_SMTP_PORT --env PANO_SMTP_USER \
    --memory 1024m --memory-swap 1024m --cpus 2.00 --pids-limit 512 \
    -p "127.0.0.1:$http_port:8088" "$1" >/dev/null
}

build_image() { # jre
  local image="$prefix-runtime:jre$1"
  docker build --platform linux/amd64 --build-arg "JRE=$1" -t "$image" "$here" >"$work/build-$1.log" 2>&1 \
    || { tail -20 "$work/build-$1.log"; exit 1; }
  last_image=$image
}

setup_api_up() { curl -fsS "$base/api/setup/step" >/dev/null || [ "$(curl -sS "$base/api/setup/step" | jq -r '.error // empty')" = PLATFORM_ALREADY_INSTALLED ]; }
cp_seen() { grep -c "\"path\": \"$1\", \"authed\": true" "$work/cp.log" || true; }

full=1
for jre in "${jres[@]}"; do
  echo "== pano-runtime:jre$jre (linux/amd64) $([ $full = 1 ] && echo 'full scenario' || echo smoke)"
  build_image "$jre"
  image=$last_image
  if [ $full = 1 ]; then
    docker run --rm --user 0 --network none --entrypoint sh -v "$data:/data" "$image" -c "chown -R $uid:$uid /data"
  fi
  run_pano "$image"

  if ! wait_for 240 setup_api_up; then
    fail "jre$jre: HTTP on 8088 (setup API) within 240 s"; docker logs --tail 60 "$pano" 2>&1; continue
  fi
  pass "jre$jre: HTTP on 8088 through the published port"
  check "jre$jre: natives unpack outside the noexec /tmp" wait_for 30 sh -c "docker exec '$pano' sh -c 'ls /data/.cache/natives | grep -q libjansi' && ! docker logs '$pano' 2>&1 | grep -q 'Failed to load native library'"
  check "jre$jre: JVM runs as uid $uid with the agent's heap" sh -c "docker top '$pano' -eo pid,uid,args | grep -q '^ *[0-9]* *$uid .*java .*-Xmx512m'"

  if [ $full = 1 ]; then
    conf=$(in_pano 'cat /data/config.conf')
    check "config: database host from env" grep -q "\"$db:3306\"" <<<"$conf"
    check "config: http-port 8088" grep -Eq 'http-port *[=:] *8088' <<<"$conf"
    check "config: SMTP relay from env" grep -q "\"$gateway\"" <<<"$conf"
    check "config: relay STARTTLS not required" not_matching 'starttls *[=:] *"?REQUIRED' "$conf"
    check "logs carry no secret values" no_secret_in_logs

    echo "  -- setup (scripts/smoke-install.sh)"
    if PANO_URL=$base PANO_DB_HOST=$db PANO_DB_PORT=3306 PANO_DB_NAME=pano_w PANO_DB_USER=pano_w PANO_DB_PASSWORD=$db_pass \
      SMOKE_TIMEOUT=240 SMOKE_ADMIN_PASSWORD=$admin_pass SMOKE_EXPECT_MANAGED_MAIL=1 "$root/scripts/smoke-install.sh"; then pass "setup through the API"; else
      fail "setup through the API"; docker logs --tail 80 "$pano" 2>&1; break; fi
    check "capabilities announced {ssoSupported} with the instance secret" wait_for 60 sh -c "grep -q '\"path\": \"/api/host/instance/capabilities\", \"authed\": true, \"keys\": \[\"ssoSupported\"\]' '$work/cp.log'"

    echo "  -- SSO"
    ticket=$(head -c 24 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
    curl -fsS -X POST --data "{\"ticket\":\"$ticket\",\"data\":{\"accountId\":\"acc-e2e-1\",\"email\":\"owner@e2e.invalid\",\"username\":\"owner\",\"role\":\"owner\",\"workloadId\":\"$workload\",\"hostname\":\"e2e.panomc.site\"}}" \
      "http://$gateway:$cp_port/_issue" >/dev/null
    sso=$(curl -sS -D - -o /dev/null -c "$work/sso.jar" "$base/panel/host-sso?t=$ticket")
    check "SSO: 302 to /panel" grep -qiE '^location: */panel\s*$' <<<"$sso"
    check "SSO: session cookies set" grep -qi '^set-cookie: *pano_auth_token' <<<"$sso"
    check "SSO: no-store" grep -qi '^cache-control: *no-store' <<<"$sso"
    again=$(curl -sS -D - -o /dev/null "$base/panel/host-sso?t=$ticket")
    check "SSO: a used ticket lands on /panel/login" grep -qiE '^location: */panel/login' <<<"$again"
    hosted=$(curl -sS -b "$work/sso.jar" "$base/api/panel/hosted")
    check "SSO session reaches GET /api/panel/hosted (hosted, workload id)" jq -e ".hosted == true and .workloadId == \"$workload\"" <<<"$hosted"
    check "GET /api/panel/hosted never returns the secret" not_contains "$hosted" "$secret"

    echo "  -- in-panel restart (exit 75)"
    curl -fsS -c "$work/admin.jar" -H 'Content-Type: application/json' \
      --data "{\"usernameOrEmail\":\"smokeadmin\",\"password\":\"$admin_pass\",\"panel\":true}" "$base/api/auth/login" >/dev/null \
      || fail "admin login"
    csrf=$(awk '$6 ~ /csrf_token/ {print $7}' "$work/admin.jar" | head -1)
    pid_before=$(docker top "$pano" -eo pid,args | awk '/java/ && /-jar/ {print $1; exit}')
    restart() {
      curl -fsS -b "$work/admin.jar" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
        --data "{\"password\":\"$admin_pass\"}" "$base/api/panel/settings/restart-pano" >/dev/null
    }
    check "restart request accepted" restart
    check "launcher relaunched after exit 75" wait_for 90 logs_have 'pano-launcher: planned restart (exit 75)'
    check "Pano serves again after the restart" wait_for 240 serves /panel/_app/version.json 200
    pid_after=$(docker top "$pano" -eo pid,args | awk '/java/ && /-jar/ {print $1; exit}')
    check "a new JVM process in the same container" test -n "$pid_after" -a "$pid_after" != "$pid_before"
    check "container RestartCount stays 0" test "$(restart_count)" = 0

    echo "  -- simulated self-update through /data/.pano-jar"
    in_pano 'cp /data/Pano-e2e-a.jar /data/Pano-e2e-b.jar && printf Pano-e2e-b.jar > /data/.pano-jar.tmp && mv /data/.pano-jar.tmp /data/.pano-jar'
    check "restart request accepted" restart
    check "launcher runs the jar the pointer names" wait_for 90 sh -c "docker top '$pano' -eo pid,args | grep -q 'Pano-e2e-b.jar'"
    check "Pano serves after the update" wait_for 240 serves /panel/_app/version.json 200
    check "container RestartCount stays 0 after the update" test "$(restart_count)" = 0
    check "container never stopped" running

    echo "  -- local Pano backup (taken before the mail user exists)"
    login() {
      rm -f "$work/admin.jar"
      curl -fsS -c "$work/admin.jar" -H 'Content-Type: application/json' \
        --data "{\"usernameOrEmail\":\"smokeadmin\",\"password\":\"$admin_pass\",\"panel\":true}" "$base/api/auth/login" >/dev/null &&
        csrf=$(awk '$6 ~ /csrf_token/ {print $7}' "$work/admin.jar" | head -1)
    }
    panel() { # method path [body]
      curl -sS -X "$1" -b "$work/admin.jar" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' ${3:+--data "$3"} "$base$2"
    }
    job_status() { panel GET /api/panel/pano-backups/job | jq -r '.job.status // empty'; }
    job_done() { [ "$(job_status)" = DONE ]; }
    check "admin login after the update" login
    backup=$(panel POST /api/panel/pano-backups '{}')
    check "backup job started" jqok '.result == "ok" and .job.type == "CREATE"' <<<"$backup"
    check "backup job finished" wait_for 180 job_done
    backup_id=$(panel GET /api/panel/pano-backups/job | jq -r '.job.backupId // empty')
    check "backup listed" sh -c "test -n '$backup_id'"

    echo "  -- mail through the Portal mail relay (no STARTTLS)"
    mail_user=e2email$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')
    mail_to="$mail_user@example.com"
    curl -sS -H 'Content-Type: application/json' \
      --data "{\"username\":\"$mail_user\",\"email\":\"$mail_to\",\"password\":\"$admin_pass\",\"passwordRepeat\":\"$admin_pass\",\"agreement\":true}" \
      "$base/api/auth/register" >"$work/register.json"
    check "a new player registers" jqok '.result == "ok"' "$work/register.json"
    check "the player exists before the restore" jqok '.result == "ok"' <<<"$(panel GET "/api/panel/players/$mail_user/exists")"
    # registration mails only when verification is required; the panel's resend always mails an unverified player
    panel POST "/api/panel/players/$mail_user/verificationMail" >"$work/verify.json"
    relayed() { grep -F "\"$mail_to\"" "$work/relay.log" | grep -q '"user": "pano_w"'; }
    check "activation mail reached the relay with the env credentials" wait_for 60 relayed
    check "Pano never asked the relay for STARTTLS" not_contains "$(cat "$work/relay.log")" '"starttls": true'
    check "no mail error logged" sh -c "! docker logs '$pano' 2>&1 | grep -Eqi 'SMTPException|Failed to send mail|STARTTLS is required'"

    echo "  -- restore of the local backup (post-restore restart = exit 75)"
    relaunches() { docker logs "$pano" 2>&1 | grep -c 'pano-launcher: planned restart (exit 75)' || true; }
    before=$(relaunches)
    restore=$(panel POST "/api/panel/pano-backups/$backup_id/restore" "{\"currentPassword\":\"$admin_pass\"}")
    check "restore accepted" jqok '.result == "ok" and .job.type == "RESTORE"' <<<"$restore"
    relaunched() { [ "$(relaunches)" -gt "$before" ]; }
    check "launcher relaunched after the restore (exit 75)" wait_for 240 relaunched
    check "Pano serves after the restore" wait_for 240 serves /panel/_app/version.json 200
    check "admin login after the restore" wait_for 60 login
    check "the restored database predates the mail user" test "$(panel GET "/api/panel/players/$mail_user/exists" | jq -r '.error // empty')" = NOT_EXISTS
    check "container RestartCount stays 0 after the restore" test "$(restart_count)" = 0
    check "the jar pointer survives the restore" sh -c "docker exec '$pano' cat /data/.pano-jar | grep -qx 'Pano-e2e-b.jar'"
    check "container never stopped (restore)" running
  else
    check "jre$jre: installed instance serves /" wait_for 240 serves / 200
    check "jre$jre: installed instance serves panel-ui" wait_for 120 serves /panel/_app/version.json 200
    check "jre$jre: runs the updated jar" sh -c "docker top '$pano' -eo pid,args | grep -q 'Pano-e2e-b.jar'"
  fi

  echo "  -- stop"
  started=$(date +%s)
  docker stop "$pano" >/dev/null
  took=$(($(date +%s) - started))
  code=$(docker inspect -f '{{.State.ExitCode}}' "$pano")
  check "jre$jre: docker stop ends within the stop timeout (${took}s, exit $code)" test "$took" -lt 30 -a "$code" != 137
  docker rm "$pano" >/dev/null
  full=0
done

echo
if [ "$failures" -eq 0 ]; then echo "e2e: all checks passed"; else echo "e2e: $failures check(s) FAILED"; exit 1; fi
