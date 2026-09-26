package com.panomc.platform.hosted

import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * The exit code the main thread ends the JVM with once Pano has shut down. The first request wins
 * (the one that started the shutdown); [exitHook] is replaceable in tests.
 */
class ProcessExit(private val exitHook: (Int) -> Unit = { exitProcess(it) }) {
    private val requested = AtomicInteger(-1)

    /** Records [code] unless an earlier request already set one. Returns true when it was recorded. */
    fun request(code: Int): Boolean = requested.compareAndSet(-1, code)

    val code: Int get() = requested.get().takeIf { it >= 0 } ?: 0

    fun exit() = exitHook(code)
}
