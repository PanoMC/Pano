#!/bin/sh
# Starts pano-node as the unprivileged `pano` user.
#
# The container starts as root only for this: a volume written by an older image (which ran the
# daemon as root) is root-owned, and the daemon could not read its own pairing from it. Such a
# volume is handed to `pano` once, then root is dropped for good before Java starts. A container
# started with `--user` is already unprivileged and runs the daemon as it is.
set -eu

JAR=/app/pano-node.jar
DATA="${PANO_NODE_DATA:-/data}"
RUN_USER=pano

if [ "$(id -u)" != "0" ]; then
    exec java -jar "$JAR" "$@"
fi

RUN_UID="$(id -u "$RUN_USER")"
RUN_GID="$(id -g "$RUN_USER")"

mkdir -p "$DATA"

# Only when the top of the volume is not ours yet, so a normal start never walks the whole tree.
# -h and -P: a link inside the volume is changed itself and never followed, so a file a server
# planted cannot make root take over something outside the volume.
if [ "$(stat -c %u "$DATA")" != "$RUN_UID" ]; then
    echo "pano-node: giving $DATA to the $RUN_USER user (once; this can take a while on a large volume)"
    chown -R -h -P "$RUN_UID:$RUN_GID" "$DATA"
fi

# The environment is kept as it is (PANO_URL, PANO_BOOTSTRAP_TOKEN, ... configure the daemon);
# only the home directory moves to one the user can write.
export HOME="/home/$RUN_USER" USER="$RUN_USER" LOGNAME="$RUN_USER"

exec setpriv --reuid="$RUN_UID" --regid="$RUN_GID" --init-groups -- java -jar "$JAR" "$@"
