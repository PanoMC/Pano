package com.panomc.platform.route

import com.panomc.platform.AppConstants
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.error.PostNotFound
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.PanelApi
import com.panomc.platform.route.api.plugins.GetPluginTranslationsAPI
import com.panomc.platform.route.api.posts.GetPostPreviewAPI
import com.panomc.platform.route.api.server.GetServersAPI
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.util.PostStatus
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.pf4j.PluginState

/** PF-09: the three public gap endpoints of doc 04 §8. */
class GapEndpointsTest {
    // ---- GET /api/servers -------------------------------------------------------------------

    private fun server(
        id: Long,
        name: String,
        accepted: Boolean = true,
        online: Boolean = true,
        customName: String? = null,
        favicon: String = ""
    ) = Server(
        id = id,
        name = name,
        motd = "motd $id",
        host = "10.0.0.$id",
        remoteAddress = "203.0.113.$id",
        port = 25565,
        playerCount = 3,
        maxPlayerCount = 20,
        type = ServerType.PAPER,
        version = "1.21",
        favicon = favicon,
        permissionGranted = accepted,
        status = if (online) ServerStatus.ONLINE else ServerStatus.OFFLINE,
        startTime = 0,
        aesKey = "secret-key-$id",
        customName = customName
    )

    @Test
    fun `servers lists accepted servers only and the main one first`() {
        val body = GetServersAPI.payload(
            listOf(server(1, "Zeta"), server(2, "Alpha"), server(3, "Pending", accepted = false), server(4, "Main")),
            4L,
            "play.example.com"
        )

        @Suppress("UNCHECKED_CAST")
        val items = body["items"] as List<Map<String, Any?>>

        assertEquals(listOf(4L, 2L, 1L), items.map { it["id"] })
        assertEquals(listOf(true, false, false), items.map { it["main"] })
        assertEquals("play.example.com", body["address"])
    }

    @Test
    fun `servers never expose host, port, remote address or key`() {
        val encoded = JsonObject(GetServersAPI.payload(listOf(server(7, "One")), null, "a.b")).encode()

        listOf("host", "port", "remoteAddress", "aesKey", "10.0.0.7", "203.0.113.7", "secret-key-7").forEach {
            assertFalse(encoded.contains(it), "leaked $it")
        }
    }

    @Test
    fun `server entry has the documented fields`() {
        val entry = GetServersAPI.toPublic(server(1, "Lobby", online = false, customName = "Hub"), false)

        assertEquals(
            setOf("id", "name", "motd", "online", "playerCount", "maxPlayerCount", "version", "type", "main", "iconUrl"),
            entry.keys
        )
        assertEquals("Hub", entry["name"])
        assertEquals(false, entry["online"])
        assertEquals("PAPER", entry["type"])
        assertNull(entry["iconUrl"])
    }

    @Test
    fun `server icon that is not a raster data url is dropped`() {
        assertNull(GetServersAPI.toPublic(server(1, "A", favicon = "data:image/svg+xml;base64,PHN2Zz4="), false)["iconUrl"])
    }

    @Test
    fun `servers answers an empty list when nothing is accepted`() {
        val body = GetServersAPI.payload(listOf(server(1, "P", accepted = false)), null, "x")

        assertEquals(emptyList<Any>(), body["items"])
    }

    // ---- GET /api/plugins/:pluginId/_/translations/:locale ----------------------------------

    private val trLocale = JsonObject("""{"shop":{"title":"Magaza","cart":"Sepet"},"hi":"Selam"}""")
    private val enLocale = JsonObject("""{"shop":{"title":"Shop","cart":"Cart"},"hi":"Hello"}""")
    private val locales = mapOf("tr" to trLocale, AppConstants.DEFAULT_LOCALE_CODE to enLocale)

    private fun translations(body: Map<String, Any?>) = body["translations"] as JsonObject

    @Test
    fun `plugin translations are the plugin subtree with the locale code`() {
        val body = GetPluginTranslationsAPI.response("pano-plugin-market", "tr", locales, emptyMap())

        assertEquals("tr", body["locale"])
        assertEquals(trLocale, translations(body))
    }

    @Test
    fun `admin edits replace plugin text, edits for unknown keys are ignored`() {
        val body = GetPluginTranslationsAPI.response(
            "pano-plugin-market",
            "tr",
            locales,
            mapOf(
                "plugins.pano-plugin-market.shop.title" to "Dukkan",
                "plugins.pano-plugin-market.nothing" to "x",
                "plugins.other-plugin.hi" to "wrong plugin"
            )
        )

        assertEquals("Dukkan", translations(body).getJsonObject("shop").getString("title"))
        assertEquals("Sepet", translations(body).getJsonObject("shop").getString("cart"))
        assertEquals("Selam", translations(body).getString("hi"))
        assertFalse(translations(body).containsKey("nothing"))
    }

    @Test
    fun `a locale the plugin lacks falls back to the default locale`() {
        val body = GetPluginTranslationsAPI.response("p", "de", locales, emptyMap())

        assertEquals("de", body["locale"])
        assertEquals("Hello", translations(body).getString("hi"))
    }

    @Test
    fun `a plugin without texts answers an empty object`() {
        assertTrue(translations(GetPluginTranslationsAPI.response("p", "tr", emptyMap(), emptyMap())).isEmpty)
    }

    @Test
    fun `plugin translations answer 404 for an unknown locale, a stopped or an unknown plugin`() {
        GetPluginTranslationsAPI.requireServable(PluginState.STARTED, true)

        assertThrows(NotFound::class.java) { GetPluginTranslationsAPI.requireServable(PluginState.STARTED, false) }
        assertThrows(NotFound::class.java) { GetPluginTranslationsAPI.requireServable(PluginState.STOPPED, true) }
        assertThrows(NotFound::class.java) { GetPluginTranslationsAPI.requireServable(PluginState.DISABLED, true) }
        assertThrows(NotFound::class.java) { GetPluginTranslationsAPI.requireServable(null, true) }
        assertEquals(404, NotFound().getStatusCode())
    }

    // ---- GET /api/posts/previews/:id --------------------------------------------------------

    private fun post(id: Long) = Post(
        id = id,
        title = "Title $id",
        categoryId = 5,
        writerUserId = 1,
        text = "body",
        date = 1000 + id,
        status = PostStatus.DRAFT,
        thumbnailUrl = "thumb",
        views = 9,
        url = "post-$id"
    )

    @Test
    fun `preview body has the shape of the post detail`() {
        val body = GetPostPreviewAPI.body(
            post(2),
            PostCategory(id = 5, title = "News", url = "news"),
            "writer",
            post(1),
            null
        )

        assertEquals(setOf("post", "previousPost", "nextPost"), body.keys)

        @Suppress("UNCHECKED_CAST")
        val post = body["post"] as Map<String, Any?>

        assertEquals("post-2", post["url"])
        assertEquals(mapOf("username" to "writer"), post["writer"])
        assertEquals(mapOf("title" to "News", "url" to "news"), post["category"])
        assertEquals(mapOf("id" to 1L, "title" to "Title 1", "url" to "post-1"), body["previousPost"])
        assertEquals("-", body["nextPost"])
    }

    @Test
    fun `preview of a post without category or writer uses the placeholders`() {
        val body = GetPostPreviewAPI.body(post(2), null, null, null, null)

        @Suppress("UNCHECKED_CAST")
        val post = body["post"] as Map<String, Any?>

        assertEquals(mapOf("id" to -1, "title" to "-"), post["category"])
        assertEquals(mapOf("username" to "-"), post["writer"])
    }

    @Test
    fun `preview answers an error for a missing post and for a caller without panel access`() {
        assertEquals(2L, GetPostPreviewAPI.requirePost(post(2)).id)

        assertThrows(PostNotFound::class.java) { GetPostPreviewAPI.requirePost(null) }
        assertThrows(NoPermission::class.java) { GetPostPreviewAPI.requirePanelAccess(false) }
        GetPostPreviewAPI.requirePanelAccess(true)
    }

    @Test
    fun `preview is a logged in endpoint and not a panel endpoint`() {
        assertEquals(LoggedInApi::class.java, GetPostPreviewAPI::class.java.superclass)
        assertFalse(PanelApi::class.java.isAssignableFrom(GetPostPreviewAPI::class.java))
    }
}
