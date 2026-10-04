package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.PanoPluginConfig
import com.panomc.node.net.PanoPluginSpec
import com.panomc.node.net.PlatformUrls
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PanoPluginInstallerTest {
    @TempDir
    lateinit var serverDir: File

    private val spec = PanoPluginSpec(
        jarUrl = "https://example.test/pano-spigot-1.0.0-alpha.62.jar",
        targetDir = "plugins",
        configPath = "plugins/Pano/config.conf",
        config = PanoPluginConfig(
            host = "panomc.com",
            port = 443,
            ssl = true,
            token = "a.jwt.token",
            encryptionKey = "ZmFrZS1iYXNlNjQta2V5"
        )
    )

    @Test
    fun `renders a config the plugin can read back`() {
        val config = ConfigFactory.parseString(PanoPluginInstaller.render(spec, "pub" to "priv"))

        assertEquals(6, config.getInt("config-version"))
        assertTrue(config.getBoolean("await-pano-connection"))
        // Missing keys read as 0 in the plugin and earn a warning on every start, so they are written.
        assertEquals(25, config.getInt("heartbeat-interval"))
        assertEquals(75, config.getInt("heartbeat-timeout"))
        assertTrue(config.getBoolean("console.enabled"))
        assertEquals("panomc.com", config.getString("platform.host"))
        assertEquals(443, config.getInt("platform.port"))
        assertTrue(config.getBoolean("platform.ssl"))
        assertEquals("a.jwt.token", config.getString("platform.token"))
        assertEquals("ZmFrZS1iYXNlNjQta2V5", config.getString("platform.encryption-key"))
    }

    @Test
    fun `always writes a key pair, because the plugin never regenerates a missing one`() {
        val config = ConfigFactory.parseString(PanoPluginInstaller.render(spec))

        assertTrue(config.getString("public-key").isNotBlank())
        assertTrue(config.getString("private-key").isNotBlank())
    }

    @Test
    fun `generates a real pair when none is supplied`() {
        val first = ConfigFactory.parseString(PanoPluginInstaller.render(spec)).getString("private-key")
        val second = ConfigFactory.parseString(PanoPluginInstaller.render(spec)).getString("private-key")

        assertFalse(first == second)
    }

    @Test
    fun `keeps the published asset name`() {
        val name = PanoPluginInstaller.fileNameOf(
            URI.create("https://github.com/PanoMC/pano-mc-plugin/releases/download/v1.0.0/pano-spigot-1.0.0.jar")
        )

        assertEquals("pano-spigot-1.0.0.jar", name)
    }

    @Test
    fun `falls back rather than trusting a url that names no jar`() {
        assertEquals("pano.jar", PanoPluginInstaller.fileNameOf(URI.create("https://example.test/download")))
        assertEquals("pano.jar", PanoPluginInstaller.fileNameOf(URI.create("https://example.test/")))
    }

    // ------------------------------------------------------------------------- the jar on disk

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private fun installer() = PanoPluginInstaller(logger, PlatformUrls(NodeConfig()), ProcessRuntime())

    private val plugins get() = File(serverDir, "plugins")

    /** Every name in `plugins/` a plugin loader would pick up. */
    private fun loadableJars(): List<String> =
        plugins.listFiles().orEmpty().map { it.name }.filter { it.lowercase().endsWith(".jar") }

    private fun zipBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.yml"))
            zip.write("name: Pano\n".repeat(512).toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    /** Serves [path] with [handler] on loopback; nothing leaves the machine. */
    private fun serve(path: String, handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }

            createContext(path) { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            }

            start()
        }

    private fun HttpServer.url(path: String) = "http://127.0.0.1:${address.port}$path"

    @Test
    fun `downloads under a name no server loads`() {
        val part = PanoPluginInstaller.partNameOf("pano-spigot-1.0.0.jar")

        assertEquals(".pano-spigot-1.0.0.jar.part", part)
        assertFalse(part.lowercase().endsWith(".jar"))
    }

    @Test
    fun `the jar is not in plugins until all of it is`() {
        val jar = zipBytes()
        val halfSent = CountDownLatch(1)
        val finish = CountDownLatch(1)

        val server = serve("/pano-spigot-test.jar") { exchange ->
            exchange.sendResponseHeaders(200, jar.size.toLong())

            exchange.responseBody.write(jar, 0, jar.size / 2)
            exchange.responseBody.flush()

            halfSent.countDown()
            finish.await(10, TimeUnit.SECONDS)

            exchange.responseBody.write(jar, jar.size / 2, jar.size - jar.size / 2)
        }

        try {
            var outcome: PanoPluginInstaller.Outcome? = null

            val install = Thread {
                outcome = installer().install(serverDir, spec.copy(jarUrl = server.url("/pano-spigot-test.jar")))
            }.apply { start() }

            assertTrue(halfSent.await(10, TimeUnit.SECONDS), "the download never started")

            val part = File(plugins, ".pano-spigot-test.jar.part")
            val deadline = System.currentTimeMillis() + 10_000

            while (!part.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }

            // Half a jar on disk, and nothing a server starting now would try to open.
            assertTrue(part.exists(), "the download is not going to its .part file")
            assertEquals(emptyList<String>(), loadableJars())

            finish.countDown()
            install.join(10_000)

            assertEquals(PanoPluginInstaller.Outcome.Installed("pano-spigot-test.jar"), outcome)
            assertEquals(listOf("pano-spigot-test.jar"), loadableJars())
            assertTrue(Downloader.isZip(File(plugins, "pano-spigot-test.jar")))
            assertEquals(jar.size.toLong(), File(plugins, "pano-spigot-test.jar").length())
            assertFalse(part.exists())
            assertTrue(File(serverDir, "plugins/Pano/config.conf").isFile)
        } finally {
            finish.countDown()
            server.stop(0)
        }
    }

    @Test
    fun `a download that is not a jar leaves nothing behind`() {
        val server = serve("/pano-spigot-test.jar") { exchange ->
            val page = "<html>Bad gateway</html>".toByteArray()

            exchange.sendResponseHeaders(200, page.size.toLong())
            exchange.responseBody.write(page)
        }

        try {
            val outcome = installer().install(serverDir, spec.copy(jarUrl = server.url("/pano-spigot-test.jar")))

            assertTrue(outcome is PanoPluginInstaller.Outcome.Failed, "was $outcome")
            assertEquals(emptyList<String>(), plugins.list().orEmpty().toList())
            assertFalse(File(serverDir, "plugins/Pano/config.conf").exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a failed download leaves the jar that was there alone`() {
        plugins.mkdirs()

        val previous = File(plugins, "pano-spigot-test.jar").apply { writeBytes(zipBytes()) }
        val before = previous.readBytes()

        val server = serve("/pano-spigot-test.jar") { exchange ->
            exchange.sendResponseHeaders(503, -1)
        }

        try {
            val outcome = installer().install(serverDir, spec.copy(jarUrl = server.url("/pano-spigot-test.jar")))

            assertTrue(outcome is PanoPluginInstaller.Outcome.Failed, "was $outcome")
            assertEquals(listOf("pano-spigot-test.jar"), plugins.list().orEmpty().toList())
            assertTrue(before.contentEquals(previous.readBytes()))
        } finally {
            server.stop(0)
        }
    }
}
