#!/bin/bash
# Pano egg install script (Pterodactyl / Pelican). Runs as root in ghcr.io/pterodactyl/installers:debian
# with the server's files at /mnt/server, on install and on every reinstall. It downloads the Pano jar
# (PANO_VERSION, or the newest of PANO_CHANNEL), checks its sha256 and points .pano-jar at it, the jar the
# startup command runs. Data (config.conf, plugins, themes, uploads) is never touched, so a reinstall
# is an upgrade.
set -euo pipefail

API_URL=${PANO_API_URL:-https://api.panomc.com}
VERSION=${PANO_VERSION:-latest}
CHANNEL=${PANO_CHANNEL:-release}
[ "$VERSION" = latest ] && VERSION=

mkdir -p /mnt/server
cd /mnt/server

json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }

# The release list answers {"data":{"release":[...],"beta":[...],"alpha":[...]}}, newest first.
# One release object per line, keeping the channel it came from in front of it.
releases=$(curl -fsSL "$API_URL/releases/platform" | sed 's/"\(release\|beta\|alpha\)":\[/\n@\1\n/g' | tr '{' '\n' |
  awk '/^@/ { channel = substr($0, 2); next } /"releaseTag"/ { print channel " " $0 }') || releases=

if [ -n "$VERSION" ]; then
  line=$(printf '%s\n' "$releases" | grep -F "\"releaseTag\":\"v${VERSION#v}\"" | head -n 1 || true)
else
  line=$(printf '%s\n' "$releases" | grep "^$CHANNEL " | head -n 1 || true)
fi

if [ -n "$line" ]; then
  tag=$(printf '%s' "$line" | json_field releaseTag)
  url=$(printf '%s' "$line" | json_field downloadUrl)
  hash=$(printf '%s' "$line" | json_field hash)
  hash=${hash#sha256:}
elif [ -n "$VERSION" ]; then
  # older than what the list holds: straight from the GitHub release
  tag="v${VERSION#v}"
  url="https://github.com/PanoMC/Pano/releases/download/$tag/Pano-${tag#v}.jar"
  hash=$(curl -fsSL "$url.sha256" 2>/dev/null | cut -d' ' -f1 || true)
else
  echo "No Pano release found in the $CHANNEL channel ($API_URL/releases/platform)." >&2
  exit 1
fi

jar="Pano-${tag#v}.jar"
current=$(cat .pano-jar 2>/dev/null || true)

if [ -f "$jar" ] && [ -n "$hash" ] && [ "$(sha256sum "$jar" | cut -d' ' -f1)" = "$hash" ]; then
  echo "Pano ${tag#v} is already installed."
else
  echo "Downloading Pano ${tag#v}..."
  curl -fsSL --retry 3 -o "$jar.part" "$url"
  if [ -n "$hash" ]; then
    actual=$(sha256sum "$jar.part" | cut -d' ' -f1)
    if [ "$actual" != "$hash" ]; then
      rm -f "$jar.part"
      echo "Checksum mismatch for $jar: expected $hash, got $actual" >&2
      exit 1
    fi
  fi
  mv -f "$jar.part" "$jar"
fi

# Same layout as Pano's JarPointer: .pano-jar names the jar, .pano-jar.previous the one before it.
if [ -n "$current" ] && [ "$current" != "$jar" ] && [ -f "$current" ]; then
  printf '%s' "$current" >.pano-jar.previous
fi
printf '%s' "$jar" >.pano-jar
for old in Pano-*.jar; do
  [ "$old" = "$jar" ] || [ "$old" = "$(cat .pano-jar.previous 2>/dev/null || true)" ] || rm -f "$old"
done

echo "Pano ${tag#v} installed. Start the server, then open its address to finish the setup wizard."
