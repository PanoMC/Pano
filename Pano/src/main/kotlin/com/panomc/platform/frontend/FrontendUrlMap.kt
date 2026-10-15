package com.panomc.platform.frontend

import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.ui.FrontendMode
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * One front-end page Pano links to (mails, redirects, payment returns, the Minecraft plugin).
 *
 * @property id `auth.login`, or `<ns>.<name>` for a plugin (`market.order`)
 * @property defaultPath site-relative, `{name}` placeholders, may carry a query (`/activate?token={token}`)
 * @property fallback Pano has a plain page at `/_pano/<id>` until a front-end takes the target over
 * @property createsSession the page sets a session cookie on Pano's host (doc 05 section 10.1)
 * @property owner `core` or the plugin id
 */
data class FrontendTarget(
    val id: String,
    val defaultPath: String,
    val fallback: Boolean = false,
    val createsSession: Boolean = false,
    val owner: String = CoreFrontendTargets.OWNER
)

/** Which of the four steps of doc 05 section 10.1 answered. */
enum class UrlSource {
    /** Step 1: the admin's override (system property `frontend_urls`). */
    OVERRIDE,

    /** Step 2: the `urls` of the active front-end (theme `core-meta.json`, custom app manifest, descriptor). */
    FRONTEND,

    /** Step 3: the target's default path through the active theme's route config. */
    THEME,

    /** Step 4: Pano's own page at `/_pano/<id>`. */
    FALLBACK
}

/** A target and where it points right now; [path] is null when nothing serves it ("no such page"). */
data class ResolvedTarget(
    val id: String,
    val owner: String,
    val source: UrlSource?,
    val path: String?,
    val defaultPath: String,
    val fallback: Boolean,
    val createsSession: Boolean
)

/** A plugin's `frontend-targets.json` cannot be used; handled like a refused endpoint path (the plugin load fails). */
class FrontendTargetsRefusal(message: String) : RuntimeException(message)

/**
 * What the URL map reads from the rest of the platform. A separate interface so the four resolution
 * steps can be tested without a database, a theme folder or a running UI.
 */
interface FrontendUrlInputs {
    /** What serves the site's pages right now. */
    fun mode(): FrontendMode

    /** Where visitors are: `frontend.site-url`, else `website-url`. No trailing slash; empty before setup. */
    fun siteUrl(): String

    /** Where Pano answers: `website-url`. No trailing slash. */
    fun websiteUrl(): String

    /** Step 1: target id to a path or an absolute URL. */
    fun overrides(): Map<String, String>

    /** Step 2: the active front-end's `urls`. A value is a path or URL string, or `false` (no such page). */
    fun frontendUrls(): Map<String, Any?>

    /** Step 3: the active theme's route config, null when there is none. */
    fun themeRoutes(): ThemeRouteMap?

    /** Reads what is kept in the database. */
    suspend fun reload() {}

    /** Writes the overrides (the whole map) and uses them from now on. */
    suspend fun saveOverrides(overrides: Map<String, String>, sqlClient: SqlClient) {
        throw UnsupportedOperationException()
    }
}

/**
 * The front-end URL map (doc 05 section 10.1): where each page that Pano links to lives on this site.
 * Core's targets are in [CoreFrontendTargets]; a plugin declares its own in `frontend-targets.json`
 * (read when the plugin loads, dropped when it unloads).
 *
 * Resolution, first hit wins: (1) the admin's override, (2) the active front-end's `urls` entry (a
 * string wins; `false` means "no such page" and skips step 3), (3) in `THEME` mode the default path
 * through the theme's route config (a renamed route gives the public path, a disabled one counts as
 * no such page), (4) `/_pano/<id>` when the target has a fallback page, else null: callers omit the link.
 *
 * Steps 1 to 3 are relative to `frontend.site-url` (or `website-url`), step 4 to `website-url`.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class FrontendUrlMap(
    private val inputs: FrontendUrlInputs,
    private val logger: Logger
) {
    private val pluginTargets = ConcurrentHashMap<String, FrontendTarget>()
    private val targetsOfPlugin = ConcurrentHashMap<String, List<String>>()

    /**
     * Says whether a built-in page exists for a full target id (`market.order`). The registry of fallback
     * pages sets this; until it does, a `fallback: true` target cannot be checked and is accepted.
     */
    @Volatile
    var fallbackPageCheck: ((targetId: String) -> Boolean)? = null

    fun target(id: String): FrontendTarget? = CoreFrontendTargets.byId[id] ?: pluginTargets[id]

    /** Absolute URL of [target], or null when nothing serves it. */
    fun url(target: String, params: Map<String, String> = emptyMap()): String? {
        val (definition, located) = locate(target) ?: return null
        val filled = fill(definition.id, located.location, params)

        return if (isAbsolute(filled)) filled else baseOf(located.source) + filled
    }

    /** Site-relative path of [target] (an absolute location, from an override, comes back as it is). */
    fun path(target: String, params: Map<String, String> = emptyMap()): String? {
        val (definition, located) = locate(target) ?: return null

        return fill(definition.id, located.location, params)
    }

    /** Absolute URL of [target] with its `{name}` placeholders left in, for the Minecraft plugin. */
    fun template(target: String): String? {
        val (_, located) = locate(target) ?: return null

        return if (isAbsolute(located.location)) located.location else baseOf(located.source) + located.location
    }

    /** Site-relative path of [target] with its `{name}` placeholders left in. */
    fun pathTemplate(target: String): String? = locate(target)?.second?.location

    /** Where [target] lives, without query and placeholders; for matching a request path. Null for an absolute location. */
    fun routePath(target: String): String? {
        val location = pathTemplate(target) ?: return null

        if (isAbsolute(location)) {
            return null
        }

        return ThemeRouteMap.splitSuffix(location).first.ifEmpty { "/" }
    }

    /** Every known target and where it points, core first, then plugins by id. */
    fun targets(): List<ResolvedTarget> =
        (CoreFrontendTargets.all + pluginTargets.values.sortedBy { it.id }).map { definition ->
            val located = locate(definition)

            ResolvedTarget(
                id = definition.id,
                owner = definition.owner,
                source = located?.source,
                path = located?.location,
                defaultPath = definition.defaultPath,
                fallback = definition.fallback,
                createsSession = definition.createsSession
            )
        }

    /** `frontend.site-url` (or `website-url`), the base of the public `GET /frontend/urls`. */
    fun siteUrl(): String = inputs.siteUrl()

    /** Reads the overrides and the descriptor from the database. */
    suspend fun reload() = inputs.reload()

    /** Replaces the admin's overrides. The caller has validated them with [validateOverrides]. */
    suspend fun saveOverrides(overrides: Map<String, String>, sqlClient: SqlClient) = inputs.saveOverrides(overrides, sqlClient)

    /** The overrides in force. */
    fun overrides(): Map<String, String> = inputs.overrides()

    /**
     * Checks an override map against the known targets: the key must be one, the value a site path
     * (`/x`, not `//x`) or an `http(s)` URL. Returns the field name mapped to a code for each bad entry.
     */
    fun validateOverrides(overrides: Map<String, String>): Map<String, String> {
        val problems = linkedMapOf<String, String>()

        overrides.forEach { (id, value) ->
            when {
                target(id) == null -> problems[id] = "UNKNOWN_TARGET"
                !isLocation(value) || value.length > MAX_LOCATION_LENGTH -> problems[id] = "INVALID_LOCATION"
            }
        }

        return problems
    }

    // --- plugin targets -------------------------------------------------------

    /**
     * Reads a plugin's `frontend-targets.json` ([text]; null = the plugin has none) and registers its
     * targets with ids prefixed `<namespace>.`. A target with `fallback: true` needs a built-in page, so
     * with a [fallbackPageCheck] installed an unmatched one fails the plugin load. Nothing
     * is registered when the file is refused.
     */
    fun registerPlugin(pluginId: String, namespace: String, text: String?) {
        unregisterPlugin(pluginId)

        if (text == null) {
            return
        }

        val parsed = parseTargets(pluginId, namespace, text)
        val check = fallbackPageCheck

        if (check != null) {
            parsed.firstOrNull { it.fallback && !check(it.id) }?.let {
                throw FrontendTargetsRefusal(
                    "frontend-targets.json of '$pluginId' declares target '${it.id}' with fallback: true, " +
                        "but the plugin registers no fallback page for it."
                )
            }
        }

        val accepted = mutableListOf<String>()

        parsed.forEach { definition ->
            val existing = target(definition.id)

            if (existing != null) {
                logger.warn(
                    "Front-end target '{}' of plugin '{}' is already owned by '{}' and is left out.",
                    definition.id, pluginId, existing.owner
                )

                return@forEach
            }

            pluginTargets[definition.id] = definition
            accepted.add(definition.id)
        }

        targetsOfPlugin[pluginId] = accepted
    }

    /** Drops every target of the plugin. */
    fun unregisterPlugin(pluginId: String) {
        targetsOfPlugin.remove(pluginId)?.forEach { pluginTargets.remove(it) }
    }

    private fun parseTargets(pluginId: String, namespace: String, text: String): List<FrontendTarget> {
        val root = try {
            JsonObject(text)
        } catch (e: Exception) {
            throw FrontendTargetsRefusal("frontend-targets.json of '$pluginId' is not a JSON object: ${e.message}")
        }

        return root.fieldNames().map { key ->
            if (!KEY_PATTERN.matches(key)) {
                throw FrontendTargetsRefusal("frontend-targets.json of '$pluginId': '$key' is not a valid target name.")
            }

            val id = "$namespace.$key"

            val (path, fallback, createsSession) = when (val value = root.getValue(key)) {
                is String -> Triple(value, false, false)

                is JsonObject -> Triple(
                    value.getValue("path") as? String,
                    value.getValue("fallback") as? Boolean ?: false,
                    value.getValue("createsSession") as? Boolean ?: false
                )

                else -> Triple(null, false, false)
            }

            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                throw FrontendTargetsRefusal("frontend-targets.json of '$pluginId': target '$id' needs a site path starting with a single '/'.")
            }

            FrontendTarget(id, path, fallback, createsSession, pluginId)
        }
    }

    // --- resolution -----------------------------------------------------------

    private class Located(val source: UrlSource, val location: String)

    private fun locate(id: String): Pair<FrontendTarget, Located>? {
        val definition = target(id) ?: return null
        val located = locate(definition) ?: return null

        return definition to located
    }

    private fun locate(definition: FrontendTarget): Located? {
        inputs.overrides()[definition.id]?.trim()?.takeIf { isLocation(it) }?.let { return Located(UrlSource.OVERRIDE, it) }

        var skipTheme = false
        val declared = inputs.frontendUrls()

        if (declared.containsKey(definition.id)) {
            when (val value = declared[definition.id]) {
                is String -> value.trim().takeIf { isLocation(it) }?.let { return Located(UrlSource.FRONTEND, it) }
                false -> skipTheme = true
                else -> Unit
            }
        }

        if (!skipTheme && inputs.mode() == FrontendMode.THEME) {
            val routes = inputs.themeRoutes() ?: ThemeRouteMap.IDENTITY
            val path = routes.publicTemplate(definition.defaultPath)

            if (path != null) {
                return Located(UrlSource.THEME, path)
            }
        }

        if (definition.fallback) {
            return Located(UrlSource.FALLBACK, "$FALLBACK_PREFIX${definition.id}")
        }

        return null
    }

    private fun baseOf(source: UrlSource) = if (source == UrlSource.FALLBACK) inputs.websiteUrl() else inputs.siteUrl()

    /**
     * Puts the params into [location]. A placeholder with no param is an [IllegalArgumentException]; a
     * param the location does not name is appended as a query parameter, in the order given.
     */
    private fun fill(id: String, location: String, params: Map<String, String>): String {
        val used = HashSet<String>()

        var filled = PLACEHOLDER.replace(location) { match ->
            val name = match.groupValues[1]
            val value = params[name]
                ?: throw IllegalArgumentException("Front-end target '$id' needs the parameter '$name'.")

            used.add(name)

            encode(value)
        }

        val extra = params.filterKeys { it !in used }

        if (extra.isNotEmpty()) {
            val fragmentAt = filled.indexOf('#')
            val fragment = if (fragmentAt == -1) "" else filled.substring(fragmentAt)

            if (fragmentAt != -1) {
                filled = filled.substring(0, fragmentAt)
            }

            val query = extra.entries.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

            filled += (if (filled.contains('?')) "&" else "?") + query + fragment
        }

        return filled
    }

    companion object {
        const val FALLBACK_PREFIX = "/_pano/"
        const val MAX_LOCATION_LENGTH = 2000

        private val PLACEHOLDER = Regex("""\{([A-Za-z0-9_-]+)}""")
        private val KEY_PATTERN = Regex("""[A-Za-z0-9][A-Za-z0-9_-]*""")

        fun isAbsolute(location: String) = location.startsWith("http://", true) || location.startsWith("https://", true)

        /** A site path (`/x`, never `//x`) or an `http(s)` URL; nothing else is ever put in a link. */
        fun isLocation(value: String) =
            value.isNotEmpty() && ((value.startsWith("/") && !value.startsWith("//")) || isAbsolute(value))

        /** Percent-encodes everything but the unreserved characters of RFC 3986. */
        internal fun encode(value: String): String {
            val out = StringBuilder()

            for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
                val c = byte.toInt() and 0xFF

                if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                ) {
                    out.append(c.toChar())
                } else {
                    out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                }
            }

            return out.toString()
        }
    }
}

/**
 * The platform side of [FrontendUrlInputs]: config, the bound front-end, the theme's `core-meta.json`
 * and the two system properties. Collaborators come through providers, so this bean can be injected into
 * the mail, maintenance and routing classes without a construction cycle.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PlatformFrontendUrlInputs(
    private val configManager: ConfigManager,
    private val uiManager: ObjectProvider<UIManager>,
    private val databaseManager: ObjectProvider<DatabaseManager>,
    private val logger: Logger
) : FrontendUrlInputs {
    @Volatile
    private var overridesCache: Map<String, String> = emptyMap()

    @Volatile
    private var descriptorUrls: Map<String, Any?> = emptyMap()

    private class ThemeMeta(
        val themeId: String,
        val modified: Long,
        val checkedAt: Long,
        val routes: ThemeRouteMap,
        val urls: Map<String, Any?>
    )

    @Volatile
    private var themeMeta: ThemeMeta? = null

    override fun mode(): FrontendMode = uiManager.getObject().frontendMode

    override fun websiteUrl(): String = configManager.config.websiteUrl.trim().trimEnd('/')

    override fun siteUrl(): String =
        configManager.config.effectiveFrontend.siteUrl.trim().trimEnd('/').ifEmpty { websiteUrl() }

    override fun overrides(): Map<String, String> = overridesCache

    override fun frontendUrls(): Map<String, Any?> = when (mode()) {
        FrontendMode.THEME -> meta()?.urls ?: emptyMap()
        FrontendMode.CUSTOM_APP -> customAppUrls()
        FrontendMode.EXTERNAL, FrontendMode.NONE -> descriptorUrls
    }

    override fun themeRoutes(): ThemeRouteMap? = meta()?.routes

    override suspend fun reload() {
        val sqlClient = databaseManager.getObject().getSqlClient()
        val dao = databaseManager.getObject().systemPropertyDao

        overridesCache = dao.getByOption(OVERRIDES_PROPERTY, sqlClient)?.value
            ?.let { parseStringMap(it) }
            ?: emptyMap()

        descriptorUrls = dao.getByOption(DESCRIPTOR_PROPERTY, sqlClient)?.value
            ?.let { parseUrls(runCatching { JsonObject(it).getJsonObject("urls") }.getOrNull()) }
            ?: emptyMap()
    }

    /**
     * Uses the `urls` of a freshly cached (or cleared) descriptor from now on, so a saved or refreshed
     * descriptor reaches the map at once and not only after a restart. [descriptor] is the cached JSON.
     */
    fun useDescriptor(descriptor: JsonObject?) {
        descriptorUrls = parseUrls(runCatching { descriptor?.getJsonObject("urls") }.getOrNull())
    }

    override suspend fun saveOverrides(overrides: Map<String, String>, sqlClient: SqlClient) {
        val dao = databaseManager.getObject().systemPropertyDao
        val value = JsonObject().apply { overrides.forEach { (key, location) -> put(key, location) } }.encode()

        if (dao.existsByOption(OVERRIDES_PROPERTY, sqlClient)) {
            dao.update(OVERRIDES_PROPERTY, value, sqlClient)
        } else {
            dao.add(SystemProperty(option = OVERRIDES_PROPERTY, value = value), sqlClient)
        }

        overridesCache = overrides.toMap()
    }

    private fun parseStringMap(text: String): Map<String, String> {
        val json = try {
            JsonObject(text)
        } catch (e: Exception) {
            logger.warn("System property {} is not a JSON object and is ignored.", OVERRIDES_PROPERTY)

            return emptyMap()
        }

        return json.fieldNames().mapNotNull { key -> (json.getValue(key) as? String)?.let { key to it } }.toMap()
    }

    /** The core-meta of the active theme, looked at again after [RECHECK_MS]. */
    private fun meta(): ThemeMeta? {
        val ui = uiManager.getObject()
        val themeId = ui.activeTheme

        if (themeId.isEmpty()) {
            return null
        }

        val now = System.currentTimeMillis()
        val cached = themeMeta

        if (cached != null && cached.themeId == themeId && now - cached.checkedAt < RECHECK_MS) {
            return cached
        }

        val file = ui.getThemeFile(themeId, "core-meta.json")
        val modified = file?.lastModified() ?: -1L

        if (cached != null && cached.themeId == themeId && cached.modified == modified) {
            return ThemeMeta(themeId, modified, now, cached.routes, cached.urls).also { themeMeta = it }
        }

        val loaded = if (file == null) {
            ThemeMeta(themeId, modified, now, ThemeRouteMap.IDENTITY, emptyMap())
        } else {
            readMeta(themeId, file, modified, now)
        }

        themeMeta = loaded

        return loaded
    }

    private fun readMeta(themeId: String, file: File, modified: Long, now: Long): ThemeMeta {
        val json = try {
            JsonObject(file.readText())
        } catch (e: Exception) {
            logger.warn("core-meta.json of theme '{}' cannot be read, its routes and urls are ignored: {}", themeId, e.message)

            return ThemeMeta(themeId, modified, now, ThemeRouteMap.IDENTITY, emptyMap())
        }

        val routes = try {
            ThemeRouteMap.fromCoreMeta(runCatching { json.getJsonObject("routes") }.getOrNull())
        } catch (e: IllegalArgumentException) {
            logger.warn("Route config of theme '{}' is invalid and is ignored: {}", themeId, e.message)

            ThemeRouteMap.IDENTITY
        }

        return ThemeMeta(themeId, modified, now, routes, parseUrls(runCatching { json.getJsonObject("urls") }.getOrNull()))
    }

    private fun customAppUrls(): Map<String, Any?> {
        val id = uiManager.getObject().activeFrontendId()
        val manifest = File(File(uiManager.getObject().customAppsFolder, id), "manifest.json")

        if (!manifest.isFile) {
            return emptyMap()
        }

        return try {
            parseUrls(JsonObject(manifest.readText()).getJsonObject("urls"))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val OVERRIDES_PROPERTY = "frontend_urls"
        const val DESCRIPTOR_PROPERTY = "frontend_descriptor"

        private const val RECHECK_MS = 2_000L

        /** `urls` of a front-end: a string is a location, `false` is "no such page"; anything else is left out. */
        internal fun parseUrls(urls: JsonObject?): Map<String, Any?> {
            if (urls == null) {
                return emptyMap()
            }

            val out = linkedMapOf<String, Any?>()

            urls.fieldNames().forEach { key ->
                when (val value = urls.getValue(key)) {
                    is String -> out[key] = value
                    false -> out[key] = false
                    else -> Unit
                }
            }

            return out
        }
    }
}
