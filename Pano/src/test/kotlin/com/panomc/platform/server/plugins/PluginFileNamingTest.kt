package com.panomc.platform.server.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginFileNamingTest {
    @Test
    fun `accepts a jar name and refuses everything else`() {
        assertTrue(PluginFileNaming.isJarName("EssentialsX-2.21.2.jar"))
        assertFalse(PluginFileNaming.isJarName("../evil.jar"))
        assertFalse(PluginFileNaming.isJarName("plugins/evil.jar"))
        assertFalse(PluginFileNaming.isJarName("start.sh"))
        assertFalse(PluginFileNaming.isJarName(".jar"))
        assertFalse(PluginFileNaming.isJarName(null))
    }

    @Test
    fun `sanitises what a source published`() {
        assertEquals("EssentialsX-2.21.2.jar", PluginFileNaming.sanitise("EssentialsX-2.21.2.jar", "fallback"))
        assertEquals("evil.jar", PluginFileNaming.sanitise("../../evil.jar", "fallback"))
        assertEquals("a-b.jar", PluginFileNaming.sanitise("a\nb.jar", "fallback"))
        assertEquals("fallback.jar", PluginFileNaming.sanitise(null, "fallback"))
        assertEquals("fallback.jar", PluginFileNaming.sanitise("   ", "fallback"))
    }

    @Test
    fun `strips build identifiers to find the plugin behind a file name`() {
        assertEquals("essentialsx", PluginFileNaming.baseNameOf("EssentialsX-2.21.2.jar"))
        assertEquals("essentialsx", PluginFileNaming.baseNameOf("EssentialsX-2.22.0.jar"))
        assertEquals("viaversion", PluginFileNaming.baseNameOf("ViaVersion-5.12.1-SNAPSHOT.jar"))
        assertEquals("worldedit-bukkit", PluginFileNaming.baseNameOf("worldedit-bukkit-7.3.0.jar"))
    }

    @Test
    fun `finds the build an install replaces`() {
        val existing = listOf("EssentialsX-2.21.2.jar", "ViaVersion-5.0.0.jar", "pano-spigot-1.0.0.jar")

        assertEquals("EssentialsX-2.21.2.jar", PluginFileNaming.replacementFor("EssentialsX-2.22.0.jar", existing))
        assertNull(PluginFileNaming.replacementFor("LuckPerms-5.4.jar", existing))
        // Re-installing the identical file is an overwrite, not a replacement to delete afterwards.
        assertNull(PluginFileNaming.replacementFor("EssentialsX-2.21.2.jar", existing))
    }

    @Test
    fun `recognises the pano plugin jar`() {
        assertTrue(PluginFileNaming.isPanoPluginJar("pano-spigot-1.2.3.jar"))
        assertTrue(PluginFileNaming.isPanoPluginJar("Pano.jar"))
        assertFalse(PluginFileNaming.isPanoPluginJar("panorama.jar"))
        assertFalse(PluginFileNaming.isPanoPluginJar("EssentialsX-2.21.2.jar"))
    }
}
