package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FirstRunAnswerTest {

    // ---- the arbiter ----

    @Test
    fun `nobody has answered at first`() {
        val first = FirstAnswer()

        assertNull(first.answer)
        assertFalse(first.await(1))
    }

    @Test
    fun `the first answer wins and a later one is ignored`() {
        val first = FirstAnswer()

        assertTrue(first.offer(true, AnswerSource.TERMINAL))
        assertFalse(first.offer(false, AnswerSource.DIALOG))
        assertFalse(first.offer(true, AnswerSource.DIALOG))

        assertEquals(FirstAnswer.Answer(true, AnswerSource.TERMINAL), first.answer)
        assertTrue(first.await(1))
    }

    @Test
    fun `a no that came first is not overturned by a later yes`() {
        val first = FirstAnswer()

        assertTrue(first.offer(false, AnswerSource.DIALOG))
        assertFalse(first.offer(true, AnswerSource.TERMINAL))
        assertEquals(FirstAnswer.Answer(false, AnswerSource.DIALOG), first.answer)
    }

    @Test
    fun `when both answers arrive at once exactly one wins and everyone sees the same winner`() {
        repeat(200) {
            val first = FirstAnswer()
            val start = CountDownLatch(1)
            val winners = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)

            try {
                val jobs = listOf(AnswerSource.DIALOG, AnswerSource.TERMINAL).map { source ->
                    pool.submit {
                        start.await()
                        if (first.offer(source == AnswerSource.TERMINAL, source)) winners.incrementAndGet()
                    }
                }

                start.countDown()
                jobs.forEach { it.get() }

                assertEquals(1, winners.get())
                assertTrue(first.await(1000))
                assertEquals(first.answer!!.source == AnswerSource.TERMINAL, first.answer!!.yes)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // ---- the non-blocking line ----

    /**
     * An input that hands out only what a test has put in, never blocks, and counts the reads made on it.
     * Like a terminal in line mode, a bulk read returns at most one line.
     */
    private class FakeInput : InputStream() {
        private val data = ArrayDeque<Int>()
        var reads = 0
        var broken = false

        fun type(text: String) = text.toByteArray().forEach { data.addLast(it.toInt() and 0xff) }

        override fun available(): Int = if (broken) throw IOException("gone") else data.size

        override fun read(): Int {
            check(data.isNotEmpty()) { "a read with nothing available would block" }
            reads++
            return data.removeFirst()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(data.isNotEmpty()) { "a read with nothing available would block" }
            reads++

            var n = 0

            while (n < len && data.isNotEmpty()) {
                val next = data.removeFirst()
                b[off + n++] = next.toByte()

                if (next == 10) break
            }

            return n
        }

        val left: Int get() = data.size
    }

    /**
     * A Windows console as the JDK sees it: `available()` counts the key presses waiting in the console's
     * queue; a one-byte read makes the console take the whole line out of that queue and return only its
     * first character (the rest is kept by the console, invisible to `available()`); a bulk read returns
     * the whole line.
     */
    private class WindowsConsoleInput : InputStream() {
        private val queue = ArrayDeque<Int>()
        private val kept = ArrayDeque<Int>()

        fun type(text: String) = text.toByteArray().forEach { queue.addLast(it.toInt() and 0xff) }

        override fun available(): Int = queue.size

        private fun takeLine() {
            while (queue.isNotEmpty()) {
                val b = queue.removeFirst()
                kept.addLast(b)

                if (b == 10) break
            }
        }

        override fun read(): Int {
            if (kept.isEmpty()) takeLine()

            return kept.removeFirst()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (kept.isEmpty()) takeLine()

            var n = 0

            while (n < len && kept.isNotEmpty()) b[off + n++] = kept.removeFirst().toByte()

            return n
        }
    }

    @Test
    fun `a line is complete on the first Enter on a Windows console`() {
        val input = WindowsConsoleInput()
        val line = PolledLine(input)

        assertEquals(PolledLine.Result.Pending, line.poll())

        input.type("y\r\n")

        assertEquals(PolledLine.Result.Line("y"), line.poll())
        assertEquals(PolledLine.Result.Pending, line.poll())
    }

    @Test
    fun `nothing typed is nothing read`() {
        val input = FakeInput()

        assertEquals(PolledLine.Result.Pending, PolledLine(input).poll())
        assertEquals(0, input.reads)
    }

    @Test
    fun `a whole line is returned without its newline`() {
        val input = FakeInput().apply { type("yes\n") }

        assertEquals(PolledLine.Result.Line("yes"), PolledLine(input).poll())
        assertEquals(0, input.left)
    }

    @Test
    fun `a line typed in pieces is assembled across polls`() {
        val input = FakeInput()
        val line = PolledLine(input)

        input.type("y")
        assertEquals(PolledLine.Result.Pending, line.poll())

        input.type("e")
        assertEquals(PolledLine.Result.Pending, line.poll())

        input.type("s\n")
        assertEquals(PolledLine.Result.Line("yes"), line.poll())
    }

    @Test
    fun `carriage returns are dropped and an empty line is an empty answer`() {
        val input = FakeInput().apply { type("\r\n") }

        assertEquals(PolledLine.Result.Line(""), PolledLine(input).poll())
        assertFalse(FirstRunPolicy.parseAnswer(""))
    }

    @Test
    fun `nothing after the newline is taken, it stays for the next reader on a terminal`() {
        val input = FakeInput().apply { type("y\nhelp\n") }

        assertEquals(PolledLine.Result.Line("y"), PolledLine(input).poll())
        assertEquals(5, input.left)
        assertEquals(1, input.reads)
    }

    @Test
    fun `non-ASCII text survives the read`() {
        val input = FakeInput().apply { type("evet ğ\n") }

        assertEquals(PolledLine.Result.Line("evet ğ"), PolledLine(input).poll())
    }

    @Test
    fun `a closed input and a broken input are the end of input`() {
        assertEquals(
            PolledLine.Result.EndOfInput,
            PolledLine(object : InputStream() {
                override fun available() = 1
                override fun read() = -1
                override fun read(b: ByteArray, off: Int, len: Int) = -1
            }).poll()
        )
        assertEquals(PolledLine.Result.EndOfInput, PolledLine(FakeInput().apply { broken = true }).poll())
        assertEquals(PolledLine.Result.Pending, PolledLine(ByteArrayInputStream(ByteArray(0))).poll())
    }
}
