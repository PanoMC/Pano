#!/bin/bash
# Pano launcher for Pterodactyl / Pelican (the egg's startup command runs it from /home/container).
# Same semantics as docker/runtime/pano-launcher.sh: the jar is named by .pano-jar (re-read on every
# start, which also puts Pano in container mode), stdin is kept for the console, SIGTERM/SIGINT are
# forwarded and the child's exit code is returned, exit 75 = planned restart / self-update -> relaunch
# in place. Pterodactyl itself would treat exit 75 as a stop.
set -f
cd /home/container || exit 1
exec 3<&0

data=$PWD
port=${SERVER_PORT:-8088}
percent=${JAVA_RAM_PERCENT:-50}
# The heap comes from the panel's memory limit, not the JVM's container detection: Java 11 before
# 11.0.16 (the java_11 yolk ships 11.0.13) does not see cgroup v2 limits and sizes the heap from the
# host's RAM, and starts with the whole heap committed unless -Xms is set. SERVER_MEMORY=0 means unlimited.
if [ "${SERVER_MEMORY:-0}" -gt 0 ] 2>/dev/null; then
  jvm_args=${PANO_JVM_ARGS:-"-Xms64M -Xmx$((SERVER_MEMORY * percent / 100))M"}
else
  jvm_args=${PANO_JVM_ARGS:-"-XX:MaxRAMPercentage=$percent"}
fi

# Pano reads PANO_HTTP_PORT only when it creates config.conf; after that the allocation can still change.
export PANO_HTTP_PORT=$port
if [ -f "$data/config.conf" ]; then
  sed -i "s/^\([[:space:]]*\)http-port[[:space:]]*=.*/\1http-port=$port/" "$data/config.conf"
fi

# jansi / jline unpack their natives next to the app instead of /tmp
natives="$data/.cache/natives"

stop=0
pid=
trap 'stop=1; [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null' TERM INT

while :; do
  # a stop that lands between a planned exit and the relaunch ends the server
  [ "$stop" = 1 ] && exit 0
  jar=$(cat "$data/.pano-jar" 2>/dev/null)
  case "$jar" in
    ''|*/*|.|..) jar= ;;
  esac
  if [ -z "$jar" ] || [ ! -f "$data/$jar" ]; then
    echo "pano: .pano-jar does not name a jar, reinstall the server" >&2
    exit 64
  fi
  mkdir -p "$natives" 2>/dev/null || true
  # shellcheck disable=SC2086 # PANO_JVM_ARGS is a space separated list, globbing is off (set -f)
  java "-Duser.home=$data" "-Djansi.tmpdir=$natives" "-Djline.tmpdir=$natives" $jvm_args -jar "$data/$jar" -nogui <&3 &
  pid=$!
  wait "$pid"; code=$?
  while [ "$stop" = 1 ] && kill -0 "$pid" 2>/dev/null; do wait "$pid"; code=$?; done
  [ "$stop" = 1 ] && exit "$code"
  [ "$code" = 75 ] || exit "$code"
  pid=
  echo "pano: planned restart (exit 75)" >&2
done
