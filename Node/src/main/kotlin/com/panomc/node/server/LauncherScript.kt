package com.panomc.node.server

/**
 * The `sh` script a server is started through on Linux and macOS (SM-62, §2.4.27).
 *
 * Its whole job is to make the server independent of the daemon that started it:
 * - stdin comes from the FIFO `stdin`, opened **read-write** on fd 3, so the game never reads
 *   end-of-file while no daemon holds the writing end — a daemon restart is invisible to it;
 * - stdout and stderr are appended to `console.out`, which the daemon tails by offset;
 * - TERM, INT and HUP are forwarded to the server, and the launcher waits for it through them;
 * - the exit code is written to `exit` (temporary file and `mv`, so a reader never sees half a
 *   number) before the launcher exits with the same code.
 *
 * Plain POSIX `sh`, nothing else but `mv`: no `dirname` (a parameter expansion does it), no `rm`
 * (the daemon clears the old exit file before a start), no bashisms. The server's own command line
 * arrives as the script's arguments and is run as `"$@"` — never re-parsed, so nothing in a JVM
 * argument or a server name can become shell.
 */
object LauncherScript {
    const val FILE = "launch.sh"

    /** Exit code when the FIFO could not be opened: the server never started. */
    const val FIFO_FAILED_EXIT = 111

    val CONTENT: String = """
        |#!/bin/sh
        |# Written by pano-node on every start; do not edit. Runs one server so that it outlives the
        |# daemon: stdin from a FIFO held open read-write, output appended to a file, exit code to a
        |# file. Usage: launch.sh <server command line...>
        |dir=${'$'}{0%/*}
        |fifo="${'$'}dir/stdin"
        |out="${'$'}dir/${DetachedFiles.OUTPUT_FILE}"
        |exitfile="${'$'}dir/${DetachedFiles.EXIT_FILE}"
        |
        |# Read-write: the launcher is always a reader, so the server never sees end-of-file while no
        |# daemon holds the writing end, and a daemon opening it to write never blocks.
        |exec 3<>"${'$'}fifo" || exit $FIFO_FAILED_EXIT
        |
        |"${'$'}@" <&3 3<&- >>"${'$'}out" 2>&1 &
        |child=${'$'}!
        |
        |sig=
        |trap 'sig=1; kill -TERM "${'$'}child" 2>/dev/null' TERM
        |trap 'sig=1; kill -INT "${'$'}child" 2>/dev/null' INT
        |trap 'sig=1; kill -HUP "${'$'}child" 2>/dev/null' HUP
        |
        |# A forwarded signal interrupts wait; only then is the child possibly still running.
        |while :; do
        |  sig=
        |  wait "${'$'}child"
        |  code=${'$'}?
        |  [ -n "${'$'}sig" ] && kill -0 "${'$'}child" 2>/dev/null && continue
        |  break
        |done
        |
        |printf '%s\n' "${'$'}code" >"${'$'}exitfile.tmp" && mv -f "${'$'}exitfile.tmp" "${'$'}exitfile"
        |exit "${'$'}code"
        |""".trimMargin()
}
