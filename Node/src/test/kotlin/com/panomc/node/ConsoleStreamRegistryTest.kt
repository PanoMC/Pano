package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors

/**
 * A console Pano is watching keeps streaming across the process objects of one server.
 *
 * The bug this pins down: a Spigot server was reinstalled (a new [ServerProcess] under the same
 * uuid) while its console page was open, and its console stayed blank after the start until the
 * page was reloaded, because the streaming switch lived on the object that was replaced.
 */
class ConsoleStreamRegistryTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private fun registry() = ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)

    private fun register(registry: ServerRegistry, uuid: String) =
        registry.register(uuid, File(dataDir, "servers/$uuid").apply { mkdirs() }, ServerSpec(uuid = uuid, software = "spigot"))

    @Test
    fun `a server registered again streams the console Pano was already watching`() {
        val registry = registry()

        register(registry, "abc")
        registry.setConsoleStreaming("abc", true)

        // A reinstall: a new process object under the same uuid.
        val reinstalled = register(registry, "abc")

        assertTrue(reinstalled.console.isStreaming())
    }

    @Test
    fun `a console asked for before its server exists streams once it is registered`() {
        val registry = registry()

        // A failed install: the node has no server, but the page is open.
        assertNull(registry.setConsoleStreaming("abc", true))

        assertTrue(register(registry, "abc").console.isStreaming())
    }

    @Test
    fun `a console nobody watches, or one Pano stopped, is not streamed`() {
        val registry = registry()

        assertFalse(register(registry, "quiet").console.isStreaming())

        registry.setConsoleStreaming("abc", true)
        registry.setConsoleStreaming("abc", false)

        assertFalse(register(registry, "abc").console.isStreaming())
    }

    @Test
    fun `a lost connection forgets every stream`() {
        val registry = registry()
        val server = register(registry, "abc")

        registry.setConsoleStreaming("abc", true)
        registry.stopConsoleStreams()

        assertFalse(server.console.isStreaming())
        assertFalse(register(registry, "abc").console.isStreaming())
    }
}
