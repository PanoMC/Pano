package com.panomc.platform.server.software

import com.panomc.platform.server.ServerType
import com.panomc.platform.server.software.dto.SoftwareCatalogEntry
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * What the provider table promises the rest of Pano, without asking an upstream anything.
 *
 * `serverTypeOf` is how a software name becomes a [ServerType], and the type is what decides
 * everything that differs for a proxy (no worlds, no tick loop), so a software missing from this
 * table is one Pano treats as the wrong kind of server however well it installs.
 */
class ServerSoftwareCatalogProvidersTest {
    private lateinit var vertx: Vertx
    private lateinit var catalog: ServerSoftwareCatalog

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()

        // Constructed but never used: nothing here leaves the process.
        catalog = ServerSoftwareCatalog(WebClient.create(vertx), LoggerFactory.getLogger(javaClass))
    }

    @AfterEach
    fun tearDown() {
        vertx.close()
    }

    @Test
    fun `spigot installs a spigot server`() {
        assertEquals(ServerType.SPIGOT, catalog.serverTypeOf("spigot"))
    }

    @Test
    fun `bungeecord is a proxy`() {
        val type = catalog.serverTypeOf("bungeecord")

        assertEquals(ServerType.BUNGEECORD, type)
        assertTrue(type!!.isProxy)
    }

    @Test
    fun `spigot is not a proxy`() {
        assertEquals(false, catalog.serverTypeOf("spigot")!!.isProxy)
    }

    @Test
    fun `the software list carries the note the wizard badges read`() {
        // "build" and "jenkins" are what the create wizard turns into "compiled on the node" and
        // "latest CI build"; a list that drops the field is a list of unlabelled options.
        val entry = SoftwareCatalogEntry("spigot", "Spigot", false, listOf("1.21.8"), "1.21.8", "build")

        assertEquals("build", entry.toJsonObject().getString("note"))
        assertTrue(entry.toJsonObject().containsKey("note"))
        assertNull(SoftwareCatalogEntry("paper", "Paper", true, emptyList()).toJsonObject().getString("note"))
    }

    @Test
    fun `an unknown software has no type at all`() {
        assertNull(catalog.serverTypeOf("bukkit"))
        assertNull(catalog.serverTypeOf(""))
    }
}
