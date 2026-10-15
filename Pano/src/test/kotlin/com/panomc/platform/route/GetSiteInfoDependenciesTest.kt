package com.panomc.platform.route

import com.panomc.platform.route.api.GetSiteInfoAPI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.pf4j.DefaultPluginDescriptor
import org.pf4j.PluginDependency

/** P-7.1: siteInfo plugin entries carry the dependency ids, [] when none. */
class GetSiteInfoDependenciesTest {
    private fun descriptor(id: String, vararg deps: String) =
        DefaultPluginDescriptor(id, "", "", "1.2.3", "", "", "").also { d ->
            deps.forEach { d.addDependency(PluginDependency(it)) }
        }

    @Test
    fun `dependent lists its dependency`() {
        val info = GetSiteInfoAPI.pluginInfo(descriptor("dependent", "dependency"), "hash")

        assertEquals(listOf("dependency"), info["dependencies"])
        assertEquals("1.2.3", info["version"])
        assertEquals("hash", info["uiHash"])
    }

    @Test
    fun `optional dependency is included by id`() {
        val info = GetSiteInfoAPI.pluginInfo(descriptor("dependent", "opt?"), "h")

        assertEquals(listOf("opt"), info["dependencies"])
    }

    @Test
    fun `plugin without dependencies gets an empty list`() {
        assertEquals(emptyList<String>(), GetSiteInfoAPI.pluginInfo(descriptor("solo"), "h")["dependencies"])
    }

    @Test
    fun `missing descriptor gives an empty list`() {
        assertEquals(emptyList<String>(), GetSiteInfoAPI.pluginInfo(null, "h")["dependencies"])
    }
}
