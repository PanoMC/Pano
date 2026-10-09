package com.panomc.platform.route

import com.panomc.platform.route.api.GetTranslationsAPI
import com.panomc.platform.route.api.panel.locale.PanelGetLocaleTranslationsAPI
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a plugin text comes from (doc 03 section 5.2): the admin edits `GET /locales/:code/translations/...`
 * reports as `pluginAdminKeys`, and the theme's `<code>.plugins.json` the panel's PLUGIN list layers over the
 * plugins' own texts.
 */
class TranslationSourcesTest {
    private val plugins = listOf("pano-plugin-market" to "market", "pano-plugin-blog" to "blog", "my.dotted.plugin" to "my.dotted.plugin")

    private fun originals() = mutableMapOf<String, Any>(
        "plugins.pano-plugin-market.theme.store.title" to "Mağaza",
        "plugins.pano-plugin-market.theme.store.cart" to "Sepet",
        "plugins.pano-plugin-blog.title" to "Blog"
    )

    // --- admin edits ----------------------------------------------------------------------------------

    @Test
    fun `an edited key is listed and an untouched key is not`() {
        val flat = mapOf<String, Any>("theme.store.title" to "Market", "theme.store.cart" to "Cart")
        val edits = mapOf("plugins.pano-plugin-market.theme.store.title" to "Admin title")

        val (merged, edited) = GetTranslationsAPI.applyAdminEdits("pano-plugin-market", flat, edits)

        assertEquals(listOf("theme.store.title"), edited)
        assertEquals("Admin title", merged["theme.store.title"])
        assertEquals("Cart", merged["theme.store.cart"])
    }

    @Test
    fun `an edit of another plugin or a key the plugin does not have is not listed`() {
        val flat = mapOf<String, Any>("a" to "1", "b" to "2")
        val edits = mapOf(
            "plugins.pano-plugin-other.a" to "x",
            "plugins.pano-plugin-market.gone" to "y"
        )

        val (merged, edited) = GetTranslationsAPI.applyAdminEdits("pano-plugin-market", flat, edits)

        assertTrue(edited.isEmpty())
        assertEquals(flat, merged)
    }

    @Test
    fun `the listed keys are sorted and a dotted plugin id keeps its own edits`() {
        val flat = mapOf<String, Any>("z" to "1", "a" to "2", "m" to "3")
        val edits = mapOf("plugins.my.dotted.plugin.z" to "Z", "plugins.my.dotted.plugin.a" to "A")

        val (_, edited) = GetTranslationsAPI.applyAdminEdits("my.dotted.plugin", flat, edits)

        assertEquals(listOf("a", "z"), edited)
        assertTrue(GetTranslationsAPI.applyAdminEdits("my", flat, edits).second.isEmpty())
    }

    // --- theme plugin texts ---------------------------------------------------------------------------

    @Test
    fun `a namespace folder replaces a plugin text and adds a new key`() {
        val original = originals()
        val file = JsonObject("""{ "market": { "theme": { "store": { "title": "Dükkan", "extra": "Yeni" } } } }""")

        val supplied = PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, file, plugins)

        assertEquals("Dükkan", original["plugins.pano-plugin-market.theme.store.title"])
        assertEquals("Yeni", original["plugins.pano-plugin-market.theme.store.extra"])
        assertEquals("Sepet", original["plugins.pano-plugin-market.theme.store.cart"])
        assertEquals(
            setOf("plugins.pano-plugin-market.theme.store.title", "plugins.pano-plugin-market.theme.store.extra"),
            supplied
        )
    }

    @Test
    fun `a folder named with the full plugin id works too`() {
        val original = originals()
        val file = JsonObject("""{ "pano-plugin-blog": { "title": "Haberler" } }""")

        val supplied = PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, file, plugins)

        assertEquals("Haberler", original["plugins.pano-plugin-blog.title"])
        assertEquals(setOf("plugins.pano-plugin-blog.title"), supplied)
    }

    @Test
    fun `when both folders name one plugin the full id wins`() {
        val original = originals()
        val file = JsonObject("""{ "pano-plugin-blog": { "title": "By id" }, "blog": { "title": "By namespace" } }""")

        PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, file, plugins)

        assertEquals("By id", original["plugins.pano-plugin-blog.title"])
    }

    @Test
    fun `a dotted plugin id is found as a folder`() {
        val original = mutableMapOf<String, Any>("plugins.my.dotted.plugin.hello" to "Hi")
        val file = JsonObject("""{ "my.dotted.plugin": { "hello": "Selam" } }""")

        PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, file, plugins)

        assertEquals("Selam", original["plugins.my.dotted.plugin.hello"])
    }

    @Test
    fun `a folder no plugin owns and a non-object folder are skipped`() {
        val original = originals()
        val before = original.toMap()
        val file = JsonObject("""{ "shop": { "title": "x" }, "market": "not an object" }""")

        val supplied = PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, file, plugins)

        assertEquals(before, original)
        assertTrue(supplied.isEmpty())
    }

    @Test
    fun `no theme file changes nothing`() {
        // no theme UI (front-end mode none), an old theme without the file, or a non-200 all arrive as null
        val original = originals()
        val before = original.toMap()

        assertTrue(PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, null, plugins).isEmpty())
        assertEquals(before, original)
        assertTrue(PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, JsonObject(), plugins).isEmpty())
        assertEquals(before, original)
    }

    @Test
    fun `an empty plugin list ignores the whole file`() {
        val original = originals()
        val before = original.toMap()

        PanelGetLocaleTranslationsAPI.applyThemePluginTexts(original, JsonObject("""{ "market": { "a": "b" } }"""), emptyList())

        assertEquals(before, original)
    }
}
