package com.panomc.node

import com.panomc.node.console.ConsoleLevel
import com.panomc.node.console.ConsoleLineParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConsoleLineParserTest {
    @Test
    fun `reads the level out of a modern server line`() {
        assertEquals(
            ConsoleLevel.WARN,
            ConsoleLineParser.parseLevel("[12:34:56] [Server thread/WARN]: Can't keep up!")
        )
    }

    @Test
    fun `reads the level out of an older server line`() {
        assertEquals(
            ConsoleLevel.ERROR,
            ConsoleLineParser.parseLevel("[12:34:56 ERROR]: Exception in thread main")
        )
    }

    @Test
    fun `falls back when the line carries no level`() {
        assertEquals(ConsoleLevel.INFO, ConsoleLineParser.parseLevel("plain output"))
    }

    @Test
    fun `uses the caller's fallback for unprefixed stderr`() {
        assertEquals(
            ConsoleLevel.ERROR,
            ConsoleLineParser.parseLevel("\tat java.base/java.lang.Thread.run", ConsoleLevel.ERROR)
        )
    }

    @Test
    fun `folds platform level names onto the five wire levels`() {
        assertEquals(ConsoleLevel.WARN, ConsoleLineParser.normalise("warning"))
        assertEquals(ConsoleLevel.ERROR, ConsoleLineParser.normalise("SEVERE"))
        assertEquals(ConsoleLevel.TRACE, ConsoleLineParser.normalise("FINEST"))
        assertEquals(null, ConsoleLineParser.normalise("LOUD"))
    }

    @Test
    fun `strips ansi colour codes before the level is read`() {
        val line = "\u001B[32m[12:34:56] [Server thread/INFO]:\u001B[0m Done (5.1s)!"

        assertEquals(ConsoleLevel.INFO, ConsoleLineParser.parseLevel(line))
        assertEquals("[12:34:56] [Server thread/INFO]: Done (5.1s)!", ConsoleLineParser.stripAnsi(line))
    }

    @Test
    fun `truncates a line past the protocol cap`() {
        val line = ConsoleLineParser.toLine("x".repeat(ConsoleLineParser.MAX_MESSAGE_LENGTH + 500), 7L)

        assertEquals(ConsoleLineParser.MAX_MESSAGE_LENGTH, line.m.length)
        assertEquals(7L, line.t)
    }
}
