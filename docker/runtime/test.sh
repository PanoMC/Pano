#!/usr/bin/env bash
# Tests the pano-runtime image + launcher with stand-in jars under the Portal agent's hardening flags.
#   docker/runtime/test.sh [JRE...]      (default: 11 21; builds linux/amd64 only)
# Needs docker and a host JDK >= 11 (javac/jar, JAVA_HOME honoured). Resources are named ph-w4-*.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
jres=("${@:-11}")
[ $# -eq 0 ] && jres=(11 21)
prefix=${PH_PREFIX:-ph-w4}
javabin=${JAVA_HOME:+$JAVA_HOME/bin/}
work=$(mktemp -d)
data="$work/data"
image=
failures=0
container="$prefix-runtime-test"

cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  # files in /data belong to the container uid: remove them from inside
  if [ -n "$image" ] && [ -d "$data" ]; then
    docker run --rm --user 0 --entrypoint sh -v "$data:/data" "$image" -c 'rm -rf /data/* /data/.[!.]*' >/dev/null 2>&1 || true
  fi
  rm -rf "$work" 2>/dev/null || true
  for jre in "${jres[@]}"; do docker rmi -f "$prefix-runtime:jre$jre" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*"; failures=$((failures + 1)); }
check() { local name=$1; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }

# ---- stand-in jars -------------------------------------------------------------------------------
mkdir -p "$work/src"
cat > "$work/src/StandIn.java" <<'JAVA'
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;

public class StandIn {
    public static void main(String[] args) throws Exception {
        String jar = Paths.get(StandIn.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getFileName().toString();
        Path data = Paths.get("/data");
        String mode = new String(Files.readAllBytes(data.resolve("mode"))).trim();
        String line = jar + " args=" + String.join(",", args) + " jvm=" + String.join(",", ManagementFactory.getRuntimeMXBean().getInputArguments())
                + " home=" + System.getProperty("user.home") + " cwd=" + System.getProperty("user.dir") + "\n";
        Files.write(data.resolve("launches.log"), line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        switch (mode) {
            case "restart":
                if (jar.equals("Pano-a.jar")) {
                    Files.write(data.resolve(".pano-jar.tmp"), "Pano-b.jar".getBytes());
                    Files.move(data.resolve(".pano-jar.tmp"), data.resolve(".pano-jar"), StandardCopyOption.ATOMIC_MOVE);
                    System.exit(75);
                }
                System.exit(0);
            case "echo":
                String in = new BufferedReader(new InputStreamReader(System.in)).readLine();
                Files.write(data.resolve("echo.out"), ("ECHO:" + in).getBytes());
                System.exit(0);
            case "bun":
                Process p = new ProcessBuilder("libraries/bun-v1.4.2", "--version").redirectErrorStream(true).start();
                String out = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
                Files.write(data.resolve("bun.out"), (p.waitFor() + ":" + out).getBytes());
                System.exit(0);
            case "wait":
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { Files.write(data.resolve("stopped"), "yes".getBytes()); } catch (IOException ignored) { }
                    Runtime.getRuntime().halt(0);
                }));
                Files.write(data.resolve("ready"), "yes".getBytes());
                Thread.sleep(Long.MAX_VALUE);
            default:
                System.exit(Integer.parseInt(mode));
        }
    }
}
JAVA
"${javabin}javac" --release 11 -d "$work/classes" "$work/src/StandIn.java"
for j in a b; do "${javabin}jar" --create --file "$work/Pano-$j.jar" --main-class StandIn -C "$work/classes" .; done

# ---- helpers -------------------------------------------------------------------------------------
reset_data() { # $1 = mode, $2 = pointer ("" = none)
  if [ -d "$data" ]; then docker run --rm --user 0 --entrypoint sh -v "$data:/data" "$image" -c 'rm -rf /data/* /data/.[!.]*' >/dev/null; fi
  mkdir -p "$data"; chmod 0777 "$data"
  cp "$work/Pano-a.jar" "$work/Pano-b.jar" "$data/"
  printf '%s' "$1" > "$data/mode"
  if [ -n "${2-}" ]; then printf '%s\n' "$2" > "$data/.pano-jar"; fi
}

# the agent's ContainerPlan flags (runtimeLauncher=image: no command, no entrypoint override)
hardened=(--init --user 10007:10007 --cap-drop ALL --security-opt no-new-privileges --read-only
  --tmpfs /tmp:rw,nosuid,nodev,size=64m,mode=1777 --mount "type=bind,source=$data,target=/data"
  --workdir /data -e HOME=/data --memory 1g --memory-swap 1g --cpus 2 --pids-limit 256)

run_fg() { # extra docker args... ; echoes nothing, returns container exit code
  docker run --rm --name "$container" "${hardened[@]}" "$@" "$image" >"$work/out.log" 2>&1
}

launches() { [ -f "$data/launches.log" ] && wc -l < "$data/launches.log" | tr -d ' ' || echo 0; }

wait_for() { local f=$1 i; for i in $(seq 1 100); do [ -f "$f" ] && return 0; sleep 0.1; done; return 1; }

# ---- tests ---------------------------------------------------------------------------------------
for jre in "${jres[@]}"; do
  image="$prefix-runtime:jre$jre"
  echo "== building $image (linux/amd64)"
  docker build --platform linux/amd64 --build-arg "JRE=$jre" -t "$image" "$here" >"$work/build-$jre.log" 2>&1 \
    || { cat "$work/build-$jre.log"; exit 1; }
  echo "== testing $image"

  reset_data restart Pano-a.jar
  set +e; run_fg; code=$?; set -e
  check "exit 75 relaunches in place and re-reads the pointer (exit $code)" \
    test "$code" = 0 -a "$(cut -d' ' -f1 "$data/launches.log" | tr '\n' ' ')" = "Pano-a.jar Pano-b.jar "
  check "planned restart is logged" grep -q 'planned restart (exit 75)' "$work/out.log"
  check "jar runs with -nogui and never -bg" grep -q 'args=-nogui ' "$data/launches.log"
  check "default heap is 75% of the container" grep -q 'jvm=-Duser.home=/data,-XX:MaxRAMPercentage=75 ' "$data/launches.log"
  check "user.home and workdir are /data (uid without passwd entry)" grep -q 'home=/data cwd=/data' "$data/launches.log"

  reset_data 42 Pano-a.jar
  set +e; run_fg; code=$?; set -e
  check "child exit code is returned (42)" test "$code" = 42

  reset_data 0 ""
  set +e; run_fg; code=$?; set -e
  check "missing pointer exits 64 (got $code)" test "$code" = 64 -a "$(launches)" = 0

  reset_data 0 Pano-missing.jar
  set +e; run_fg; code=$?; set -e
  check "pointer to a missing jar exits 64 (got $code)" test "$code" = 64

  reset_data 0 ../etc/passwd
  set +e; run_fg; code=$?; set -e
  check "pointer with a path exits 64 (got $code)" test "$code" = 64

  reset_data echo Pano-a.jar
  set +e; echo "say hello" | docker run --rm -i --name "$container" "${hardened[@]}" "$image" >"$work/out.log" 2>&1; code=$?; set -e
  check "stdin reaches the jar (console)" test "$code" = 0 -a "$(cat "$data/echo.out" 2>/dev/null)" = "ECHO:say hello"

  reset_data 0 Pano-a.jar
  set +e; run_fg -e "PANO_JVM_ARGS=-Xmx123m -Dpano.test=1"; code=$?; set -e
  check "PANO_JVM_ARGS replaces the default heap" \
    grep -q 'jvm=-Duser.home=/data,-Xmx123m,-Dpano.test=1 ' "$data/launches.log"

  reset_data bun Pano-a.jar
  set +e; run_fg; code=$?; set -e
  check "bundled Bun is seeded as libraries/bun-v1.4.2" \
    test "$code" = 0 -a "$(cat "$data/bun.out" 2>/dev/null)" = "0:1.4.2" -a -L "$data/libraries/bun-v1.4.2"

  reset_data wait Pano-a.jar
  docker run -d -i --name "$container" "${hardened[@]}" "$image" >/dev/null
  wait_for "$data/ready" || true
  start=$(date +%s)
  docker stop -t 20 "$container" >/dev/null
  took=$(( $(date +%s) - start ))
  code=$(docker inspect -f '{{.State.ExitCode}}' "$container")
  docker rm -f "$container" >/dev/null
  check "SIGTERM reaches the JVM and stops it cleanly (exit $code in ${took}s)" \
    test "$code" = 0 -a -f "$data/stopped" -a "$took" -lt 15 -a "$(launches)" = 1

  reset_data 0 Pano-a.jar
  set +e; docker run --rm --name "$container" --memory 1g --memory-swap 1g --cpus 2 -v "$data:/data" "$image" >"$work/out.log" 2>&1; code=$?; set -e
  check "default user (no --init, image uid) works" test "$code" = 0 -a "$(launches)" = 1
done

if [ "$failures" -gt 0 ]; then echo "$failures test(s) failed"; exit 1; fi
echo "all runtime tests passed"
