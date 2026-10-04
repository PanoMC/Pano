package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaDownloads
import com.panomc.node.net.InstallPanoPluginMessage
import com.panomc.node.net.PanoPluginConfig
import com.panomc.node.net.PanoPluginSpec
import com.panomc.node.net.PlatformUrls
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.task.PluginInstallService
import com.panomc.node.task.TaskSink
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `INSTALL_PANO_PLUGIN` carrying an import's start (`startAfter`).
 *
 * Pano used to start an imported server the moment the import finished, while this install was
 * still downloading the plugin into it; on a remote host the server won that race and ran without
 * the plugin. The start now rides on the install, and these are its rules: it happens after the
 * install has ended, done or failed alike; it does not happen when nobody asked for it (an older
 * Pano, which still sends its own START); and it never starts a server that is already up.
 *
 * No Minecraft process is started: the start is recorded rather than run, except in the one case
 * that needs a server really in STARTING, which a Java download that never finishes holds there.
 */
class PanoPluginStartAfterTest {
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

    /** Terminal frames and starts, in the order they happened. */
    private val events = CopyOnWriteArrayList<String>()

    private val sink = object : TaskSink {
        override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {}

        override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
            events.add("DONE")
        }

        override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
            events.add("FAILED")
        }
    }

    private fun registry(downloads: JavaDownloads? = null) =
        ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener, downloads)

    private fun service(registry: ServerRegistry): PluginInstallService {
        val urls = PlatformUrls(NodeConfig())

        return PluginInstallService(registry, sink, PanoPluginInstaller(logger, urls, ProcessRuntime()), urls, logger) {
            events.add("START ${it.uuid}")
        }
    }

    /** A registered, stopped server with a jar-named file, which is all a start ever looks at first. */
    private fun server(registry: ServerRegistry, uuid: String = SERVER, javaMajor: Int = ServerSpec.AUTO_JAVA_MAJOR): ServerProcess {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.jar").writeText("not a jar")

        return registry.register(
            uuid,
            directory,
            ServerSpec(uuid = uuid, name = uuid, software = "paper", version = "1.21.4", javaMajor = javaMajor, jar = "server.jar")
        )
    }

    /** The Pano plugin as Pano could have sent it: a real zip, fetched through the `file:` path. */
    private fun pluginJar(): File = File(dataDir, "pano-spigot-test.jar").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.yml"))
            zip.write("name: Pano\n".toByteArray())
            zip.closeEntry()
        }
    }

    private fun message(jarUrl: String, startAfter: Boolean?) = InstallPanoPluginMessage(
        serverUuid = SERVER,
        taskId = "task-1",
        spec = PanoPluginSpec(
            jarUrl = jarUrl,
            targetDir = "plugins",
            configPath = "plugins/Pano/config.conf",
            config = PanoPluginConfig(
                host = "127.0.0.1",
                port = 8080,
                ssl = false,
                token = "a.jwt.token",
                encryptionKey = "ZmFrZS1iYXNlNjQta2V5"
            )
        ),
        startAfter = startAfter
    )

    private val missingJar get() = File(dataDir, "nowhere/pano.jar").toURI().toString()

    @Test
    fun `startAfter starts the server once the install is done`() {
        val registry = registry()
        val server = server(registry)

        service(registry).installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = true))

        assertEquals(listOf("DONE", "START $SERVER"), events)
        assertTrue(File(server.directory, "plugins/pano-spigot-test.jar").isFile)
    }

    @Test
    fun `startAfter starts the server when the install failed as well`() {
        val registry = registry()
        server(registry)

        service(registry).installPanoPlugin(message(missingJar, startAfter = true))

        // A server that wants to run is not kept down by a download that did not work.
        assertEquals(listOf("FAILED", "START $SERVER"), events)
    }

    @Test
    fun `without startAfter the install only installs`() {
        val registry = registry()
        server(registry)

        val service = service(registry)

        // An older Pano sends no startAfter at all, and still sends its own START.
        service.installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = null))
        service.installPanoPlugin(message(missingJar, startAfter = null))
        service.installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = false))

        assertEquals(listOf("DONE", "FAILED", "DONE"), events)
    }

    @Test
    fun `an agent's first start and Pano's startAfter are one start`() {
        val registry = registry()
        server(registry)

        val service = service(registry)

        // The daemon passes true when the install claimed an agent's first start; with Pano asking
        // as well there is still one start, and with Pano silent the claim alone is enough.
        service.installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = true), startAfter = true)
        service.installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = null), startAfter = true)

        assertEquals(listOf("DONE", "START $SERVER", "DONE", "START $SERVER"), events)
    }

    @Test
    fun `a server that is already up is not started again`() {
        val held = CountDownLatch(1)
        val downloading = CountDownLatch(1)

        // A pinned Java no host has, and a download that waits: the start sits in STARTING.
        val downloads = object : JavaDownloads {
            override val enabled = true

            override fun installForStart(serverUuid: String, major: Int): String? {
                downloading.countDown()
                held.await(10, TimeUnit.SECONDS)

                return "offline"
            }
        }

        val registry = registry(downloads)
        val server = server(registry, javaMajor = 42)

        try {
            server.start("tester")

            assertTrue(downloading.await(10, TimeUnit.SECONDS), "the start never reached its Java download")
            assertEquals(ServerProcessState.STARTING, server.state)

            service(registry).installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = true))

            assertEquals(listOf("DONE"), events)
        } finally {
            held.countDown()

            // Let the refused start land before the temporary directory goes.
            val deadline = System.currentTimeMillis() + 10_000

            while (server.state == ServerProcessState.STARTING && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `an install for a server this node does not hold starts nothing`() {
        val registry = registry()

        service(registry).installPanoPlugin(message(pluginJar().toURI().toString(), startAfter = true))

        assertEquals(listOf("FAILED"), events)
        assertFalse(events.any { it.startsWith("START") })
    }

    private companion object {
        const val SERVER = "0b7c8f2e-5a4d-4a8e-9a57-2f0c1d3e4b5a"
    }
}
