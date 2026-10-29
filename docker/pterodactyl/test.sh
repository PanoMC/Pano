#!/bin/bash
# Runs the egg the way Wings does, without a panel: the install script in the installer image against a
# fresh server folder, then the yolk with STARTUP / SERVER_PORT / the egg variables as a non-root user,
# MariaDB next to it. Passes when Pano prints the egg's "done" line, answers HTTP on the allocation
# port and stops on the console's "stop". A second install over the same folder must keep its data,
# and a start on another allocation must move Pano there.
#   bash docker/pterodactyl/test.sh [java_11|java_17|java_21|java_25] [version]
#   PANO_TEST_JAR=build/libs/Pano-<version>.jar bash docker/pterodactyl/test.sh   # run a local build
set -euo pipefail
dir=$(cd "$(dirname "$0")" && pwd)
yolk=${1:-java_11}
version=${2:-latest}
prefix=${PH_PREFIX:-ph-ptero}
port=${PANO_TEST_PORT:-18099}
uid=988
work=$(mktemp -d)
net="$prefix-net"
db="$prefix-db"
fifo="$work/stdin"

cleanup() {
  exec 4>&- 2>/dev/null || true
  docker rm -f "$prefix-pano" "$db" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  docker run --rm -v "$work:/w" alpine rm -rf /w/server >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

bash "$dir/build-egg.sh" --check
script=$(jq -r .scripts.installation.script "$dir/egg-pano.json")
startup=$(jq -r .startup "$dir/egg-pano.json")
done_line=$(jq -r '.config.startup | fromjson | .done' "$dir/egg-pano.json")

docker network create "$net" >/dev/null
docker run -d --name "$db" --network "$net" -e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=pano \
  -e MARIADB_USER=pano -e MARIADB_PASSWORD=pano mariadb:11 >/dev/null

install() {
  docker run --rm -v "$work/server:/mnt/server" -e PANO_VERSION="$version" -e PANO_CHANNEL=release \
    ghcr.io/pterodactyl/installers:debian bash -c "$script"
  # Wings hands the files to its own user after the install
  docker run --rm -v "$work/server:/s" alpine chown -R "$uid:$uid" /s
}

echo "== install"
install
[ -s "$work/server/.pano-jar" ]
if [ -n "${PANO_TEST_JAR:-}" ]; then
  # a local build instead of the release: same layout as a self-update leaves it
  name=$(basename "$PANO_TEST_JAR")
  docker run --rm -v "$work/server:/s" -v "$(realpath "$PANO_TEST_JAR"):/in.jar:ro" alpine \
    sh -c "cp /in.jar /s/$name && printf '%s' $name >/s/.pano-jar && chown -R $uid:$uid /s"
fi
jar=$(cat "$work/server/.pano-jar")
echo "installed $jar"

start_and_check() { # port
  local port=$1
  echo "== start ($yolk, port $port)"
  rm -f "$fifo"; mkfifo "$fifo"
  docker run --name "$prefix-pano" --network "$net" -i --user "$uid:$uid" -m "${PANO_TEST_MEMORY:-1024}m" \
    -v "$work/server:/home/container" -p "127.0.0.1:$port:$port" \
    -e STARTUP="$startup" -e SERVER_PORT="$port" -e SERVER_MEMORY="${PANO_TEST_MEMORY:-1024}" -e JAVA_RAM_PERCENT=50 \
    -e PANO_VERSION="$version" -e PANO_CHANNEL=release \
    -e PANO_DB_HOST="$db" -e PANO_DB_PORT=3306 -e PANO_DB_NAME=pano -e PANO_DB_USER=pano -e PANO_DB_PASSWORD=pano \
    "ghcr.io/pterodactyl/yolks:$yolk" <"$fifo" >"$work/console.log" 2>&1 &
  run_pid=$!
  exec 4>"$fifo"

  for _ in $(seq 1 180); do
    grep -qF "$done_line" "$work/console.log" && break
    kill -0 "$run_pid" 2>/dev/null || { cat "$work/console.log"; echo "FAIL: server exited before it was ready" >&2; exit 1; }
    sleep 1
  done
  grep -qF "$done_line" "$work/console.log" || { tail -50 "$work/console.log"; echo "FAIL: no \"$done_line\" in 180 s" >&2; exit 1; }
  echo "ready: $(grep -F "$done_line" "$work/console.log" | tail -1)"

  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "http://127.0.0.1:$port/")
  echo "GET / -> $code"
  case "$code" in 2*|3*) ;; *) tail -30 "$work/console.log"; echo "FAIL: HTTP $code" >&2; exit 1 ;; esac

  docker stats --no-stream --format "memory: {{.MemUsage}}" "$prefix-pano"
  docker exec "$prefix-pano" bash -c 'for p in /proc/[0-9]*; do r=$(awk "/VmRSS/{print \$2}" $p/status 2>/dev/null); [ -n "$r" ] && echo "$((r/1024)) MB  $(tr "\0" " " <$p/cmdline | cut -c1-70)"; done | sort -rn | head -6' || true
  echo "-- stop"
  echo stop >&4
  for _ in $(seq 1 60); do kill -0 "$run_pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$run_pid" 2>/dev/null; then tail -30 "$work/console.log"; echo "FAIL: still running 60 s after stop" >&2; exit 1; fi
  wait "$run_pid" && rc=0 || rc=$?
  echo "exit code $rc"
  exec 4>&-
  docker rm -f "$prefix-pano" >/dev/null
  grep -Eq "http-port[[:space:]]*=[[:space:]]*$port\b" "$work/server/config.conf" || {
    grep -n http-port "$work/server/config.conf"; echo "FAIL: config.conf has no http-port=$port" >&2; exit 1; }
}

start_and_check "$port"

echo "== reinstall keeps data"
touch "$work/server/plugins/.keep-test" 2>/dev/null || docker run --rm -v "$work/server:/s" alpine sh -c "mkdir -p /s/plugins && touch /s/plugins/.keep-test"
install
[ -f "$work/server/config.conf" ] && [ -f "$work/server/plugins/.keep-test" ]
[ -n "${PANO_TEST_JAR:-}" ] || [ "$(cat "$work/server/.pano-jar")" = "$jar" ]
if [ -n "${PANO_TEST_JAR:-}" ]; then
  docker run --rm -v "$work/server:/s" alpine sh -c "printf '%s' $jar >/s/.pano-jar && chown -R $uid:$uid /s"
fi
echo "== new allocation after the reinstall"
start_and_check "$((port + 1))"
echo "PASS ($yolk, $jar)"
