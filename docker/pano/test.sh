#!/usr/bin/env bash
# Tests the ghcr.io/panomc/pano image (pano-seed on top of pano-runtime) with stand-in release jars.
#   docker/pano/test.sh              (builds linux/amd64 only, runtime jre11)
# Needs docker and a host JDK >= 11 (javac/jar, JAVA_HOME honoured). Resources are named ph-w4-*.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
prefix=${PH_PREFIX:-ph-w4}
javabin=${JAVA_HOME:+$JAVA_HOME/bin/}
work=$(mktemp -d)
data="$work/data"
runtime="$prefix-runtime:jre11"
image="$prefix-pano:test"
container="$prefix-pano-test"
failures=0

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  if [ -d "$data" ]; then
    docker run --rm --user 0 --entrypoint sh -v "$data:/data" "$runtime" -c 'rm -rf /data/* /data/.[!.]*' >/dev/null 2>&1 || true
  fi
  rm -rf "$work" 2>/dev/null || true
  docker rmi -f "$image" "$prefix-pano:bad" "$runtime" >/dev/null 2>&1 || true
}
trap cleanup EXIT

pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; failures=$((failures + 1)); }
check() { local name=$1; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }

# ---- stand-in jar: logs its own name and exits 0 --------------------------------------------------
mkdir -p "$work/src"
cat > "$work/src/StandIn.java" <<'JAVA'
import java.nio.file.*;
public class StandIn {
    public static void main(String[] args) throws Exception {
        String jar = Paths.get(StandIn.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
        Files.write(Paths.get("/data/launches.log"), (jar + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
JAVA
"${javabin}javac" --release 11 -d "$work/classes" "$work/src/StandIn.java"
"${javabin}jar" --create --file "$work/standin.jar" --main-class StandIn -C "$work/classes" .

build_pano() { # $1 tag, $2... jar names to bundle; a .sha256 is written for the first one
  local tag=$1; shift
  rm -rf "$work/ctx"; mkdir -p "$work/ctx/release"
  cp "$here/Dockerfile" "$here/pano-seed.sh" "$work/ctx/"
  for j in "$@"; do cp "$work/standin.jar" "$work/ctx/release/$j"; done
  (cd "$work/ctx/release" && sha256sum "$1" > "$1.sha256")
  docker build --platform linux/amd64 --build-arg "RUNTIME=$runtime" -t "$tag" "$work/ctx" >"$work/build.log" 2>&1
}

reset_data() { # $@ = jars to put in /data
  if [ -d "$data" ]; then docker run --rm --user 0 --entrypoint sh -v "$data:/data" "$runtime" -c 'rm -rf /data/* /data/.[!.]*' >/dev/null; fi
  mkdir -p "$data"; chmod 0777 "$data"
  for j in "$@"; do cp "$work/standin.jar" "$data/$j"; done
}

hardened=(--user 10007:10007 --cap-drop ALL --security-opt no-new-privileges --read-only
  --tmpfs /tmp:rw,nosuid,nodev,size=64m,mode=1777 --mount "type=bind,source=$data,target=/data"
  --memory 1g --memory-swap 1g --cpus 2 --pids-limit 256)

run_fg() { docker run --rm --name "$container" "${hardened[@]}" "$image" >"$work/out.log" 2>&1; }
readf() { cat "$data/$1" 2>/dev/null || true; }
last_launch() { tail -n1 "$data/launches.log" 2>/dev/null || true; }

echo "== building $runtime (linux/amd64)"
docker build --platform linux/amd64 --build-arg JRE=11 -t "$runtime" "$here/../runtime" >"$work/build.log" 2>&1 \
  || { cat "$work/build.log"; exit 1; }

echo "== building $image with Pano-1.0.0.jar"
build_pano "$image" Pano-1.0.0.jar || { cat "$work/build.log"; exit 1; }

reset_data
set +e; run_fg; code=$?; set -e
check "first start seeds the bundled jar and runs it (exit $code)" \
  test "$code" = 0 -a "$(readf .pano-jar)" = Pano-1.0.0.jar -a "$(readf .pano-image-jar)" = Pano-1.0.0.jar -a "$(last_launch)" = Pano-1.0.0.jar
check "seeding is logged" grep -q 'installed the bundled Pano-1.0.0.jar' "$work/out.log"

set +e; run_fg; code=$?; set -e
check "second start keeps /data and does not reseed" \
  test "$code" = 0 -a "$(last_launch)" = Pano-1.0.0.jar -a "$(grep -c installed "$work/out.log")" = 0

# an in-panel self-update moved the pointer (JarPointer.stage)
docker run --rm --user 0 --entrypoint sh -v "$data:/data" "$runtime" -c \
  'cp /data/Pano-1.0.0.jar /data/Pano-1.1.0.jar && printf Pano-1.1.0.jar > /data/.pano-jar && printf Pano-1.0.0.jar > /data/.pano-jar.previous'
set +e; run_fg; code=$?; set -e
check "a self-update survives restarts of the same image" test "$code" = 0 -a "$(last_launch)" = Pano-1.1.0.jar

echo "== rebuilding $image with Pano-2.0.0.jar"
build_pano "$image" Pano-2.0.0.jar || { cat "$work/build.log"; exit 1; }
set +e; run_fg; code=$?; set -e
check "a new image installs its release (exit $code)" \
  test "$code" = 0 -a "$(readf .pano-jar)" = Pano-2.0.0.jar -a "$(readf .pano-jar.previous)" = Pano-1.1.0.jar -a "$(last_launch)" = Pano-2.0.0.jar
check "older jars are pruned, the previous one is kept" \
  test ! -e "$data/Pano-1.0.0.jar" -a -f "$data/Pano-1.1.0.jar" -a -f "$data/Pano-2.0.0.jar"

reset_data Pano-2.0.0.jar
printf 'Pano-gone.jar' > "$data/.pano-jar"
set +e; run_fg; code=$?; set -e
check "a pointer to a missing jar is repaired" test "$code" = 0 -a "$(readf .pano-jar)" = Pano-2.0.0.jar

reset_data
chmod 0755 "$data"
set +e; run_fg; code=$?; set -e
check "an unwritable volume exits 73 with a hint (got $code)" test "$code" = 73 && grep -q 'not writable' "$work/out.log"

reset_data
set +e; docker run --rm --name "$container" --memory 1g --memory-swap 1g --cpus 2 -v "$data:/data" "$image" >"$work/out.log" 2>&1; code=$?; set -e
check "default user (image uid) works" test "$code" = 0 -a "$(last_launch)" = Pano-2.0.0.jar

set +e; build_pano "$prefix-pano:bad" Pano-3.0.0.jar Pano-3.0.1.jar; code=$?; set -e
check "a build with two release jars fails" test "$code" != 0

rm -rf "$work/ctx"; mkdir -p "$work/ctx/release"; cp "$here/Dockerfile" "$here/pano-seed.sh" "$work/ctx/"
cp "$work/standin.jar" "$work/ctx/release/Pano-4.0.0.jar"
echo "0000000000000000000000000000000000000000000000000000000000000000  Pano-4.0.0.jar" > "$work/ctx/release/Pano-4.0.0.jar.sha256"
set +e; docker build --platform linux/amd64 --build-arg "RUNTIME=$runtime" -t "$prefix-pano:bad" "$work/ctx" >"$work/build.log" 2>&1; code=$?; set -e
check "a build whose jar fails its .sha256 fails" test "$code" != 0

if [ "$failures" -gt 0 ]; then echo "$failures test(s) failed"; exit 1; fi
echo "all pano image tests passed"
