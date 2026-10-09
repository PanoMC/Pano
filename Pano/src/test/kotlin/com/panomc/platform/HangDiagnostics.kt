package com.panomc.platform

import io.vertx.core.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The HTTP tests wait for a real server on the same JVM. When a wait times out on a CI runner the plain
 * TimeoutException says nothing, so this prints what was in flight and every thread's stack to the test output.
 */
object HangDiagnostics {
    @Volatile
    var inFlight: String = ""

    fun <T> await(future: Future<T>, seconds: Long): T {
        try {
            return future.toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            val dump = StringBuilder("HANG after ${seconds}s, in flight: $inFlight, future: $future\n")

            Thread.getAllStackTraces().forEach { (thread, stack) ->
                dump.append("\"${thread.name}\" state=${thread.state} daemon=${thread.isDaemon}\n")
                stack.forEach { dump.append("    at $it\n") }
            }

            System.err.println(dump)

            // Gradle prints a failed test's exception to the console, not its output: carry the Vert.x and test
            // threads in the message so a CI log shows where the wait was stuck.
            val brief = StringBuilder("HANG after ${seconds}s, in flight: $inFlight, future: $future\n")

            Thread.getAllStackTraces()
                .filterKeys { it.name.startsWith("vert.x") || it.name.startsWith("Test worker") || it.name.contains("vertx") }
                .toSortedMap(compareBy { it.name })
                .forEach { (thread, stack) ->
                    brief.append("\"${thread.name}\" ${thread.state}\n")
                    stack.take(18).forEach { brief.append("    at $it\n") }
                }

            throw TimeoutException(brief.toString()).apply { initCause(e) }
        }
    }
}
