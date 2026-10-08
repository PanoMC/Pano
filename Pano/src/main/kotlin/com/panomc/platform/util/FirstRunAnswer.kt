package com.panomc.platform.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Where an answer to the first-run question came from. */
enum class AnswerSource { DIALOG, TERMINAL }

/**
 * Decides the first-run question once when it is asked in two places at the same time: the first
 * [offer] wins, every later one is ignored. Free of any I/O so it can be tested on its own.
 */
class FirstAnswer {

    data class Answer(val yes: Boolean, val source: AnswerSource)

    private val winner = AtomicReference<Answer?>(null)
    private val decided = CountDownLatch(1)

    /** The decided answer, or null while nobody has answered. */
    val answer: Answer? get() = winner.get()

    /** Returns true when this answer is the one that counts, false when another one was first. */
    fun offer(yes: Boolean, source: AnswerSource): Boolean {
        val won = winner.compareAndSet(null, Answer(yes, source))

        if (won) decided.countDown()

        return won
    }

    /** Waits up to [timeoutMs] for an answer; true when there is one. */
    fun await(timeoutMs: Long): Boolean = decided.await(timeoutMs, TimeUnit.MILLISECONDS)
}

/**
 * Reads one line from an input without ever blocking in a read: [poll] looks at what [input] can
 * deliver right now and reads exactly that, so no thread is left waiting on the input after the
 * caller stops polling. The input must not be buffered (pass the raw file descriptor stream, not
 * `System.in`), so nothing is read ahead: everything after the line's newline stays in the
 * operating system's queue for whoever reads the input next (the console reader, later).
 *
 * On a terminal in line mode `available()` is positive only once a whole line was entered, so a
 * partly typed line is left to the terminal's own line buffer.
 */
class PolledLine(private val input: InputStream) {

    sealed class Result {
        /** No complete line yet. */
        object Pending : Result()

        data class Line(val text: String) : Result()

        /** The input is closed or broke. */
        object EndOfInput : Result()
    }

    private val pending = ByteArrayOutputStream()

    fun poll(): Result {
        try {
            while (input.available() > 0) {
                val b = input.read()

                when {
                    b < 0 -> return Result.EndOfInput

                    b == NEWLINE -> {
                        val text = String(pending.toByteArray(), Charsets.UTF_8)
                        pending.reset()

                        return Result.Line(text)
                    }

                    b != CARRIAGE_RETURN -> pending.write(b)
                }
            }
        } catch (_: IOException) {
            return Result.EndOfInput
        }

        return Result.Pending
    }

    private companion object {
        const val NEWLINE = 10
        const val CARRIAGE_RETURN = 13
    }
}
