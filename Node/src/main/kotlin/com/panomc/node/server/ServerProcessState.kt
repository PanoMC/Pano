package com.panomc.node.server

/**
 * The process states Pano understands, with the same names it stores.
 *
 * INSTALLING lives on Pano's side of the protocol only: the node reports what a process is doing,
 * and while an install runs there is no process at all -- that phase is reported as task progress
 * instead, which is what carries a percentage.
 */
enum class ServerProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED;

    val isAlive get() = this == STARTING || this == RUNNING || this == STOPPING
}

/**
 * The rules for moving between [ServerProcessState] values.
 *
 * Pulled out of the supervisor so the transitions can be exercised without starting a JVM: the
 * interesting part of this state machine is what it refuses (a start while stopping, a crash for a
 * stop somebody asked for), and that is precisely the part a test of a real process cannot reach
 * reliably.
 */
object ServerStateMachine {
    /** The state a start request moves to, or null when the request must be ignored. */
    fun onStartRequested(current: ServerProcessState): ServerProcessState? =
        if (current == ServerProcessState.STOPPED || current == ServerProcessState.CRASHED) {
            ServerProcessState.STARTING
        } else {
            null
        }

    /** STARTING becomes RUNNING once the server says it is done booting, or the deadline passes. */
    fun onReady(current: ServerProcessState): ServerProcessState? =
        if (current == ServerProcessState.STARTING) ServerProcessState.RUNNING else null

    /** A stop is accepted from any live state; a stopped server simply has nothing to stop. */
    fun onStopRequested(current: ServerProcessState): ServerProcessState? =
        if (current.isAlive) ServerProcessState.STOPPING else null

    /**
     * Where a process that exited lands.
     *
     * A non-zero exit is only a crash when nobody asked for the stop: a server killed on request
     * exits non-zero on most platforms and calling that a crash would page an operator every time
     * they pressed the button.
     *
     * The second rule is there because of what a Minecraft server does when it cannot bind its
     * port: it prints the failure and shuts itself down *tidily*, status 0, about twenty seconds
     * in. Read literally that is a clean stop, and a clean stop is exactly what the panel showed
     * for a server that had never started — the operator was told everything was fine and found no
     * server. So a process that exited before it ever reached RUNNING did not stop cleanly, whatever
     * its status code says, and [STARTUP_GRACE_MILLIS] is the window in which that is true.
     * Past it, [ServerProcess] has already called the server RUNNING anyway, so a zero exit really
     * is somebody typing `stop` in the console.
     */
    fun onExit(
        exitCode: Int,
        requested: Boolean,
        reachedRunning: Boolean = true,
        uptimeMillis: Long = Long.MAX_VALUE
    ): ServerProcessState = when {
        requested -> ServerProcessState.STOPPED
        exitCode != 0 -> ServerProcessState.CRASHED
        !reachedRunning && uptimeMillis < STARTUP_GRACE_MILLIS -> ServerProcessState.CRASHED
        else -> ServerProcessState.STOPPED
    }

    /**
     * Where an adopted process lands when it exits (SM-51, §2.4.16).
     *
     * There is no exit code to reason about: the handle that carries one belonged to the daemon
     * that started the server, and this one only ever held a [ProcessHandle]. So the single fact
     * available is whether Pano asked for this. It did -- STOPPED. It did not -- the server went
     * away on its own, which is what CRASHED means, and the reason comes off the log tail instead
     * of off a status code.
     */
    fun onAdoptedExit(requested: Boolean): ServerProcessState =
        if (requested) ServerProcessState.STOPPED else ServerProcessState.CRASHED

    /**
     * How long a server has to finish starting before a clean exit counts as an ordinary stop.
     *
     * The same minute [ServerProcess.READY_TIMEOUT_SECONDS] gives it to print its "Done" line, and
     * deliberately so: after that the supervisor calls it RUNNING regardless, so anything later is
     * a running server exiting rather than one that never came up.
     */
    const val STARTUP_GRACE_MILLIS = 60_000L

    /**
     * Whether a crashed server should be restarted, and how long to wait first.
     *
     * The backoff climbs and then gives up for ten minutes rather than forever: a server that
     * crashes because a disk filled up will start again once it is emptied, and a node that
     * stopped trying would leave it down until somebody noticed.
     */
    fun restartDelayMillis(attempt: Int): Long = when {
        attempt <= 1 -> 5_000L
        attempt == 2 -> 15_000L
        attempt == 3 -> 60_000L
        else -> 600_000L
    }
}
