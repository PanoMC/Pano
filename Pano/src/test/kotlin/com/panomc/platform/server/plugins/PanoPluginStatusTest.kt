package com.panomc.platform.server.plugins

import com.panomc.platform.node.ManagedPluginJarResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Statistics table's "update available" (§2.4.26): a comparison only between two real versions.
 */
class PanoPluginStatusTest {
    @Test
    fun `a newer release is an update, the same or an older one is not`() {
        assertEquals(true, PanoPluginStatus.updateAvailable("1.0.0-alpha.62", "1.0.0-alpha.100"))
        assertEquals(false, PanoPluginStatus.updateAvailable("1.0.0-alpha.62", "1.0.0-alpha.62"))
        assertEquals(false, PanoPluginStatus.updateAvailable("1.0.0-alpha.100", "1.0.0-alpha.62"))
    }

    @Test
    fun `prereleases are ordered the way semantic-release cuts them`() {
        assertEquals(true, PanoPluginStatus.updateAvailable("1.0.0-alpha.99", "1.0.0-beta.1"))
        assertEquals(true, PanoPluginStatus.updateAvailable("1.0.0-beta.40", "1.0.0"))
        assertEquals(true, PanoPluginStatus.updateAvailable("1.0.0", "1.1.0-alpha.1"))
        assertEquals(true, PanoPluginStatus.updateAvailable("v1.0.0", "1.0.1"), "a leading v is tolerated")
    }

    @Test
    fun `a development jar, no plugin, or no lookup yet is no answer`() {
        assertNull(PanoPluginStatus.updateAvailable("local-build", "1.0.0-alpha.62"))
        assertNull(PanoPluginStatus.updateAvailable("1.0.0-alpha.62", ManagedPluginJarResolver.LOCAL_BUILD))
        assertNull(PanoPluginStatus.updateAvailable(null, "1.0.0-alpha.62"))
        assertNull(PanoPluginStatus.updateAvailable("1.0.0-alpha.62", null))
        assertNull(PanoPluginStatus.updateAvailable("dev", "whatever"))
    }

    @Test
    fun `the panel gets all three, nulls included`() {
        val json = PanoPluginStatus("1.0.0-alpha.62", "1.0.0-alpha.70").toJsonObject()

        assertEquals("1.0.0-alpha.62", json.getString("version"))
        assertEquals("1.0.0-alpha.70", json.getString("latestVersion"))
        assertTrue(json.getBoolean("updateAvailable"))

        val unknown = PanoPluginStatus(null, null).toJsonObject()

        assertTrue(unknown.containsKey("updateAvailable"))
        assertNull(unknown.getBoolean("updateAvailable"))
    }

    @Test
    fun `a release tag is the version its jars report`() {
        assertEquals("1.0.0-alpha.62", ManagedPluginJarResolver.releaseVersion("v1.0.0-alpha.62"))
        assertEquals("1.0.0", ManagedPluginJarResolver.releaseVersion("1.0.0"))
        assertNull(ManagedPluginJarResolver.releaseVersion(" "))
        assertNull(ManagedPluginJarResolver.releaseVersion(null))
        assertFalse(ManagedPluginJarResolver.VERSION_TTL_MS < ManagedPluginJarResolver.CACHE_TTL_MS)
    }
}
