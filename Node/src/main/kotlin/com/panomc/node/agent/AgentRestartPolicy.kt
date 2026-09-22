package com.panomc.node.agent

import com.panomc.node.NodeVersion

/**
 * What a Pano Agent's launcher does when its worker exits (SM-74).
 *
 * Pure, so every exit code can be asserted without starting a process:
 *
 * - [NodeVersion.SELF_UPDATE_EXIT_CODE] (75): the worker swapped a new jar in; start it again at
 *   once, from the same path, and the new code runs. The server kept running throughout.
 * - [NodeVersion.RETIRED_EXIT_CODE] (78): Pano removed the agent; say so and exit 0, so nothing that
 *   restarts on failure starts it again.
 * - 0: the worker finished on purpose; exit 0.
 * - 2, [NodeVersion.ALREADY_RUNNING_EXIT_CODE] (76), [NodeVersion.NOT_PAIRED_EXIT_CODE] (77): the
 *   worker cannot run as asked (a bad command line, another agent in this folder, a code that was
 *   wrong or expired) and has already said why; restarting would only say it again, so the launcher
 *   exits with the same code.
 * - anything else is a crash: start it again after 1, 2, 5, 10, then 30 seconds, and forget the
 *   count once a worker has stayed up for five minutes.
 */
object AgentRestartPolicy {
    /** One decision: start again after [delayMillis], or end the launcher with [exitCode]. */
    sealed class Decision {
        data class Restart(val delayMillis: Long, val nextAttempt: Int) : Decision()

        data class Exit(val exitCode: Int, val message: String? = null) : Decision()
    }

    /** What the daemon exits with when its command line cannot be used. */
    const val USAGE_EXIT_CODE = 2

    /** The backoff after the first, second, … crash in a row. */
    val BACKOFF_MILLIS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    /** How long a worker has to stay up for its next crash to count as the first again. */
    const val STABLE_UPTIME_MILLIS = 5 * 60_000L

    /** Exit codes that mean "do not start me again as I am". */
    val FATAL_EXIT_CODES = setOf(USAGE_EXIT_CODE, NodeVersion.ALREADY_RUNNING_EXIT_CODE, NodeVersion.NOT_PAIRED_EXIT_CODE)

    const val REMOVED_MESSAGE =
        "This server was removed from Pano. Start it with its own server jar again; pano-agent.jar can be deleted."

    /**
     * What to do after a worker exited with [exitCode] having run for [uptimeMillis]; [attempt] is
     * how many crashes in a row came before this one.
     */
    fun decide(exitCode: Int, uptimeMillis: Long, attempt: Int): Decision = when (exitCode) {
        NodeVersion.SELF_UPDATE_EXIT_CODE -> Decision.Restart(0L, 0)

        NodeVersion.RETIRED_EXIT_CODE -> Decision.Exit(0, REMOVED_MESSAGE)

        0 -> Decision.Exit(0)

        in FATAL_EXIT_CODES -> Decision.Exit(exitCode)

        else -> {
            val crashes = if (uptimeMillis >= STABLE_UPTIME_MILLIS) 0 else attempt

            Decision.Restart(BACKOFF_MILLIS[crashes.coerceIn(0, BACKOFF_MILLIS.size - 1)], crashes + 1)
        }
    }
}
