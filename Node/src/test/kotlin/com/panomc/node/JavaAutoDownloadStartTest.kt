package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaDownloads
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * What a start does when the Java it needs is missing (SM-63, §2.4.28).
 *
 * No process is ever launched: every case here ends in a refusal, which is the part of a start
 * that has to be right without a JVM to watch -- STOPPED rather than CRASHED, the reason and its
 * machine-readable code on the state, and the download asked for exactly the right major. Majors
 * 42 and up, so a JDK on the machine running the tests can never satisfy one by accident.
 */
class JavaAutoDownloadStartTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private data class Seen(val state: ServerProcessState, val reason: ServerProcess.StateReason?)

    private val seen = CopyOnWriteArrayList<Seen>()

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {
            seen.add(Seen(state, server.stateReason))
        }

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private class FakeDownloads(override val enabled: Boolean, private val answer: String?) : JavaDownloads {
        val asked = CopyOnWriteArrayList<Pair<String, Int>>()

        override fun installForStart(serverUuid: String, major: Int): String? {
            asked.add(serverUuid to major)

            return answer
        }
    }

    private fun server(uuid: String, javaMajor: Int, version: String, jar: (File) -> Unit = { it.writeText("not a jar") }): File {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.json").writeText(
            """{"uuid":"$uuid","name":"$uuid","software":"paper","version":"$version","javaMajor":$javaMajor,"jar":"server.jar","memoryMb":512,"port":25565}"""
        )

        jar(File(directory, "server.jar"))

        return directory
    }

    /** A jar whose main class says it needs Java [major]. */
    private fun jarNeeding(major: Int): (File) -> Unit = { file ->
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "Main"
        }

        JarOutputStream(file.outputStream(), manifest).use { out ->
            out.putNextEntry(JarEntry("Main.class"))
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, (major + 44).toByte()))
            out.closeEntry()
        }
    }

    private fun startAndWait(downloads: FakeDownloads?, uuid: String): Seen {
        val registry = ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener, downloads)

        registry.load()

        registry.get(uuid)!!.start("tester")

        val deadline = System.currentTimeMillis() + 10_000

        while (System.currentTimeMillis() < deadline) {
            seen.lastOrNull { it.state == ServerProcessState.STOPPED }?.let { return it }

            Thread.sleep(20)
        }

        error("The start never ended; saw $seen")
    }

    @Test
    fun `a pinned major that is missing is downloaded before the start`() {
        server("pinned", 42, "1.21.4")

        val downloads = FakeDownloads(enabled = true, answer = "offline")

        val stopped = startAndWait(downloads, "pinned")

        assertEquals(listOf("pinned" to 42), downloads.asked)

        // STARTING while the download ran, then STOPPED -- never CRASHED, so nothing restarts it.
        assertEquals(ServerProcessState.STARTING, seen.first().state)
        assertFalse(seen.any { it.state == ServerProcessState.CRASHED })

        assertEquals(ServerProcess.REASON_JAVA_MISSING, stopped.reason!!.code)
        assertEquals(42, stopped.reason.javaMajor)
        assertEquals("No Java 42 runtime on this host; downloading it failed: offline", stopped.reason.message)
    }

    @Test
    fun `automatic asks for the jar's floor when it is above the ladder`() {
        server("floor", 0, "1.21.4", jarNeeding(43))

        val downloads = FakeDownloads(enabled = true, answer = "Java 43 is not available for linux/x64 from Temurin or Zulu")

        val stopped = startAndWait(downloads, "floor")

        assertEquals(listOf("floor" to 43), downloads.asked)
        assertEquals(43, stopped.reason!!.javaMajor)
    }

    @Test
    fun `with downloads off the start is refused and says so`() {
        server("off", 42, "1.21.4")

        val downloads = FakeDownloads(enabled = false, answer = null)

        val stopped = startAndWait(downloads, "off")

        assertTrue(downloads.asked.isEmpty())
        assertEquals("No Java 42 runtime on this host; automatic Java download is disabled", stopped.reason!!.message)
        assertEquals(ServerProcess.REASON_JAVA_MISSING, stopped.reason.code)

        // Refused straight from STOPPED, and still announced: Pano asked for a start.
        assertEquals(listOf(ServerProcessState.STOPPED), seen.map { it.state })
    }

    @Test
    fun `config carries java-auto-download, defaulting to on`() {
        assertTrue(NodeConfigStore.parse("node.name = x\n").javaAutoDownload)

        val off = NodeConfig(name = "x", javaAutoDownload = false)

        val rendered = NodeConfigStore.render(off)

        assertTrue(rendered.contains("java-auto-download"))
        assertFalse(NodeConfigStore.parse(rendered).javaAutoDownload)
    }

    @Test
    fun `the environment overrides java-auto-download`() {
        fun parse(value: String?) = NodeCli.parse(emptyArray()) { name ->
            if (name == NodeCli.ENV_JAVA_AUTO_DOWNLOAD) value else null
        }.javaAutoDownload

        assertNull(parse(null))
        assertEquals(false, parse("false"))
        assertEquals(false, parse("0"))
        assertEquals(true, parse("TRUE"))
        assertNull(parse(""))
        assertThrows(IllegalArgumentException::class.java) { parse("maybe") }
    }
}
