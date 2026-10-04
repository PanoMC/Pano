#!/bin/sh
# Builds the minimal, relocatable git that pano-node downloads for Spigot's BuildTools (SM-67).
#
#   scripts/build-pano-git.sh <os> <arch> <git-version> <output-dir>
#
#   os      linux | macos
#   arch    x64 | arm64 (only used for the asset name: the build is always native)
#
# Writes <output-dir>/pano-git-<os>-<arch>.tar.gz and a sha256sum-format .sha256 next to it.
#
# Why a git this small is enough: BuildTools clones and fetches its repositories with its bundled
# JGit, and the only git processes it starts are `git --version`, `git config --system --list`
# (JGit's own probe) and the local operations in Spigot's applyPatches.sh (clone/fetch from a
# sibling directory, reset, am --3way). None of them talks to a remote over http, so no curl, no
# OpenSSL, no expat; no Perl, Python or Tcl because no command it runs is a script; no gettext
# because nobody reads the messages; no Rust (recent git builds parts of itself with cargo unless
# told not to) so the build needs nothing but a C toolchain and zlib. Verified 2026-09-24 against
# BuildTools #201 building 1.21.8 with exactly this git on PATH.
#
# Linux: run inside Alpine, linked statically against musl, so one file works on every glibc and
# musl distribution alike. macOS: linked against the system libraries only (libSystem, libz,
# libiconv, CoreServices -- all part of every macOS install).
#
# RUNTIME_PREFIX makes git find its libexec and templates relative to its own binary, so the tree
# works wherever the node extracts it (<data>/tools/git/<os>-<arch>-<version>/).
set -eu

OS="${1:?os (linux|macos)}"
ARCH="${2:?arch (x64|arm64)}"
GIT_VERSION="${3:?git version, e.g. 2.55.0}"
OUT="${4:?output directory}"

case "$OS" in
  linux|macos) ;;
  *) echo "Unsupported os: $OS" >&2; exit 2 ;;
esac

case "$ARCH" in
  x64|arm64) ;;
  *) echo "Unsupported arch: $ARCH" >&2; exit 2 ;;
esac

# The build is native, so the machine has to be the arch the asset will be named after: an x64
# binary published as arm64 would pass every check here and fail on every node that downloads it.
case "$(uname -m)" in
  x86_64|amd64) MACHINE=x64 ;;
  aarch64|arm64) MACHINE=arm64 ;;
  *) MACHINE="$(uname -m)" ;;
esac

if [ "$MACHINE" != "$ARCH" ]; then
  echo "Asked for $ARCH but this machine is $MACHINE" >&2
  exit 2
fi

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

NAME="pano-git-$OS-$ARCH"
TARBALL="git-$GIT_VERSION.tar.gz"
MIRROR="https://mirrors.edge.kernel.org/pub/software/scm/git"

cd "$WORK"

curl -fsSL -o "$TARBALL" "$MIRROR/$TARBALL"
curl -fsSL -o sha256sums.asc "$MIRROR/sha256sums.asc"

# kernel.org's checksum list for the source tarball; a mismatch stops the build.
EXPECTED="$(awk -v f="$TARBALL" '$2 == f { print $1 }' sha256sums.asc)"

if [ -z "$EXPECTED" ]; then
  echo "No checksum for $TARBALL in sha256sums.asc" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL="$(sha256sum "$TARBALL" | awk '{ print $1 }')"
else
  ACTUAL="$(shasum -a 256 "$TARBALL" | awk '{ print $1 }')"
fi

if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "Checksum mismatch for $TARBALL: expected $EXPECTED, got $ACTUAL" >&2
  exit 1
fi

tar -xzf "$TARBALL"
cd "git-$GIT_VERSION"

JOBS="$( (nproc || sysctl -n hw.ncpu || echo 2) 2>/dev/null | head -n 1)"

# prefix is only where `make install` puts things under DESTDIR; RUNTIME_PREFIX makes every path
# git uses at run time relative to the directory its binary is in.
PREFIX=/pano-git
STAGE="$WORK/stage"

# The dashed helpers stay (no SKIP_DASHED_BUILT_INS): `git clone ../Bukkit`, which Spigot's
# applyPatches.sh runs, starts `git-upload-pack` by that name. INSTALL_SYMLINKS makes every one of
# them a relative symlink to bin/git, so they cost nothing and survive the node's tar reader
# (which refuses absolute links).

set -- \
  prefix="$PREFIX" \
  RUNTIME_PREFIX=YesPlease \
  NO_CURL=YesPlease \
  NO_EXPAT=YesPlease \
  NO_OPENSSL=YesPlease \
  NO_GETTEXT=YesPlease \
  NO_TCLTK=YesPlease \
  NO_PERL=YesPlease \
  NO_PYTHON=YesPlease \
  NO_RUST=YesPlease \
  NO_INSTALL_HARDLINKS=YesPlease \
  INSTALL_SYMLINKS=YesPlease \
  INSTALL_STRIP=-s

if [ "$OS" = linux ]; then
  # musl's regex lacks REG_STARTEND; git's bundled one is what Alpine's own package uses too.
  set -- "$@" NO_REGEX=YesPlease CFLAGS="-O2" LDFLAGS="-static"
else
  set -- "$@" CFLAGS="-O2 -mmacosx-version-min=11.0" LDFLAGS="-mmacosx-version-min=11.0"
fi

make -j"$JOBS" "$@" all
make "$@" DESTDIR="$STAGE" install

ROOT="$STAGE$PREFIX"

# Nothing BuildTools runs needs these, and every one of them is a megabyte or more.
rm -rf "$ROOT/share/gitweb" "$ROOT/share/perl5" "$ROOT/share/man" "$ROOT/share/locale"
for tool in git-cvsserver git-shell scalar; do
  rm -f "$ROOT/bin/$tool" "$ROOT/libexec/git-core/$tool"
done

"$ROOT/bin/git" --version

if [ "$OS" = linux ]; then
  # A static binary has no interpreter; a dynamic one here would be a broken asset.
  if command -v file >/dev/null 2>&1; then
    file "$ROOT/bin/git"
    file "$ROOT/bin/git" | grep -q "statically linked"
  fi
else
  otool -L "$ROOT/bin/git"
fi

# Relocation check: moved somewhere else, the exec path has to follow.
MOVED="$WORK/moved/pano-git"
mkdir -p "$WORK/moved"
cp -R "$ROOT" "$MOVED"

EXEC_PATH="$("$MOVED/bin/git" --exec-path)"

case "$EXEC_PATH" in
  "$MOVED"/*) ;;
  *) echo "git is not relocatable: exec path $EXEC_PATH" >&2; exit 1 ;;
esac

# The local operations BuildTools' applyPatches.sh relies on, end to end, with no system git.
(
  cd "$WORK/moved"
  export GIT_CONFIG_NOSYSTEM=1 HOME="$WORK/moved"
  G="$MOVED/bin/git"
  "$G" init -q upstream
  cd upstream
  echo one > a.txt
  "$G" add a.txt
  "$G" -c user.name=t -c user.email=t@t commit -qm one
  echo two >> a.txt
  "$G" -c user.name=t -c user.email=t@t commit -qam two
  "$G" format-patch -q -1 -o ../patches
  "$G" reset -q --hard HEAD~1
  cd ..
  "$G" clone -q upstream downstream
  cd downstream
  "$G" fetch -q origin
  "$G" -c user.name=t -c user.email=t@t am -q --3way ../patches/*.patch
  grep -q two a.txt
)

rm -rf "$MOVED"

# One top-level directory, which the node strips when it extracts.
cd "$STAGE"
mv "${PREFIX#/}" "$NAME"
printf '%s\n' "$GIT_VERSION" > "$NAME/PANO-GIT-VERSION"

# macOS tar would otherwise add AppleDouble `._*` entries for extended attributes.
COPYFILE_DISABLE=1 tar -czf "$OUT/$NAME.tar.gz" "$NAME"

cd "$OUT"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256"
else
  shasum -a 256 "$NAME.tar.gz" > "$NAME.tar.gz.sha256"
fi

ls -l "$OUT/$NAME.tar.gz"
cat "$OUT/$NAME.tar.gz.sha256"
