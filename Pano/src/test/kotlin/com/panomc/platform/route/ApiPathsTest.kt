package com.panomc.platform.route

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiPathsTest {
    private val market = "pano-plugin-market"

    // resolve x4 (+ ROOT)

    @Test
    fun `core site path goes under api v1`() {
        assertEquals("/api/v1/posts", ApiPaths.resolve("/posts", Mount.API, Namespace.SITE, null))
    }

    @Test
    fun `core panel path goes under api v1 panel`() {
        assertEquals("/api/v1/panel/settings", ApiPaths.resolve("/settings", Mount.API, Namespace.PANEL, null))
    }

    @Test
    fun `plugin site path goes under the plugin id`() {
        assertEquals(
            "/api/plugins/$market/store/products",
            ApiPaths.resolve("/store/products", Mount.API, Namespace.SITE, market)
        )
    }

    @Test
    fun `plugin panel path goes under the plugin id and panel`() {
        assertEquals(
            "/api/plugins/$market/panel/products",
            ApiPaths.resolve("/products", Mount.API, Namespace.PANEL, market)
        )
    }

    @Test
    fun `root mount is verbatim whatever the namespace`() {
        assertEquals("/panel/host-sso", ApiPaths.resolve("/panel/host-sso", Mount.ROOT, Namespace.SITE, null))
        assertEquals("/", ApiPaths.resolve("/", Mount.ROOT, Namespace.PANEL, market))
    }

    @Test
    fun `the four builders agree with the constants`() {
        assertEquals("/api/v1", ApiPaths.ROOT)
        assertEquals("/api/v1/x", ApiPaths.core("/x"))
        assertEquals("/api/v1/panel/x", ApiPaths.panel("/x"))
        assertEquals("/api/plugins/p/x", ApiPaths.plugin("p", "/x"))
        assertEquals("/api/plugins/p/panel/x", ApiPaths.pluginPanel("p", "/x"))
    }

    @Test
    fun `plugin namespace is unversioned and panel plugin paths are recognised`() {
        assertTrue(ApiPaths.isMounted("/api/plugins/p/x"))
        assertTrue(ApiPaths.isMounted("/api/v1/x"))
        assertFalse(ApiPaths.isMounted("/api/v2/x"))
        assertTrue(ApiPaths.isPluginPanel("/api/plugins/p/panel/x"))
        assertTrue(ApiPaths.isPluginPanel("/api/plugins/p/panel"))
        assertFalse(ApiPaths.isPluginPanel("/api/plugins/p/panelx"))
        assertFalse(ApiPaths.isPluginPanel("/api/plugins/p/x/panel/y"))
        assertFalse(ApiPaths.isPluginPanel("/api/v1/panel/plugins/p/x"))
        assertTrue(ApiPaths.isPanelApi("/api/v1/panel/settings"))
        assertTrue(ApiPaths.isPanelApi("/api/plugins/p/panel/x"))
        assertFalse(ApiPaths.isPanelApi("/api/plugins/p/x"))
        assertFalse(ApiPaths.isPanelApi("/api/v1/plugins/p/x"))
    }

    // refusal table

    @Test
    fun `fine paths are not refused`() {
        assertNull(ApiPaths.refusal("/posts/:url", "Core", null))
        assertNull(ApiPaths.refusal("/", "Core", null))
        assertNull(ApiPaths.refusal("/apiary", "Core", null))
        assertNull(ApiPaths.refusal("/panelists", "Core", null))
        assertNull(ApiPaths.refusal("/store/products", "Market", market))
        assertNull(ApiPaths.refusal("/", "Market", market))
        assertNull(ApiPaths.refusal("/plugins/:pluginId/_/ui.zip", "com.panomc.platform.route.api.plugins.X", null, true))
    }

    @Test
    fun `empty path is refused`() {
        val message = ApiPaths.refusal("", "GetHelloAPI", market)!!

        assertTrue(message.contains("GetHelloAPI"))
        assertTrue(message.endsWith("declare \"/hello\""), message)
    }

    @Test
    fun `path without a leading slash is refused`() {
        val message = ApiPaths.refusal("hello", "GetHelloAPI", market)!!

        assertTrue(message.contains("GetHelloAPI"))
        assertTrue(message.endsWith("declare \"/hello\""), message)
    }

    @Test
    fun `path starting with api is refused`() {
        val message = ApiPaths.refusal("/api/posts", "GetPostsAPI", null)!!

        assertTrue(message.contains("GetPostsAPI"))
        assertTrue(message.endsWith("declare \"/posts\", not \"/api/posts\"; Pano adds /api/v1 itself"), message)
        assertTrue(ApiPaths.refusal("/api/x", "X", market)!!.endsWith("Pano adds /api/plugins/$market itself"))
        assertTrue(ApiPaths.refusal("/api", "X", null)!!.endsWith("Pano adds /api/v1 itself"))
    }

    @Test
    fun `path starting with panel is refused`() {
        val message = ApiPaths.refusal("/panel/settings", "PanelGetSettingsAPI", null)!!

        assertTrue(message.contains("PanelGetSettingsAPI"))
        assertTrue(message.endsWith("extend PanelApi and declare \"/settings\"; Pano adds /panel itself"), message)
        assertTrue(ApiPaths.refusal("/panel/x", "X", market)!!.endsWith("Pano adds /panel itself"))
    }

    @Test
    fun `plugin path starting with the reserved segment or a parameter is refused`() {
        val reserved = ApiPaths.refusal("/_/ui.zip", "UiAPI", market)!!
        val parameter = ApiPaths.refusal("/:id", "ItemAPI", market)!!

        assertTrue(reserved.contains("UiAPI"))
        assertTrue(reserved.endsWith("put a resource name first: \"/items/:id\""), reserved)
        assertTrue(parameter.contains("ItemAPI"))
        assertTrue(parameter.endsWith("put a resource name first: \"/items/:id\""), parameter)
    }

    @Test
    fun `the reserved segment and parameter rule is for plugins only`() {
        assertNull(ApiPaths.refusal("/:id", "Core", null))
        assertNull(ApiPaths.refusal("/_/x", "Core", null))
    }

    @Test
    fun `core path under plugins is refused outside the plugins package`() {
        val message = ApiPaths.refusal("/plugins/list", "com.panomc.platform.route.api.PluginsAPI", null)!!

        assertTrue(message.contains("com.panomc.platform.route.api.PluginsAPI"))
        assertTrue(message.endsWith("reserved for plugins"), message)
        assertNull(ApiPaths.refusal("/plugins/list", "com.panomc.platform.route.api.plugins.X", null, true))
        assertNull(ApiPaths.refusal("/pluginsx", "Core", null))
    }

    // route table

    private class FirstAPI
    private class SecondAPI

    private fun entry(
        method: String = "GET",
        path: String = "/api/v1/posts/:id",
        pluginId: String? = null,
        routeClass: Class<*> = FirstAPI::class.java
    ) = RouteEntry(method, path, path, pluginId, routeClass, Mount.API, Namespace.SITE)

    @Test
    fun `duplicate method and path names both classes`() {
        val table = RouteTable()

        table.register(listOf(entry()))

        val refusal = assertThrows(ApiPathRefusal::class.java) {
            table.register(listOf(entry(path = "/api/v1/posts/:name", routeClass = SecondAPI::class.java)))
        }

        assertTrue(refusal.message!!.contains(FirstAPI::class.java.name), refusal.message)
        assertTrue(refusal.message!!.contains(SecondAPI::class.java.name), refusal.message)
        assertEquals(1, table.entries().size)
    }

    @Test
    fun `duplicate inside one batch adds nothing`() {
        val table = RouteTable()

        assertThrows(ApiPathRefusal::class.java) {
            table.register(
                listOf(
                    entry(path = "/api/v1/a"),
                    entry(path = "/api/v1/b"),
                    entry(path = "/api/v1/a", routeClass = SecondAPI::class.java)
                )
            )
        }

        assertTrue(table.entries().isEmpty())
    }

    @Test
    fun `same path with another method is not a duplicate`() {
        val table = RouteTable()

        table.register(listOf(entry(method = "GET"), entry(method = "POST")))

        assertEquals(2, table.entries().size)
    }

    @Test
    fun `plugin entries are removed on unload and can be registered again`() {
        val table = RouteTable()
        val pluginPath = ApiPaths.plugin(market, "/store/products")
        val pluginEntry = entry(path = pluginPath, pluginId = market)

        table.register(listOf(entry(path = "/api/v1/posts"), pluginEntry))
        assertEquals(listOf(pluginEntry), table.entriesOf(market))
        assertEquals(pluginEntry, table.find("GET", pluginPath))

        table.removePlugin(market)

        assertTrue(table.entriesOf(market).isEmpty())
        assertEquals(1, table.entries().size)

        table.register(listOf(pluginEntry))

        assertEquals(2, table.entries().size)
    }
}
