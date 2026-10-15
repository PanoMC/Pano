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
 * deliver right now and reads it with a single `read(ByteArray)`, so no thread is left waiting on the
 * input after the caller stops polling. The input must not be buffered (pass the raw file descriptor
 * stream, not `System.in`), so nothing is read ahead.
 *
 * Why one read of many bytes and not a byte at a time: on a Windows console `available()` counts the key
 * presses still in the console's queue, and the first one-byte read makes the console take the whole line
 * out of that queue and hand out only its first character, so `available()` would then report 0 for the
 * rest of the line. A single read with a large buffer returns exactly one line there, and on a terminal
 * in line mode (where `available()` is positive only once a whole line was entered) too, so a partly
 * typed line is left to the terminal's own line buffer and nothing after the line's newline is queued
 * behind it for whoever reads the input next (the console reader, later). Bytes after the newline in the
 * same read, which a terminal never delivers, are dropped.
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
    private val buffer = ByteArray(BUFFER_SIZE)

    fun poll(): Result {
        try {
            while (input.available() > 0) {
                val count = input.read(buffer, 0, buffer.size)

                if (count < 0) return Result.EndOfInput

                for (i in 0 until count) {
                    val b = buffer[i].toInt() and 0xff

                    when (b) {
                        NEWLINE -> {
                            val text = String(pending.toByteArray(), Charsets.UTF_8)
                            pending.reset()

                            return Result.Line(text)
                        }

                        CARRIAGE_RETURN -> Unit

                        else -> pending.write(b)
                    }
                }
            }
        } catch (_: IOException) {
            return Result.EndOfInput
        }

        return Result.Pending
    }

    private companion object {
        const val BUFFER_SIZE = 1024
        const val NEWLINE = 10
        const val CARRIAGE_RETURN = 13
    }
}
