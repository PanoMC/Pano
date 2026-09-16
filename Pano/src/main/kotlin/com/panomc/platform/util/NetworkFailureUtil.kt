package com.panomc.platform.util

import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Helpers for reporting a failed outbound HTTP call as a single log line.
 *
 * An unreachable host is an environment condition, not a defect in this code. Its stack trace is
 * ~40 frames of Vert.x/Netty plumbing that are identical on every occurrence and say nothing the
 * exception message doesn't already say, so logging one buries the actual operational signal -
 * *which* call failed and *why* - in noise. Connectivity failures are therefore summarised with
 * [describe]; anything else keeps its stack trace, because there the frames are the only thing
 * pointing at the bug.
 */
object NetworkFailureUtil {

    /**
     * Whether [error], or anything it wraps, is a plain "could not reach the other side" failure:
     * DNS, connect, reset, TLS and read/write errors all surface as [IOException] subclasses, and a
     * request that ran out of time as [TimeoutException].
     */
    fun isConnectivityFailure(error: Throwable): Boolean =
        causeChain(error).any { it is IOException || it is TimeoutException }

    /**
     * One line naming what went wrong, taken from the root cause - the outer frames of a wrapped
     * network error are generic ("Connection failed"), the innermost one carries the detail
     * (`UnknownHostException: Failed to resolve 'api.github.com'`).
     */
    fun describe(error: Throwable): String {
        val root = causeChain(error).last()
        val message = root.message?.takeIf { it.isNotBlank() }

        return if (message == null) root.javaClass.simpleName else "${root.javaClass.simpleName}: $message"
    }

    // Identity-compares against what was already collected so a self-referencing or cyclic cause
    // chain (rare, but possible for exceptions that are initCause'd to each other) terminates
    // instead of looping forever.
    private fun causeChain(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error

        while (current != null && chain.none { it === current }) {
            chain.add(current)
            current = current.cause
        }

        return chain
    }
}
