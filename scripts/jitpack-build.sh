#!/usr/bin/env bash
# JitPack install step (see jitpack.yml): builds the Pano artifact and installs it into the local
# Maven repository, where JitPack picks it up as com.github.panomc:pano:<tag>.
#
# JitPack moved some tags onto a newer build image whose checkout could not run ./gradlew
# ("Could not find or load main class org.gradle.wrapper.GradleWrapperMain"), so this does not trust
# the wrapper blindly: it probes it, restores the jar from git, and as a last resort runs a Gradle
# installed with SDKMAN. It also makes sure a JDK 11 exists, because the Pano module compiles with
# a Java 11 toolchain while Gradle itself runs on the JDK JitPack selected (21).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

wrapper_jar=gradle/wrapper/gradle-wrapper.jar
gradle_version="$(sed -n 's#.*gradle-\([0-9][^/]*\)-bin\.zip.*#\1#p' gradle/wrapper/gradle-wrapper.properties | head -n1)"

# The JDK Gradle must run on, captured before SDKMAN can move its "current" candidate.
orig_java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
orig_path="$PATH"

sdk_init() {
  if ! command -v sdk >/dev/null 2>&1; then
    # shellcheck disable=SC1091
    set +u
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    set -u
  fi
}

restore_java() {
  export JAVA_HOME="$orig_java_home"
  export PATH="$orig_java_home/bin:$orig_path"
}

# Gradle's toolchain detection finds /usr/lib/jvm and SDKMAN installs; install a JDK 11 only when
# there is none, and tell Gradle where it is either way.
jdk11="$(ls -d "$HOME"/.sdkman/candidates/java/11* /usr/lib/jvm/*11* /usr/lib/jvm/*/11* 2>/dev/null | head -n1 || true)"
if [ -z "$jdk11" ]; then
  echo "jitpack-build: no JDK 11 found, installing one with SDKMAN"
  sdk_init
  sdk install java 11.0.2-open </dev/null || true
  jdk11="$(ls -d "$HOME"/.sdkman/candidates/java/11* 2>/dev/null | head -n1 || true)"
  restore_java
fi
gradle_args=(--console=plain --stacktrace)
if [ -n "$jdk11" ]; then
  gradle_args+=("-Porg.gradle.java.installations.paths=$jdk11")
fi

gradle_cmd=(./gradlew)
if ! ./gradlew --version >/dev/null 2>&1; then
  echo "jitpack-build: the Gradle wrapper does not start, restoring $wrapper_jar from git"
  git checkout -- "$wrapper_jar" 2>/dev/null || true
  if ! ./gradlew --version >/dev/null 2>&1; then
    echo "jitpack-build: the wrapper is still unusable, using Gradle $gradle_version from SDKMAN"
    sdk_init
    sdk install gradle "$gradle_version" </dev/null || true
    restore_java
    gradle_cmd=(gradle)
  fi
fi

echo "jitpack-build: ${gradle_cmd[*]} publishPano (JAVA_HOME=$JAVA_HOME, JDK 11 toolchain=${jdk11:-none})"
exec "${gradle_cmd[@]}" "${gradle_args[@]}" publishPano
