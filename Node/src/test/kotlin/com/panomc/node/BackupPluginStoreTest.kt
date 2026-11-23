package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.files.BackupExcludeMatcher
import com.panomc.node.files.BackupService
import com.panomc.node.files.TransferService
import com.panomc.node.files.VirtualArchive
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.BackupCreateMessage
import com.panomc.node.net.BackupDeleteMessage
import com.panomc.node.net.PlatformConnection
import com.panomc.node.net.TransferPullMessage
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.task.TaskReporter
import com.panomc.node.util.NodeLogger
import com.sun.net.httpserver.HttpServer
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Backups the Pano plugin took live in `<server>/backups/`, not in the node's `<data>/backups/<uuid>/`.
 * Pano lists both, so the node serves, restores and deletes both, and never puts one inside the other.
 */
class BackupPluginStoreTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))
    private val vertx = Vertx.vertx()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }

    @AfterEach
    fun close() {
        vertx.close()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private val serverDir get() = File(dataDir, "servers/s1")

    private fun registry(): ServerRegistry =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener).apply {
            register("s1", serverDir.apply { mkdirs() }, ServerSpec(uuid = "s1"))
        }

    private fun service(registry: ServerRegistry = registry()): BackupService {
        val connection = PlatformConnection(vertx, logger, NodeConfig())

        return BackupService(dataDir, registry, TaskReporter(connection), connection, logger)
    }

    private fun create(backups: BackupService, id: String, mode: String) {
        assertNull(backups.create(BackupCreateMessage(serverUuid = "s1", taskId = "t-$id", backupId = id, name = id, mode = mode, scope = "ALL")))
    }

    /** Takes backups with the node, then moves its whole store into the server folder, which is the plugin's layout. */
    private fun pluginStoreWith(vararg backups: Pair<String, String>): File {
        File(serverDir, "world").mkdirs()
        File(serverDir, "world/level.dat").writeText("level")
        File(serverDir, "server.properties").writeText("motd=x\n")

        val service = service()

        backups.forEach { (id, mode) -> create(service, id, mode) }

        val store = File(serverDir, "backups")

        assertTrue(File(dataDir, "backups/s1").renameTo(store))

        return store
    }

    private fun entries(write: (java.io.OutputStream) -> Unit): Set<String> {
        val bytes = ByteArrayOutputStream().also(write).toByteArray()
        val names = mutableSetOf<String>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }

        return names
    }

    @Test
    fun `a snapshot the plugin took is served from the server folder`() {
        pluginStoreWith("11111111-1111-1111-1111-111111111111" to "SNAPSHOT")

        val backups = service()
        val archive = backups.resolveVirtualArchive("s1", "@backup/11111111-1111-1111-1111-111111111111")

        assertNotNull(archive)
        assertTrue("world/level.dat" in entries { archive!!.write(it, Long.MAX_VALUE) })

        // An id that is in neither store is not found, and nothing outside the stores is reachable.
        assertNull(backups.resolveVirtualArchive("s1", "@backup/22222222-2222-2222-2222-222222222222"))
        assertNull(backups.resolveVirtualArchive("s1", "@backup/../world"))
    }

    @Test
    fun `a FULL zip the plugin took is served from the server folder`() {
        val store = pluginStoreWith("33333333-3333-3333-3333-333333333333" to "FULL")

        val file = service().resolveVirtual("s1", "@backup/33333333-3333-3333-3333-333333333333")

        assertEquals(File(store, "33333333-3333-3333-3333-333333333333.zip").canonicalFile, file?.canonicalFile)
    }

    @Test
    fun `deleting a plugin backup removes exactly that one`() {
        val store = pluginStoreWith(
            "44444444-4444-4444-4444-444444444444" to "SNAPSHOT",
            "55555555-5555-5555-5555-555555555555" to "SNAPSHOT",
            "66666666-6666-6666-6666-666666666666" to "FULL"
        )

        val backups = service()

        assertTrue(backups.delete(BackupDeleteMessage(serverUuid = "s1", backupId = "44444444-4444-4444-4444-444444444444")).getBoolean("ok"))
        assertTrue(backups.delete(BackupDeleteMessage(serverUuid = "s1", backupId = "66666666-6666-6666-6666-666666666666")).getBoolean("ok"))

        assertFalse(File(store, "44444444-4444-4444-4444-444444444444.json").exists())
        assertFalse(File(store, "repo/snapshots/44444444-4444-4444-4444-444444444444.json.gz").exists())
        assertFalse(File(store, "66666666-6666-6666-6666-666666666666.zip").exists())

        // The other snapshot still restores whole: its chunks were kept.
        assertTrue(File(store, "55555555-5555-5555-5555-555555555555.json").isFile)
        val archive = backups.resolveVirtualArchive("s1", "@backup/55555555-5555-5555-5555-555555555555")
        assertTrue("world/level.dat" in entries { archive!!.write(it, Long.MAX_VALUE) })
    }

    @Test
    fun `a node backup leaves the plugin's backups out`() {
        pluginStoreWith("77777777-7777-7777-7777-777777777777" to "SNAPSHOT")

        val backups = service()

        create(backups, "88888888-8888-8888-8888-888888888888", "SNAPSHOT")

        val names = entries { backups.resolveVirtualArchive("s1", "@backup/88888888-8888-8888-8888-888888888888")!!.write(it, Long.MAX_VALUE) }

        assertTrue("world/level.dat" in names)
        assertTrue(names.none { it.startsWith("backups/") }, names.toString())
    }

    @Test
    fun `a backups folder of another tool stays in the backup`() {
        val other = File(serverDir, "backups").apply { mkdirs() }

        File(other, "world-2026.zip").writeText("not pano")

        assertFalse(BackupExcludeMatcher.isPluginBackupStore(other))
        assertFalse(BackupExcludeMatcher(null, serverDir).matches("backups/world-2026.zip"))

        File(other, "repo").mkdirs()
        File(other, "repo/config.json").writeText("{}")

        assertTrue(BackupExcludeMatcher(null, serverDir).matches("backups/world-2026.zip"))
    }

    @Test
    fun `a snapshot larger than a file transfer is streamed, not refused`() {
        val received = ByteArrayOutputStream()
        var error: String? = null

        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        http.createContext("/") { exchange ->
            error = exchange.requestHeaders.getFirst(TransferService.ERROR_HEADER)
            exchange.requestBody.use { it.copyTo(received) }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        http.start()

        try {
            val config = NodeConfig(platformUrl = "http://127.0.0.1:${http.address.port}", token = "t")

            // Announces more than the old 1 GiB ceiling; writes a little, which is all the test needs.
            val huge = VirtualArchive("big.zip", TransferService.MAX_TRANSFER_BYTES * 6) { output, max ->
                assertEquals(Long.MAX_VALUE, max)
                output.write("PK-data".toByteArray())
            }

            val transfers = TransferService(registry(), config, logger, virtualArchive = { _, _ -> huge })

            transfers.pull(TransferPullMessage(ticket = "ticket1", serverUuid = "s1", path = "@backup/big"))

            assertNull(error)
            assertEquals("PK-data", received.toString())
        } finally {
            http.stop(0)
        }
    }
}
