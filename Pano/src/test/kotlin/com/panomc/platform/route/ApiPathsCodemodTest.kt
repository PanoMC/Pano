package com.panomc.platform.route

import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.server.plugins.PanoPluginUpdateService
import com.panomc.platform.util.RateLimitManager
import com.panomc.platform.util.RateLimitManager.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the path codemod (PF-04) leaves behind: namespace helpers and the tables that read mounted paths. */
class ApiPathsCodemodTest {
    @Test
    fun `the api namespace is wider than the mounted prefix`() {
        assertTrue(ApiPaths.isApi("/api"))
        assertTrue(ApiPaths.isApi("/api/v1/posts"))
        assertTrue(ApiPaths.isApi("/api/posts"))
        assertFalse(ApiPaths.isApi("/apiary"))
        assertFalse(ApiPaths.isApi("/panel/api/basicData"))

        assertTrue(ApiPaths.isMounted("/api/v1"))
        assertTrue(ApiPaths.isMounted("/api/v1/posts"))
        assertFalse(ApiPaths.isMounted("/api/posts"))
        assertFalse(ApiPaths.isMounted("/api/v10/posts"))
    }

    @Test
    fun `the panel root is the prefix of the panel builder`() {
        assertEquals("/api/v1/panel", ApiPaths.PANEL_ROOT)
        assertEquals("${ApiPaths.PANEL_ROOT}/x", ApiPaths.panel("/x"))
        assertEquals("/api", ApiPaths.BASE)
    }

    @Test
    fun `the rate limit tiers are read from mounted paths`() {
        fun tier(path: String) = RateLimitManager.tierForPath(path)

        assertEquals(Tier.AUTH, tier("/api/v1/auth/login"))
        assertEquals(Tier.MAINTENANCE_AUTH, tier("/api/v1/maintenance/login"))
        assertEquals(Tier.MAINTENANCE_API, tier("/api/v1/maintenance/skip"))
        assertEquals(Tier.SERVER_API, tier("/api/v1/server/connect"))
        assertEquals(Tier.SERVER_API, tier("/api/v1/server/disconnect"))
        assertEquals(Tier.SERVER_API, tier("/api/v1/server/connection"))
        assertEquals(Tier.SETUP_API, tier("/api/v1/setup/step"))
        assertEquals(Tier.FILE_SERVE, tier("/api/v1/favicon"))
        assertEquals(Tier.FILE_SERVE, tier("/api/v1/website-logo"))
        assertEquals(Tier.FILE_SERVE, tier("/api/v1/server/icon/default"))
        assertEquals(Tier.FILE_SERVE, tier("/api/v1/posts/thumbnails/a.png"))
        assertEquals(Tier.FILE_SERVE, tier("/api/v1/profile/picture/steve"))
        assertEquals(Tier.PANEL_API, tier("/api/v1/panel/settings"))
        assertEquals(Tier.PANEL_API, tier("/api/plugins/pano-plugin-market/panel/products"))
        assertEquals(Tier.PUBLIC_API, tier("/api/v1/posts"))
        assertEquals(Tier.PUBLIC_API, tier("/api/plugins/pano-plugin-market/store/products"))
    }

    @Test
    fun `urls handed to other programs carry the api prefix`() {
        assertEquals("/api/v1/server/pano-plugin/jar", ApiPaths.core(PanoPluginUpdateService.SERVER_JAR_PATH))
        assertEquals("/api/v1/node/plugin-jars/spigot", ManagedPluginJarResolver.pluginJarPath("spigot"))
        assertEquals(
            "curl -fsSL http://p/api/v1/node/install.sh | sh -s -- --pano 'http://p' --code '1'",
            NodeInstallScriptProvider.shellInstallCommand("http://p", "1")
        )
        assertTrue(NodeInstallScriptProvider.powerShellInstallCommand("http://p", "1").contains("'http://p/api/v1/node/install.ps1'"))
    }
}
