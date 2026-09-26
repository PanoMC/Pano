#!/bin/sh
# Pano runtime launcher (PID under tini). Same semantics as the Portal agent's shell launcher
# (ContainerPlan.LAUNCHER): the jar is named by /data/.pano-jar (file name only, re-read on every start),
# stdin is kept for the console, SIGTERM/SIGINT are forwarded and the child's exit code is returned,
# exit 75 = planned restart -> relaunch in place. JVM args come from PANO_JVM_ARGS (space separated,
# already sanitised by the agent); without it the heap defaults to 75% of the container memory.
set -f
exec 3<&0

data=${PANO_DATA_DIR:-/data}
bun_version=${PANO_BUN_VERSION:-bun-v1.4.2}
jvm_args=${PANO_JVM_ARGS:-"-XX:MaxRAMPercentage=75"}

seed_bun() {
  # Pano looks for libraries/<bun version> and downloads it when missing; point it at the bundled one.
  [ -x /opt/pano/bun ] || return 0
  link="$data/libraries/$bun_version"
  [ -e "$link" ] && return 0
  mkdir -p "$data/libraries" 2>/dev/null || return 0
  rm -f "$link" 2>/dev/null
  ln -s /opt/pano/bun "$link" 2>/dev/null || true
}

# The agent mounts /tmp noexec: jansi / jline unpack their natives next to the app instead.
natives="$data/.cache/natives"

stop=0
pid=
trap 'stop=1; [ -n "$pid" ] && kill -TERM "$pid" 2>/dev/null' TERM INT

while :; do
  # a stop that lands between a planned exit and the relaunch ends the container
  [ "$stop" = 1 ] && exit 0
  jar=$(cat "$data/.pano-jar" 2>/dev/null)
  case "$jar" in
    ''|*/*|.|..) jar= ;;
  esac
  if [ -z "$jar" ] || [ ! -f "$data/$jar" ]; then
    echo "pano-launcher: $data/.pano-jar does not name a jar" >&2
    exit 64
  fi
  seed_bun
  mkdir -p "$natives" 2>/dev/null || true
  # shellcheck disable=SC2086 # PANO_JVM_ARGS is a space separated list, globbing is off (set -f)
  # arbitrary --user uids have no passwd entry, so the JVM would report user.home as "?"
  java "-Duser.home=${HOME:-$data}" "-Djansi.tmpdir=$natives" "-Djline.tmpdir=$natives" $jvm_args -jar "$data/$jar" -nogui <&3 &
  pid=$!
  wait "$pid"; code=$?
  while [ "$stop" = 1 ] && kill -0 "$pid" 2>/dev/null; do wait "$pid"; code=$?; done
  [ "$stop" = 1 ] && exit "$code"
  [ "$code" = 75 ] || exit "$code"
  pid=
  echo "pano-launcher: planned restart (exit 75)" >&2
done
