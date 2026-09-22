package com.panomc.platform.server.plugins

import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.event.PanoPluginUpdateResultEvent
import com.panomc.platform.server.message.PanoPluginUpdateMessage
import com.panomc.platform.util.TextUtil.convertToSnakeCase
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The parts of serving a Pano plugin update that can be checked without GitHub: which build each
 * software gets, what the served file is called, what its checksum is, and the wire names of the
 * two messages that carry it.
 */
class PanoPluginJarProviderTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `each software is served its own module's jar`() {
        listOf(ServerType.PAPER, ServerType.SPIGOT, ServerType.BUKKIT, ServerType.PURPUR, ServerType.FOLIA).forEach {
            assertEquals("spigot", ManagedPluginJarResolver.platformOf(it), "$it runs the Spigot build")
        }

        assertEquals("velocity", ManagedPluginJarResolver.platformOf(ServerType.VELOCITY))
        assertEquals("bungeecord", ManagedPluginJarResolver.platformOf(ServerType.BUNGEECORD))
        assertEquals("bungeecord", ManagedPluginJarResolver.platformOf(ServerType.WATERFALL))
        assertEquals("fabric", ManagedPluginJarResolver.platformOf(ServerType.FABRIC))
        assertEquals("fabric", ManagedPluginJarResolver.platformOf(ServerType.QUILT))

        listOf(ServerType.VANILLA, ServerType.FORGE, ServerType.NEOFORGE).forEach {
            assertEquals(null, ManagedPluginJarResolver.platformOf(it), "$it has no Pano plugin")
        }
    }

    @Test
    fun `a release asset keeps its own name`() {
        assertEquals(
            "pano-spigot-1.0.0-alpha.63.jar",
            PanoPluginJarProvider.assetFileName(
                "https://github.com/PanoMC/pano-mc-plugin/releases/download/v1.0.0-alpha.63/pano-spigot-1.0.0-alpha.63.jar",
                "spigot",
                "1.0.0-alpha.63"
            )
        )
    }

    @Test
    fun `anything that is not this platform's asset gets a name built from Pano's own values`() {
        // Another platform's asset: serving it under that name would be a lie about its content.
        assertEquals(
            "pano-velocity-1.0.0-alpha.63.jar",
            PanoPluginJarProvider.assetFileName("https://example.com/pano-spigot-1.0.0-alpha.63.jar", "velocity", "1.0.0-alpha.63")
        )

        assertEquals(
            "pano-fabric-latest.jar",
            PanoPluginJarProvider.assetFileName("https://example.com/download?id=7", "fabric", null)
        )

        // A version is only ever used after everything that could make it a path is stripped.
        assertEquals(
            "pano-bungeecord-evil1.0.jar",
            PanoPluginJarProvider.assetFileName("not a url at all", "bungeecord", "../evil/1.0")
        )
        assertTrue(PluginFileNaming.isJarName(PanoPluginJarProvider.assetFileName("x", "spigot", "a/b\\c")))
    }

    @Test
    fun `the checksum is the file's SHA-256 and a jar is recognised by its zip header`() {
        val text = File(directory, "hello.txt").apply { writeText("hello") }

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            PanoPluginJarProvider.sha256Of(text)
        )
        assertFalse(PanoPluginJarProvider.isZip(text))

        val jar = File(directory, "pano-spigot-1.0.0.jar")

        ZipOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("plugin.yml"))
            out.write("name: Pano\n".toByteArray())
            out.closeEntry()
        }

        assertTrue(PanoPluginJarProvider.isZip(jar))
        assertFalse(PanoPluginJarProvider.isZip(File(directory, "missing.jar")))
    }

    @Test
    fun `the push and its result keep the agreed wire names and fields`() {
        val message = PanoPluginUpdateMessage(
            eventId = "e-1",
            taskId = "t-1",
            url = PanoPluginUpdateService.SERVER_JAR_PATH,
            sha256 = "abc",
            size = 42L,
            fileName = "pano-spigot-1.0.0-alpha.63.jar",
            version = "1.0.0-alpha.63"
        )

        assertEquals("PANO_PLUGIN_UPDATE", message.getResponseName())

        val encoded = JsonObject(message.encode())

        assertEquals("PANO_PLUGIN_UPDATE", encoded.getString("event"))
        // Every PlatformMessage also carries a `responseName` Jackson reads off its getter, which the
        // plugin ignores; what matters is that none of the agreed fields is missing or renamed.
        assertTrue(
            encoded.fieldNames().containsAll(
                setOf("event", "eventId", "taskId", "url", "sha256", "size", "fileName", "version")
            ),
            encoded.encode()
        )
        assertEquals("/api/server/pano-plugin/jar", encoded.getString("url"))
        assertEquals(42L, encoded.getLong("size"))

        // ServerEvent derives the name it listens for from the class name exactly like this; a
        // rename of the class would silently stop the plugin's answers from being heard.
        assertEquals(
            "PANO_PLUGIN_UPDATE_RESULT",
            PanoPluginUpdateResultEvent::class.java.simpleName.replace("Event", "").convertToSnakeCase().uppercase()
        )
    }
}
