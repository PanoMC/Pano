package com.panomc.platform.ui

import com.panomc.platform.ApiLevel
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.NamespaceClash
import com.panomc.platform.UIManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.gate.Verdict
import com.panomc.platform.plugin.PluginNamespace
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * What the panel shows about the active theme and its plugins (doc 01 sections 7 and 9).
 *
 * The theme side is `core-meta.json` in the installed theme folder (written by `theme-core sync` and
 * the build); the plugin side is `contract/views.json` and `contract/controllers.json` of each active
 * plugin's built package. Everything here is computed on request from those files, nothing is stored,
 * and nothing blocks a plugin update: the running theme applies the same rules by itself.
 *
 * The rules and the home page option list are pure functions in the companion so tests can feed
 * fixture files; the class only reads the files.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ThemeCompatibility(
    private val uiManager: UIManager,
    private val pluginManager: PluginManager,
    private val pluginUiManager: PluginUiManager
) {
    private val logger = LoggerFactory.getLogger(ThemeCompatibility::class.java)

    /** The `GET /panel/theme/compatibility` payload. Blocking (reads files); call it off the event loop. */
    fun report(): Map<String, Any?> {
        // A configured theme the API level gate refused is not the active one (the bundled theme is served), but the
        // panel's warning is about it: name it with its verdict instead of reporting the fallback theme.
        val configured = uiManager.configuredTheme()
        val refused = uiManager.installedThemeList.find { it.id == configured }
        val verdict = uiManager.themeVerdict(configured)

        if (refused != null && verdict != Verdict.OK) {
            return refusedReport(ThemeRef(refused.id, refused.version), verdict, refused.apiLevel, uiManager.activeTheme)
        }

        val themeId = uiManager.activeTheme
        val theme = ThemeRef(themeId, uiManager.installedThemeList.find { it.id == themeId }?.version)

        return evaluate(theme, readCoreMeta(themeId), readPlugins(), pluginUiManager.getNamespaceClashes(pluginManager))
    }

    /** The home page options of the active theme. Blocking; call it off the event loop. */
    fun homeOptions(): HomeOptions = homeOptions(readCoreMeta(uiManager.activeTheme), readPlugins())

    /** `core-meta.json` of the theme, or null when there is none or it is not a JSON object (a theme run with `vite dev`). */
    internal fun readCoreMeta(themeId: String): JsonObject? {
        if (themeId.isBlank()) return null

        return try {
            val file = uiManager.getThemeFile(themeId, CORE_META) ?: return null

            JsonObject(file.readText())
        } catch (e: Exception) {
            logger.warn("Could not read {} of theme '{}': {}", CORE_META, themeId, e.message)

            null
        }
    }

    /** The contract files of every active plugin whose UI the site serves (a namespace clash loser is left out). */
    internal fun readPlugins(): List<PluginContracts> =
        pluginUiManager.getActiveRegisteredPlugins(pluginManager).map { it.first }.sortedBy { it.pluginId }.map { plugin ->
            PluginContracts(
                pluginId = plugin.pluginId,
                namespace = namespaceOf(plugin),
                version = pluginManager.getPlugin(plugin.pluginId)?.descriptor?.version,
                views = readObject(plugin, "contract/views.json"),
                controllers = readObject(plugin, "contract/controllers.json")
            )
        }

    private fun namespaceOf(plugin: PanoPlugin) = PluginNamespace.of(plugin)

    private fun readObject(plugin: PanoPlugin, entry: String): JsonObject? = try {
        pluginUiManager.readPackageEntry(plugin, entry)?.let { JsonObject(String(it.bytes, Charsets.UTF_8)) }
    } catch (e: Exception) {
        logger.warn("Could not read {} of plugin '{}': {}", entry, plugin.pluginId, e.message)

        null
    }

    /** The active theme as the panel names it. */
    data class ThemeRef(val id: String, val version: String?)

    /**
     * The contract files of one plugin. [views] and [controllers] are null when the package has none
     * (a plugin that is not migrated to named views): nothing can be said about it, so it raises no issue.
     */
    data class PluginContracts(
        val pluginId: String,
        val namespace: String,
        val version: String?,
        val views: JsonObject?,
        val controllers: JsonObject?
    )

    /** One entry of the home page select. [label] is a string or a locale map, as the theme wrote it. */
    data class HomeOption(
        val id: String,
        val label: Any?,
        val kind: String,
        val path: String?,
        val available: Boolean
    ) {
        fun toMap(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>("id" to id, "label" to label, "kind" to kind)

            if (path != null) map["path"] = path

            map["available"] = available

            return map
        }
    }

    /** The list the admin picks from, [default] is the option `null` stands for. */
    data class HomeOptions(val default: String, val options: List<HomeOption>, val customOffered: Boolean) {
        /** True when [value] is something `PUT /panel/theme/home` may store (null clears and is always fine). */
        fun accepts(value: String): Boolean {
            if (value.startsWith(CUSTOM_PREFIX)) {
                return customOffered && isConcretePath(value.substring(CUSTOM_PREFIX.length))
            }

            val option = options.firstOrNull { it.id == value } ?: return false

            return option.kind != KIND_CUSTOM && option.path != "*"
        }
    }

    companion object {
        const val CORE_META = "core-meta.json"
        const val CUSTOM_PREFIX = "custom:"

        const val THEME_API_LEVEL_UNSUPPORTED = "THEME_API_LEVEL_UNSUPPORTED"

        /**
         * The payload for a configured theme the API level gate refused ([verdict] is not OK): status `OUTDATED`, one
         * issue naming the verdict and the theme's level, and [served], the theme that is served in its place.
         */
        fun refusedReport(theme: ThemeRef, verdict: Verdict, apiLevel: Int, served: String): Map<String, Any?> = linkedMapOf(
            "theme" to mapOf("id" to theme.id, "version" to theme.version),
            "status" to STATUS_OUTDATED,
            "verdict" to verdict.name,
            "apiLevel" to apiLevel,
            "served" to served,
            "counts" to linkedMapOf("overrides" to 0, "active" to 0, "fallback" to 0, "pluginNotInstalled" to 0),
            "issues" to listOf(
                linkedMapOf(
                    "type" to THEME_API_LEVEL_UNSUPPORTED,
                    "verdict" to verdict.name,
                    "apiLevel" to apiLevel,
                    "supportedMin" to ApiLevel.MIN_SUPPORTED,
                    "supportedMax" to ApiLevel.CURRENT,
                    "served" to served
                )
            )
        )

        const val STATUS_OK = "OK"
        const val STATUS_OUTDATED = "OUTDATED"
        const val STATUS_UNKNOWN = "UNKNOWN"

        const val CONTRACT_MISMATCH = "CONTRACT_MISMATCH"
        const val CONTROLLER_MISMATCH = "CONTROLLER_MISMATCH"
        const val VIEW_REMOVED = "VIEW_REMOVED"
        const val ENGINE_MISMATCH = "ENGINE_MISMATCH"
        const val NAMESPACE_CLASH = "NAMESPACE_CLASH"

        const val KIND_POSTS = "posts"
        const val KIND_PAGE = "page"
        const val KIND_PATH = "path"
        const val KIND_CUSTOM = "custom"

        private const val POSTS_PATH = "/posts"
        private const val MAX_CUSTOM_PATH = 256

        /**
         * The payload of doc 01 section 7.
         *
         * [coreMeta] null = `UNKNOWN`: no verdict, no issue except the namespace clashes (those are about
         * plugins, not the theme, and never make a theme `OUTDATED`). Counts: `overrides` = entries of the
         * theme's `overrides`; `fallback` = overrides that show the default view (or none) because of an
         * issue; `pluginNotInstalled` = overrides of a plugin that is not installed (never an issue);
         * `active` = the rest, so the three always add up to `overrides`. A plugin without contract files
         * cannot be judged and counts as active.
         */
        fun evaluate(
            theme: ThemeRef?,
            coreMeta: JsonObject?,
            plugins: List<PluginContracts>,
            clashes: List<NamespaceClash>
        ): Map<String, Any?> {
            val issues = mutableListOf<Map<String, Any?>>()
            var overrideCount = 0
            var fallback = 0
            var notInstalled = 0

            if (coreMeta != null) {
                val overrides = coreMeta.getJsonObject("overrides") ?: JsonObject()
                val engine = coreMeta.getJsonObject("engine") ?: JsonObject()
                val pins = coreMeta.getJsonObject("controllers") ?: JsonObject()
                val overrideControllers = coreMeta.getJsonObject("overrideControllers") ?: JsonObject()

                overrideCount = overrides.size()

                for (view in overrides.fieldNames().sorted()) {
                    val themeContract = overrides.getValue(view)
                    val issue = if (view.contains(':')) {
                        val plugin = findPlugin(plugins, view.substringBefore(':'))

                        if (plugin == null) {
                            notInstalled++

                            continue
                        }

                        pluginViewIssue(view, themeContract, plugin, pins, overrideControllers)
                    } else {
                        engineViewIssue(view, themeContract, engine)
                    }

                    if (issue != null) {
                        issues.add(issue)
                        fallback++
                    }
                }
            }

            clashes.forEach {
                issues.add(
                    linkedMapOf(
                        "type" to NAMESPACE_CLASH,
                        "namespace" to it.namespace,
                        "pluginId" to it.pluginId,
                        "heldBy" to it.heldBy
                    )
                )
            }

            val status = when {
                coreMeta == null -> STATUS_UNKNOWN
                issues.any { it["type"] != NAMESPACE_CLASH } -> STATUS_OUTDATED
                else -> STATUS_OK
            }

            return linkedMapOf(
                "theme" to theme?.let { mapOf("id" to it.id, "version" to it.version) },
                "status" to status,
                "counts" to linkedMapOf(
                    "overrides" to overrideCount,
                    "active" to overrideCount - fallback - notInstalled,
                    "fallback" to fallback,
                    "pluginNotInstalled" to notInstalled
                ),
                "issues" to issues
            )
        }

        /** The plugin an override namespace names: its namespace, or its full plugin id as an alias. */
        private fun findPlugin(plugins: List<PluginContracts>, prefix: String) =
            plugins.firstOrNull { it.namespace == prefix } ?: plugins.firstOrNull { it.pluginId == prefix }

        private fun engineViewIssue(view: String, themeContract: Any?, engine: JsonObject): Map<String, Any?>? {
            if (!engine.containsKey(view)) return null

            val bundled = engine.getValue(view)

            if (sameVersion(themeContract, bundled)) return null

            return linkedMapOf(
                "type" to ENGINE_MISMATCH,
                "view" to view,
                "themeContract" to themeContract,
                "engineContract" to bundled
            )
        }

        private fun pluginViewIssue(
            override: String,
            themeContract: Any?,
            plugin: PluginContracts,
            pins: JsonObject,
            overrideControllers: JsonObject
        ): Map<String, Any?>? {
            val views = plugin.views?.getJsonObject("views") ?: return null
            val view = plugin.namespace + ":" + override.substringAfter(':')
            val entry = views.getValue(view) as? JsonObject
                ?: return linkedMapOf(
                    "type" to VIEW_REMOVED,
                    "view" to view,
                    "pluginId" to plugin.pluginId,
                    "pluginVersion" to plugin.version
                )
            val current = entry.getValue("contract") ?: 1

            if (!sameVersion(themeContract, current)) {
                return linkedMapOf(
                    "type" to CONTRACT_MISMATCH,
                    "view" to view,
                    "pluginId" to plugin.pluginId,
                    "pluginVersion" to plugin.version,
                    "themeContract" to themeContract,
                    "currentContract" to current
                )
            }

            // The running theme looks only at controllers that are pinned and registered; the same here.
            val registered = plugin.controllers ?: return null
            val used = (overrideControllers.getValue(override) as? io.vertx.core.json.JsonArray)?.list ?: return null

            for (controller in used) {
                val name = controller as? String ?: continue
                val pin = pins.getValue(name)
                val now = (registered.getValue(name) as? JsonObject)?.getValue("version")

                if (pin != null && now != null && !sameVersion(pin, now)) {
                    return linkedMapOf(
                        "type" to CONTROLLER_MISMATCH,
                        "view" to view,
                        "controller" to name,
                        "themeVersion" to pin,
                        "currentVersion" to now
                    )
                }
            }

            return null
        }

        /** Versions are small integers; a JSON 1 may arrive as Integer or Long. */
        private fun sameVersion(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toLong() == b.toLong() else a == b

        /**
         * The home page options (doc 01 section 9). With a `home` in `core-meta.json` they are the theme's
         * own options (the feed `posts` is always added, the engine always shows it); without one: the feed,
         * every active plugin page that sets `home` in its view, and `custom`.
         */
        fun homeOptions(coreMeta: JsonObject?, plugins: List<PluginContracts>): HomeOptions {
            val home = coreMeta?.getValue("home") as? JsonObject
            val pages = plugins.flatMap { pagePaths(it) }
            val options = mutableListOf<HomeOption>()

            if (home != null) {
                val configured = home.getValue("options") as? JsonObject ?: JsonObject()

                for (id in configured.fieldNames()) {
                    val option = configured.getValue(id) as? JsonObject ?: continue
                    val path = option.getValue("path") as? String
                    val kind = (option.getValue("kind") as? String)?.takeIf { it in KINDS } ?: when {
                        id == "posts" -> KIND_POSTS
                        path == "*" -> KIND_CUSTOM
                        path != null -> KIND_PATH
                        else -> KIND_PAGE
                    }

                    options.add(
                        HomeOption(
                            id, option.getValue("label") ?: id, kind, path,
                            kind != KIND_PATH || path == null || pathIsServed(path, pages, coreMeta)
                        )
                    )
                }
            } else {
                plugins.forEach { plugin ->
                    val views = plugin.views?.getJsonObject("views") ?: return@forEach

                    for (id in views.fieldNames().sorted()) {
                        val entry = views.getValue(id) as? JsonObject ?: continue
                        val homeEntry = entry.getValue("home") as? JsonObject ?: continue
                        val path = (entry.getValue("page") as? JsonObject)?.getValue("path") as? String ?: continue

                        options.add(HomeOption(id, homeEntry.getValue("label") ?: id, KIND_PATH, path, true))
                    }
                }

                options.add(HomeOption(KIND_CUSTOM, "Custom page", KIND_CUSTOM, "*", true))
            }

            if (options.none { it.id == "posts" }) {
                options.add(0, HomeOption("posts", "Posts", KIND_POSTS, null, true))
            }

            val default = (home?.getValue("default") as? String)?.takeIf { it.isNotEmpty() } ?: "posts"

            return HomeOptions(default, options, home == null || options.any { it.path == "*" })
        }

        private val KINDS = setOf(KIND_POSTS, KIND_PAGE, KIND_PATH, KIND_CUSTOM)

        /** The canonical paths of the pages a plugin registers (`page.path` of each view). */
        private fun pagePaths(plugin: PluginContracts): List<String> {
            val views = plugin.views?.getJsonObject("views") ?: return emptyList()

            return views.fieldNames().mapNotNull { id ->
                ((views.getValue(id) as? JsonObject)?.getValue("page") as? JsonObject)?.getValue("path") as? String
            }
        }

        /** True when [path] is the feed, a route the theme adds, or a page of an active plugin. */
        private fun pathIsServed(path: String, pages: List<String>, coreMeta: JsonObject?): Boolean {
            if (path == POSTS_PATH) return true

            val added = (coreMeta?.getValue("routes") as? JsonObject)?.getJsonArray("add")?.list ?: emptyList<Any?>()

            return (pages + added.filterIsInstance<String>()).any { patternMatches(it, path) }
        }

        /** `/store/[slug]` or `/store/:slug` matches `/store/vip`; every other segment must be equal. */
        internal fun patternMatches(pattern: String, path: String): Boolean {
            val a = pattern.trim('/').split('/')
            val b = path.trim('/').split('/')

            if (a.size != b.size) return false

            return a.indices.all { i ->
                a[i] == b[i] || (a[i].startsWith("[") && a[i].endsWith("]")) || a[i].startsWith(":")
            }
        }

        /** A site path the admin may type: starts with one slash, no spaces, no route pattern, not absurdly long. */
        fun isConcretePath(path: String): Boolean =
            path.length in 1..MAX_CUSTOM_PATH &&
                path.startsWith("/") &&
                !path.startsWith("//") &&
                !path.contains('\\') &&
                !path.contains('[') &&
                path.none { it.isWhitespace() || it.isISOControl() }

        /** The stored home page of [themeId] out of the `home_page` property value, null when none or unreadable. */
        fun homeValueFor(propertyValue: String?, themeId: String): String? {
            if (propertyValue.isNullOrBlank()) return null

            return try {
                JsonObject(propertyValue).getValue(themeId) as? String
            } catch (e: Exception) {
                null
            }
        }

        /**
         * The `home_page` property value after setting [themeId] to [value] (null clears it). The other
         * themes' choices are kept; an unreadable old value is dropped.
         */
        fun withHomeValue(propertyValue: String?, themeId: String, value: String?): JsonObject {
            val map = try {
                if (propertyValue.isNullOrBlank()) JsonObject() else JsonObject(propertyValue)
            } catch (e: Exception) {
                JsonObject()
            }

            if (value == null) map.remove(themeId) else map.put(themeId, value)

            return map
        }
    }
}
