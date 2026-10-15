package com.panomc.platform.ui

import com.panomc.platform.NamespaceClash
import com.panomc.platform.ui.ThemeCompatibility.Companion.evaluate
import com.panomc.platform.ui.ThemeCompatibility.Companion.homeOptions
import com.panomc.platform.ui.ThemeCompatibility.PluginContracts
import com.panomc.platform.ui.ThemeCompatibility.ThemeRef
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The fallback report and the home page options (doc 01 sections 7 and 9) from fixture files: a theme's
 * `core-meta.json` and a plugin's `contract/views.json` + `contract/controllers.json`.
 */
class ThemeCompatibilityTest {
    @TempDir
    lateinit var dir: File

    private val theme = ThemeRef("blaze-theme", "v1.4.0")

    private fun fixture(name: String, json: String) = JsonObject(File(dir, name).also { it.writeText(json) }.readText())

    private val marketViews = """
        { "namespace": "market", "pluginId": "pano-plugin-market", "version": "1.1.0", "sdk": 2,
          "views": {
            "market:ProductCard": { "kind": "component", "contract": 2, "page": null, "home": null },
            "market:PriceTag":    { "kind": "component", "contract": 1, "page": null, "home": null },
            "market:StorePage":   { "kind": "page", "contract": 1, "page": { "path": "/store" },
                                    "home": { "label": { "en-US": "Store", "tr": "Mağaza" } } },
            "market:ProductPage": { "kind": "page", "contract": 1, "page": { "path": "/store/[slug]" }, "home": null }
          } }
    """

    private val marketControllers = """
        { "market/cart": { "version": 2, "scope": "app", "state": ["lines"], "actions": ["add"] },
          "market/format": { "version": 1, "scope": "app", "state": [], "actions": [] } }
    """

    private fun market(views: String? = marketViews, controllers: String? = marketControllers) = PluginContracts(
        "pano-plugin-market", "market", "1.1.0",
        views?.let { fixture("views-${it.hashCode()}.json", it) },
        controllers?.let { fixture("controllers-${it.hashCode()}.json", it) }
    )

    private fun meta(
        overrides: String = "{}",
        engine: String = "{}",
        controllers: String = "{}",
        overrideControllers: String = "{}",
        extra: String = ""
    ) = fixture(
        "core-meta-${overrides.hashCode()}-${extra.hashCode()}.json",
        """{ "overrides": $overrides, "engine": $engine, "controllers": $controllers,
             "overrideControllers": $overrideControllers $extra }"""
    )

    @Suppress("UNCHECKED_CAST")
    private fun issues(report: Map<String, Any?>) = report["issues"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun counts(report: Map<String, Any?>) = report["counts"] as Map<String, Any?>

    // --- the report ---------------------------------------------------------------------------------

    @Test
    fun `matching overrides give status OK and no issue`() {
        val report = evaluate(
            theme,
            meta(
                overrides = """{ "Navbar": 1, "market:ProductCard": 2 }""",
                engine = """{ "Navbar": 1 }""",
                controllers = """{ "market/cart": 2 }""",
                overrideControllers = """{ "market:ProductCard": ["market/cart"] }"""
            ),
            listOf(market()),
            emptyList()
        )

        assertEquals("OK", report["status"])
        assertTrue(issues(report).isEmpty())
        assertEquals(mapOf("id" to "blaze-theme", "version" to "v1.4.0"), report["theme"])
        assertEquals(2, counts(report)["overrides"])
        assertEquals(2, counts(report)["active"])
        assertEquals(0, counts(report)["fallback"])
    }

    @Test
    fun `a contract that moved on is CONTRACT_MISMATCH`() {
        val report = evaluate(theme, meta(overrides = """{ "market:ProductCard": 1 }"""), listOf(market()), emptyList())

        assertEquals("OUTDATED", report["status"])
        assertEquals(
            listOf(
                mapOf(
                    "type" to "CONTRACT_MISMATCH", "view" to "market:ProductCard", "pluginId" to "pano-plugin-market",
                    "pluginVersion" to "1.1.0", "themeContract" to 1, "currentContract" to 2
                )
            ),
            issues(report)
        )
        assertEquals(1, counts(report)["fallback"])
        assertEquals(0, counts(report)["active"])
    }

    @Test
    fun `a controller pinned at another version is CONTROLLER_MISMATCH`() {
        val report = evaluate(
            theme,
            meta(
                overrides = """{ "market:ProductCard": 2 }""",
                controllers = """{ "market/cart": 1, "market/format": 1 }""",
                overrideControllers = """{ "market:ProductCard": ["market/format", "market/cart"] }"""
            ),
            listOf(market()),
            emptyList()
        )

        assertEquals("OUTDATED", report["status"])
        assertEquals(
            listOf(
                mapOf(
                    "type" to "CONTROLLER_MISMATCH", "view" to "market:ProductCard", "controller" to "market/cart",
                    "themeVersion" to 1, "currentVersion" to 2
                )
            ),
            issues(report)
        )
    }

    @Test
    fun `a controller the plugin does not register or the theme does not pin is not a mismatch`() {
        val report = evaluate(
            theme,
            meta(
                overrides = """{ "market:ProductCard": 2 }""",
                controllers = """{ "market/gone": 1 }""",
                overrideControllers = """{ "market:ProductCard": ["market/gone", "market/cart"] }"""
            ),
            listOf(market()),
            emptyList()
        )

        assertEquals("OK", report["status"])
    }

    @Test
    fun `a contract mismatch is reported once, not also as a controller mismatch`() {
        val report = evaluate(
            theme,
            meta(
                overrides = """{ "market:ProductCard": 1 }""",
                controllers = """{ "market/cart": 1 }""",
                overrideControllers = """{ "market:ProductCard": ["market/cart"] }"""
            ),
            listOf(market()),
            emptyList()
        )

        assertEquals(listOf("CONTRACT_MISMATCH"), issues(report).map { it["type"] })
    }

    @Test
    fun `a view the plugin dropped is VIEW_REMOVED`() {
        val report = evaluate(theme, meta(overrides = """{ "market:OldBadge": 1 }"""), listOf(market()), emptyList())

        assertEquals("OUTDATED", report["status"])
        assertEquals(
            listOf(
                mapOf(
                    "type" to "VIEW_REMOVED", "view" to "market:OldBadge", "pluginId" to "pano-plugin-market",
                    "pluginVersion" to "1.1.0"
                )
            ),
            issues(report)
        )
        assertEquals(1, counts(report)["fallback"])
    }

    @Test
    fun `an engine view written for another bundled contract is ENGINE_MISMATCH`() {
        val report = evaluate(
            theme, meta(overrides = """{ "Navbar": 1, "Footer": 1 }""", engine = """{ "Navbar": 2, "Footer": 1 }"""),
            emptyList(), emptyList()
        )

        assertEquals("OUTDATED", report["status"])
        assertEquals(
            listOf(mapOf("type" to "ENGINE_MISMATCH", "view" to "Navbar", "themeContract" to 1, "engineContract" to 2)),
            issues(report)
        )
    }

    @Test
    fun `a namespace clash is listed but does not make the theme outdated`() {
        val report = evaluate(theme, meta(), listOf(market()), listOf(NamespaceClash("market", "market", "pano-plugin-market")))

        assertEquals("OK", report["status"])
        assertEquals(
            listOf(mapOf("type" to "NAMESPACE_CLASH", "namespace" to "market", "pluginId" to "market", "heldBy" to "pano-plugin-market")),
            issues(report)
        )
    }

    @Test
    fun `no core-meta is UNKNOWN, with no counts and only clashes as issues`() {
        val report = evaluate(theme, null, listOf(market()), emptyList())

        assertEquals("UNKNOWN", report["status"])
        assertTrue(issues(report).isEmpty())
        assertEquals(0, counts(report)["overrides"])

        val withClash = evaluate(theme, null, emptyList(), listOf(NamespaceClash("a", "b", "c")))

        assertEquals("UNKNOWN", withClash["status"])
        assertEquals(1, issues(withClash).size)
    }

    @Test
    fun `no theme at all is UNKNOWN with a null theme`() {
        val report = evaluate(null, null, emptyList(), emptyList())

        assertEquals("UNKNOWN", report["status"])
        assertNull(report["theme"])
    }

    @Test
    fun `an override of a plugin that is not installed is counted, never an issue`() {
        val report = evaluate(
            theme, meta(overrides = """{ "blog:Teaser": 3, "market:ProductCard": 2, "Navbar": 1 }""", engine = """{ "Navbar": 1 }"""),
            listOf(market()), emptyList()
        )

        assertEquals("OK", report["status"])
        assertTrue(issues(report).isEmpty())
        assertEquals(mapOf("overrides" to 3, "active" to 2, "fallback" to 0, "pluginNotInstalled" to 1), counts(report))
    }

    @Test
    fun `counts add up to the overrides`() {
        val report = evaluate(
            theme,
            meta(
                overrides = """{ "market:ProductCard": 1, "market:OldBadge": 1, "market:PriceTag": 1, "blog:Teaser": 1, "Navbar": 1 }""",
                engine = """{ "Navbar": 1 }"""
            ),
            listOf(market()), emptyList()
        )
        val counts = counts(report)

        assertEquals(5, counts["overrides"])
        assertEquals(2, counts["active"])
        assertEquals(counts["overrides"], (counts["active"] as Int) + (counts["fallback"] as Int) + (counts["pluginNotInstalled"] as Int))
        assertEquals(2, counts["fallback"])
        assertEquals(1, counts["pluginNotInstalled"])
    }

    @Test
    fun `the full plugin id works as the namespace of an override`() {
        val report = evaluate(theme, meta(overrides = """{ "pano-plugin-market:ProductCard": 1 }"""), listOf(market()), emptyList())

        assertEquals(listOf("CONTRACT_MISMATCH"), issues(report).map { it["type"] })
        assertEquals("market:ProductCard", issues(report).single()["view"])
    }

    @Test
    fun `a plugin without contract files cannot be judged and raises nothing`() {
        val report = evaluate(
            theme, meta(overrides = """{ "market:ProductCard": 1 }"""), listOf(market(views = null, controllers = null)), emptyList()
        )

        assertEquals("OK", report["status"])
        assertEquals(1, counts(report)["active"])
    }

    @Test
    fun `an override without a contract in the plugin's views is taken as contract 1`() {
        val views = """{ "views": { "market:PriceTag": { "kind": "component" } } }"""

        assertEquals("OK", evaluate(theme, meta(overrides = """{ "market:PriceTag": 1 }"""), listOf(market(views)), emptyList())["status"])
    }

    // --- the home page options ----------------------------------------------------------------------

    private val configuredMeta = """
        , "home": { "default": "landing", "options": {
            "posts":   { "label": "Posts", "kind": "posts" },
            "landing": { "label": { "en-US": "Landing", "tr": "Açılış" }, "kind": "page" },
            "store":   { "label": "Store", "kind": "path", "path": "/store" },
            "vip":     { "label": "VIP", "kind": "path", "path": "/store/vip" },
            "shop":    { "label": "Gone", "kind": "path", "path": "/gone" },
            "custom":  { "label": "Custom page", "kind": "custom", "path": "*" } } }
    """

    @Test
    fun `a theme without a home config offers the feed, plugin pages that set home, and custom`() {
        val options = homeOptions(meta(), listOf(market()))

        assertEquals("posts", options.default)
        assertEquals(setOf("posts", "market:StorePage", "custom"), options.options.map { it.id }.toSet())

        val store = options.options.first { it.id == "market:StorePage" }

        assertEquals("path", store.kind)
        assertEquals("/store", store.path)
        assertTrue(store.available)
        assertEquals(mapOf("en-US" to "Store", "tr" to "Mağaza"), (store.label as JsonObject).map)
        assertTrue(options.customOffered)
    }

    @Test
    fun `no core-meta behaves like no home config`() {
        val options = homeOptions(null, emptyList())

        assertEquals(setOf("posts", "custom"), options.options.map { it.id }.toSet())
        assertTrue(options.accepts("custom:/rules"))
    }

    @Test
    fun `a configured theme offers its own options and availability follows the plugin pages`() {
        val options = homeOptions(meta(extra = configuredMeta), listOf(market()))

        assertEquals("landing", options.default)
        assertEquals(listOf("posts", "landing", "store", "vip", "shop", "custom"), options.options.map { it.id })
        assertEquals(
            mapOf("posts" to true, "landing" to true, "store" to true, "vip" to true, "shop" to false, "custom" to true),
            options.options.associate { it.id to it.available }
        )
    }

    @Test
    fun `the plugin behind a path being off makes the option unavailable`() {
        val options = homeOptions(meta(extra = configuredMeta), emptyList())

        assertFalse(options.options.first { it.id == "store" }.available)
        assertFalse(options.options.first { it.id == "vip" }.available)
        assertTrue(options.options.first { it.id == "landing" }.available)
    }

    @Test
    fun `the feed is offered even when the theme does not list it`() {
        val options = homeOptions(
            meta(extra = """, "home": { "default": "store", "options": { "store": { "label": "S", "kind": "path", "path": "/store" } } }"""),
            listOf(market())
        )

        assertEquals("posts", options.options.first().id)
        assertEquals("store", options.default)
        assertFalse(options.customOffered)
    }

    @Test
    fun `a route the theme adds counts as served`() {
        val options = homeOptions(
            meta(extra = """, "home": { "options": { "team": { "label": "T", "kind": "path", "path": "/staff-team" } } },
                "routes": { "add": ["/staff-team"], "rename": {}, "disable": [] }"""),
            emptyList()
        )

        assertTrue(options.options.first { it.id == "team" }.available)
        assertEquals("posts", options.default)
    }

    @Test
    fun `option ids and custom paths are accepted, anything else is not`() {
        val options = homeOptions(meta(extra = configuredMeta), listOf(market()))

        listOf("posts", "landing", "store", "vip", "shop", "custom:/rules", "custom:/store/vip").forEach {
            assertTrue(options.accepts(it), it)
        }

        listOf(
            "", "nope", "custom", "custom:", "custom:rules", "custom://evil.example", "custom:/store/[slug]",
            "custom:/with space", "custom:/a\\b", "custom:/" + "a".repeat(300), "market:StorePage"
        ).forEach {
            assertFalse(options.accepts(it), it)
        }
    }

    @Test
    fun `custom paths are refused when the theme offers no custom option`() {
        val options = homeOptions(
            meta(extra = """, "home": { "options": { "store": { "label": "S", "kind": "path", "path": "/store" } } }"""),
            listOf(market())
        )

        assertTrue(options.accepts("store"))
        assertFalse(options.accepts("custom:/rules"))
    }

    @Test
    fun `without a home config the plugin page id is accepted`() {
        val options = homeOptions(meta(), listOf(market()))

        assertTrue(options.accepts("market:StorePage"))
        assertFalse(options.accepts("market:ProductCard"))
        assertFalse(options.accepts("custom"))
    }

    @Test
    fun `toMap carries path only when there is one`() {
        val options = homeOptions(meta(extra = configuredMeta), listOf(market()))

        assertEquals(
            mapOf("id" to "posts", "label" to "Posts", "kind" to "posts", "available" to true),
            options.options.first { it.id == "posts" }.toMap()
        )
        assertEquals("/store", options.options.first { it.id == "store" }.toMap()["path"])
    }

    // --- the stored pick ----------------------------------------------------------------------------

    @Test
    fun `the stored home page is per theme and survives other themes' writes`() {
        val first = ThemeCompatibility.withHomeValue(null, "blaze-theme", "store")

        assertEquals("store", ThemeCompatibility.homeValueFor(first.encode(), "blaze-theme"))
        assertNull(ThemeCompatibility.homeValueFor(first.encode(), "vanilla-theme"))

        val second = ThemeCompatibility.withHomeValue(first.encode(), "vanilla-theme", "custom:/rules")

        assertEquals("store", ThemeCompatibility.homeValueFor(second.encode(), "blaze-theme"))
        assertEquals("custom:/rules", ThemeCompatibility.homeValueFor(second.encode(), "vanilla-theme"))

        val cleared = ThemeCompatibility.withHomeValue(second.encode(), "blaze-theme", null)

        assertNull(ThemeCompatibility.homeValueFor(cleared.encode(), "blaze-theme"))
        assertEquals("custom:/rules", ThemeCompatibility.homeValueFor(cleared.encode(), "vanilla-theme"))
    }

    @Test
    fun `clearing the last pick leaves an empty object`() {
        val cleared = ThemeCompatibility.withHomeValue("""{"blaze-theme":"store"}""", "blaze-theme", null)

        assertTrue(cleared.isEmpty)
    }

    @Test
    fun `an unreadable stored value reads as none and is replaced on write`() {
        assertNull(ThemeCompatibility.homeValueFor(null, "t"))
        assertNull(ThemeCompatibility.homeValueFor("", "t"))
        assertNull(ThemeCompatibility.homeValueFor("not json", "t"))
        assertNull(ThemeCompatibility.homeValueFor("""{"t": 5}""", "t"))
        assertEquals("""{"t":"posts"}""", ThemeCompatibility.withHomeValue("not json", "t", "posts").encode())
    }

    @Test
    fun `the stored pick is not part of theme_settings`() {
        // theme_settings is replaced as a whole object per theme; the pick has its own property, so a
        // save or reset of the settings cannot reach it.
        assertEquals("home_page", com.panomc.platform.route.api.panel.theme.PanelUpdateThemeHomeAPI.HOME_PAGE)
        assertFalse(
            com.panomc.platform.route.api.panel.theme.PanelUpdateThemeHomeAPI.HOME_PAGE ==
                com.panomc.platform.route.api.panel.theme.PanelUpdateThemeSettingsAPI.THEME_SETTINGS
        )
    }

    @Test
    fun `route patterns match concrete paths`() {
        assertTrue(ThemeCompatibility.patternMatches("/store", "/store"))
        assertTrue(ThemeCompatibility.patternMatches("/store/[slug]", "/store/vip"))
        assertTrue(ThemeCompatibility.patternMatches("/store/:slug", "/store/vip"))
        assertFalse(ThemeCompatibility.patternMatches("/store/[slug]", "/store"))
        assertFalse(ThemeCompatibility.patternMatches("/store", "/shop"))
    }

    @Test
    fun `a refused configured theme is named with its verdict instead of UNKNOWN`() {
        val report = ThemeCompatibility.refusedReport(
            ThemeRef("blaze-theme", "v1.1.0"), com.panomc.platform.gate.Verdict.TOO_OLD, 0, "vanilla-theme"
        )

        assertEquals(mapOf("id" to "blaze-theme", "version" to "v1.1.0"), report["theme"])
        assertEquals("OUTDATED", report["status"])
        assertEquals("TOO_OLD", report["verdict"])
        assertEquals("vanilla-theme", report["served"])

        val issue = (report["issues"] as List<*>).single() as Map<*, *>

        assertEquals("THEME_API_LEVEL_UNSUPPORTED", issue["type"])
        assertEquals("TOO_OLD", issue["verdict"])
        assertEquals(0, issue["apiLevel"])
    }
}
