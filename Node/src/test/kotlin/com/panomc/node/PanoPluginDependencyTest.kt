package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.net.PanoPluginConfig
import com.panomc.node.net.PanoPluginDependency
import com.panomc.node.net.PanoPluginSpec
import com.panomc.node.net.PlatformUrls
import com.panomc.node.server.ModPresence
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.util.NodeLogger
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PanoPluginDependencyTest {
    @TempDir
    lateinit var serverDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private fun installer() = PanoPluginInstaller(logger, PlatformUrls(NodeConfig()), ProcessRuntime())

    private val mods get() = File(serverDir, "mods")

    private fun jar(entry: String, text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private val panoMod = jar("fabric.mod.json", """{"id":"pano","depends":{"fabric-api":"*"}}""")
    private val fabricApi = jar("fabric.mod.json", """{"id":"fabric-api","version":"0.146.1+26.1.2"}""")

    private fun sha512(bytes: ByteArray) = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves each path's bytes on loopback and counts the requests per path. */
    private fun serve(files: Map<String, ByteArray>, hits: MutableMap<String, AtomicInteger>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }

            files.forEach { (path, bytes) ->
                hits[path] = AtomicInteger()

                createContext(path) { exchange ->
                    hits.getValue(path).incrementAndGet()

                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    exchange.close()
                }
            }

            start()
        }

    private fun HttpServer.url(path: String) = "http://127.0.0.1:${address.port}$path"

    private fun spec(server: HttpServer, dependency: PanoPluginDependency) = PanoPluginSpec(
        jarUrl = server.url("/pano-fabric-1.0.0.jar"),
        targetDir = "mods",
        configPath = "config/pano/config.conf",
        config = PanoPluginConfig(host = "127.0.0.1", port = 8088, ssl = false, token = "a.jwt", encryptionKey = "a2V5"),
        dependencies = listOf(dependency)
    )

    private fun fabricApiDependency(server: HttpServer, sha512: String? = sha512(fabricApi)) = PanoPluginDependency(
        modId = "fabric-api",
        name = "Fabric API",
        downloadUrl = server.url("/fabric-api-0.146.1+26.1.2.jar"),
        filename = "fabric-api-0.146.1+26.1.2.jar",
        sha512 = sha512
    )

    private fun files() = mapOf("/pano-fabric-1.0.0.jar" to panoMod, "/fabric-api-0.146.1+26.1.2.jar" to fabricApi)

    private fun jarsInMods() = mods.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".jar") }.sorted()

    @Test
    fun `puts Fabric API next to the Pano mod when the server has none`() {
        val hits = mutableMapOf<String, AtomicInteger>()
        val server = serve(files(), hits)

        try {
            val outcome = installer().install(serverDir, spec(server, fabricApiDependency(server)))

            assertTrue(outcome is PanoPluginInstaller.Outcome.Installed, outcome.toString())
            assertEquals(listOf("fabric-api-0.146.1+26.1.2.jar", "pano-fabric-1.0.0.jar"), jarsInMods())
            assertTrue(File(serverDir, "config/pano/config.conf").isFile)
            assertTrue(mods.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `leaves a Fabric API the server already has alone, whatever its file is called`() {
        mods.mkdirs()
        File(mods, "FabricAPI.jar").writeBytes(fabricApi)

        val hits = mutableMapOf<String, AtomicInteger>()
        val server = serve(files(), hits)

        try {
            val outcome = installer().install(serverDir, spec(server, fabricApiDependency(server)))

            assertTrue(outcome is PanoPluginInstaller.Outcome.Installed, outcome.toString())
            assertEquals(0, hits.getValue("/fabric-api-0.146.1+26.1.2.jar").get())
            assertEquals(listOf("FabricAPI.jar", "pano-fabric-1.0.0.jar"), jarsInMods())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `leaves the Pano mod out when Fabric API is missing and cannot be downloaded`() {
        val hits = mutableMapOf<String, AtomicInteger>()
        val server = serve(files(), hits)

        try {
            val outcome = installer().install(
                serverDir,
                spec(server, PanoPluginDependency(modId = "fabric-api", name = "Fabric API"))
            )

            assertTrue(outcome is PanoPluginInstaller.Outcome.Failed, outcome.toString())
            assertTrue((outcome as PanoPluginInstaller.Outcome.Failed).error.contains("Fabric API"))
            assertEquals(emptyList<String>(), jarsInMods())
            assertFalse(File(serverDir, "config/pano/config.conf").exists())
            assertEquals(0, hits.getValue("/pano-fabric-1.0.0.jar").get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a download that does not match its checksum installs neither jar`() {
        val hits = mutableMapOf<String, AtomicInteger>()
        val server = serve(files(), hits)

        try {
            val outcome = installer().install(serverDir, spec(server, fabricApiDependency(server, sha512 = "00".repeat(64))))

            assertTrue(outcome is PanoPluginInstaller.Outcome.Failed, outcome.toString())
            assertEquals(emptyList<String>(), jarsInMods())
            assertTrue(mods.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a mod is recognised by its id or by what it provides, and only when it is loaded`() {
        mods.mkdirs()

        File(mods, "qfapi.jar").writeBytes(
            jar("quilt.mod.json", """{"quilt_loader":{"id":"quilted_fabric_api","provides":[{"id":"fabric-api"}]}}""")
        )
        File(mods, "other.jar").writeBytes(jar("fabric.mod.json", """{"id":"other","provides":["legacy-other"]}"""))
        File(mods, "lithium.jar.disabled").writeBytes(jar("fabric.mod.json", """{"id":"lithium"}"""))
        File(mods, "broken.jar").writeText("not a zip")

        assertTrue(ModPresence.isInstalled(mods, "fabric-api"))
        assertTrue(ModPresence.isInstalled(mods, "quilted_fabric_api"))
        assertTrue(ModPresence.isInstalled(mods, "legacy-other"))
        assertFalse(ModPresence.isInstalled(mods, "lithium"))
        assertFalse(ModPresence.isInstalled(File(serverDir, "nowhere"), "fabric-api"))
    }
}
