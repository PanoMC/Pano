package com.panomc.node

import com.panomc.node.console.ConsoleLine
import com.panomc.node.console.ServerLogFollower
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

/**
 * The console of an adopted process (SM-51, §2.4.16).
 *
 * Driven by hand rather than by its own thread: what matters is where the reader is in the file
 * after each read, and a test that slept for the poll interval would be testing the clock.
 */
class ServerLogFollowerTest {
    @TempDir
    lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")

    private val collected = mutableListOf<ConsoleLine>()

    private fun follower() = ServerLogFollower(root, zone) { collected.addAll(it) }

    private fun logs(): File = File(root, "logs").apply { mkdirs() }

    private fun latest(): File = File(logs(), "latest.log")

    private fun append(vararg lines: String) {
        latest().appendText(lines.joinToString("\n") + "\n")
    }

    @Test
    fun `starts at the end and reports only what arrives after it`() {
        logs()

        append("[10:00:00] [Server thread/INFO]: written before anyone was watching")

        val follower = follower()

        follower.open()
        follower.drain()

        assertTrue(collected.isEmpty())

        append("[10:00:01] [Server thread/INFO]: hello")

        follower.drain()

        assertEquals(listOf("[10:00:01] [Server thread/INFO]: hello"), collected.map { it.m })
    }

    @Test
    fun `reads each append once and keeps the level and the time off the line`() {
        logs()

        val follower = follower()

        follower.open()

        append("[10:00:01] [Server thread/INFO]: one")

        follower.drain()

        append("[10:00:02] [Server thread/WARN]: two")

        follower.drain()
        follower.drain()

        assertEquals(listOf("INFO", "WARN"), collected.map { it.l })
        assertEquals(listOf("one", "two"), collected.map { it.m.substringAfterLast(": ") })
        assertTrue(collected[1].t > collected[0].t)
    }

    @Test
    fun `holds an unfinished line back until its newline arrives`() {
        logs()

        val follower = follower()

        follower.open()

        latest().appendText("[10:00:03] [Server thread/INFO]: half a l")

        follower.drain()

        assertTrue(collected.isEmpty())

        latest().appendText("ine\n")

        follower.drain()

        assertEquals(listOf("[10:00:03] [Server thread/INFO]: half a line"), collected.map { it.m })
    }

    @Test
    fun `survives a rotation that replaces the file`() {
        logs()

        val follower = follower()

        follower.open()

        append("[23:59:59] [Server thread/INFO]: last line of the day")

        follower.drain()

        // What a Minecraft server does at midnight: latest.log is moved aside and a new, shorter
        // one takes its place. A reader still sitting at the old offset would go silent forever.
        latest().renameTo(File(logs(), "2026-09-22-1.log"))

        append("[00:00:00] [Server thread/INFO]: first line of the new one")

        follower.drain()

        assertEquals(
            listOf("[23:59:59] [Server thread/INFO]: last line of the day", "[00:00:00] [Server thread/INFO]: first line of the new one"),
            collected.map { it.m }
        )
    }

    @Test
    fun `starts over when the file is truncated in place`() {
        logs()

        val follower = follower()

        follower.open()

        append("[10:00:00] [Server thread/INFO]: aaaaaaaaaaaaaaaaaaaaaaaa")

        follower.drain()

        latest().writeText("[10:00:01] [Server thread/INFO]: short\n")

        follower.drain()

        assertEquals("[10:00:01] [Server thread/INFO]: short", collected.last().m)
    }

    @Test
    fun `truncates an absurd line instead of holding it whole`() {
        logs()

        val follower = follower()

        follower.open()

        append("x".repeat(ServerLogFollower.MAX_LINE_LENGTH * 3))
        append("[10:00:04] [Server thread/INFO]: still reading")

        follower.drain()

        assertEquals(ServerLogFollower.MAX_LINE_LENGTH, collected.first().m.length)
        assertEquals("[10:00:04] [Server thread/INFO]: still reading", collected.last().m)
    }

    @Test
    fun `is not upset by a server that has never written a log`() {
        val follower = follower()

        follower.open()
        follower.drain()

        assertTrue(collected.isEmpty())

        append("[10:00:05] [Server thread/INFO]: first boot")

        follower.drain()

        assertEquals(1, collected.size)
    }
}
