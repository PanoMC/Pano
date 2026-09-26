#!/bin/sh
# Seeds /data with the release bundled in the image, then hands over to pano-launcher.
#   - no usable /data/.pano-jar             -> install the bundled jar (first start)
#   - /data/.pano-image-jar != bundled jar  -> install it too (the image was upgraded or downgraded)
#   - otherwise                             -> keep /data as it is (an in-panel self-update stays)
# The pointer protocol is JarPointer's: file name only, written through a tmp file and a rename,
# the old target kept in .pano-jar.previous.
set -eu

data=${PANO_DATA_DIR:-/data}
release_dir=${PANO_RELEASE_DIR:-/opt/pano/release}

bundled=
for f in "$release_dir"/Pano-*.jar; do if [ -f "$f" ]; then bundled=$(basename "$f"); fi; done

write() { # $1 file, $2 value
  printf '%s' "$2" > "$data/$1.tmp" && mv -f "$data/$1.tmp" "$data/$1"
}

if [ -n "$bundled" ]; then
  current=$(cat "$data/.pano-jar" 2>/dev/null || true)
  case "$current" in ''|*/*|.|..) current= ;; esac
  if [ -n "$current" ] && [ ! -f "$data/$current" ]; then current=; fi
  seeded=$(cat "$data/.pano-image-jar" 2>/dev/null || true)

  if [ -z "$current" ] || [ "$seeded" != "$bundled" ]; then
    if [ ! -w "$data" ]; then
      echo "pano-seed: $data is not writable by uid $(id -u); mount a volume owned by it" >&2
      exit 73
    fi
    if [ "$current" != "$bundled" ]; then
      cp "$release_dir/$bundled" "$data/.$bundled.tmp"
      mv -f "$data/.$bundled.tmp" "$data/$bundled"
      if [ -n "$current" ]; then write .pano-jar.previous "$current"; fi
      write .pano-jar "$bundled"
    fi
    write .pano-image-jar "$bundled"
    keep=$(cat "$data/.pano-jar.previous" 2>/dev/null || true)
    for f in "$data"/Pano-*.jar; do
      n=$(basename "$f")
      if [ -f "$f" ] && [ "$n" != "$bundled" ] && [ "$n" != "$keep" ]; then rm -f "$f"; fi
    done
    echo "pano-seed: installed the bundled $bundled" >&2
  fi
fi

exec /usr/local/bin/pano-launcher "$@"
