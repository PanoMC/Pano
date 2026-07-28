package com.panomc.platform.maintenance

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Template
import com.panomc.platform.AppConstants
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.log.MaintenanceModeBannedIpLog
import com.panomc.platform.auth.panel.log.MaintenanceModeUnbannedIpLog
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.i18n.I18nManager
import com.panomc.platform.route.WebsiteUrlRedirectHandler
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.TrustedProxyIpResolver
import io.vertx.core.Vertx
import io.vertx.core.http.Cookie
import io.vertx.core.http.CookieSameSite
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns every piece of maintenance-mode state: the settings read, the request classification, the
 * bypass decision, the on-disk `page.hbs` and the on-disk IP ban store.
 *
 * Nothing here decides *how* a request is answered — that is [MaintenanceGateHandler]'s job. This
 * class only answers questions and renders HTML.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MaintenanceModeManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val databaseManager: DatabaseManager,
    private val i18nManager: I18nManager
) {
    companion object {
        /** Per-request memoisation key so the gate and `Api.checkMaintenance` never both pay. */
        const val CTX_ACCESS_KEY = "maintenance.access"

        const val LOGIN_PATH = "/api/maintenance/login"
        const val SKIP_PATH = "/api/maintenance/skip"
        const val EXIT_PATH = "/api/maintenance/exit"

        const val LOGIN_USERNAME_FIELD = "usernameOrEmail"
        const val LOGIN_PASSWORD_FIELD = "password"
        const val LOGIN_NONCE_FIELD = "_nonce"

        /** Query parameter the login endpoint redirects back with; values below. */
        const val ERROR_PARAM = "e"
        const val ERROR_INVALID = "invalid"
        const val ERROR_IP_BANNED = "ip_banned"
        const val ERROR_NO_ACCESS = "no_access"
        const val ERROR_RATE_LIMITED = "rate_limited"
        const val ERROR_CSRF = "csrf"

        const val RETRY_AFTER_SECONDS = 3600
        const val ROBOTS_TXT_BODY = "User-agent: *\nDisallow: /\n"

        const val PAGE_FILE_NAME = "page.hbs"
        const val BANS_FILE_NAME = "maintenance-mode-banned-ips.json"
        const val DEFAULT_PAGE_RESOURCE = "maintenance/page-template.hbs"

        /**
         * Composed into the page in place of a title/message the admin left blank, so the fallback
         * copy is resolved per request from the platform locale instead of being frozen at save
         * time. Escaped for the title (plain text), raw for the message (the bundle ships HTML).
         */
        private const val DEFAULT_TITLE_SLOT = "{{defaultTitle}}"
        private const val DEFAULT_MESSAGE_SLOT = "{{{defaultMessage}}}"

        /**
         * Must match the marker in [DEFAULT_PAGE_RESOURCE]. Bump BOTH whenever the shipped design
         * changes, so installations composed from the previous template regenerate on boot instead
         * of serving a stale page forever.
         */
        private const val TEMPLATE_VERSION_MARKER = "<!--PANO_TEMPLATE:2-->"

        /** Same placeholder the themes use in `footer.been-created-with`. */
        private const val PANO_PLACEHOLDER = "{pano}"

        private const val DEFAULT_LOGIN_LOCATION = "/login"

        private const val BANS_FILE_VERSION = 1
        private const val PAGE_FILE_PERMISSIONS = "rw-r--r--"
        private const val BANS_FILE_PERMISSIONS = "rw-------"

        private const val ACCESS_TTL_MS = 30_000L
        private const val MAX_ACCESS_CACHE_ENTRIES = 10_000
        private const val MAX_BAN_ENTRIES = 10_000
        private const val MAX_TRANSLATION_CACHE_ENTRIES = 256

        private const val NONCE_COOKIE_NAME = "maintenance_nonce"
        private const val INSECURE_COOKIE_SUFFIX = "_http"
        private const val LOGIN_NONCE_TTL_SECONDS = 1800L

        private const val MAX_USERNAME_LENGTH = 64
        private const val MAX_USER_AGENT_LENGTH = 255

        private val LOGO_BLOCK_REGEX = Regex("<!--PANO_LOGO_START-->[\\s\\S]*?<!--PANO_LOGO_END-->")

        /**
         * Last resort when neither `page.hbs` nor the bundled resource can be read (corrupt jar,
         * unwritable folder). Contains no admin-supplied content by design.
         */
        const val FALLBACK_HTML =
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                    "<meta name=\"robots\" content=\"noindex,nofollow\">" +
                    "<title>We'll be right back</title></head>" +
                    "<body style=\"margin:0;min-height:100vh;display:flex;align-items:center;" +
                    "justify-content:center;font:16px/1.6 system-ui,sans-serif\">" +
                    "<main style=\"max-width:34rem;padding:2rem;text-align:center\">" +
                    "<h1>We'll be right back</h1>" +
                    "<p>The site is temporarily unavailable while we carry out maintenance.</p>" +
                    "</main></body></html>"
    }

    data class Access(
        val isLoggedIn: Boolean,
        val userId: Long?,
        val canBypass: Boolean,
        val hasPanelAccess: Boolean
    ) {
        companion object {
            val ANONYMOUS = Access(false, null, false, false)
        }
    }

    enum class PathClass { INFRASTRUCTURE, UNMATCHED_API, PANEL, PAGE }

    /**
     * [ip] is what gets counted and banned; [socketPeer] is the TCP peer it was derived from and is
     * persisted so an operator can spot a spoofing proxy in the panel.
     */
    data class BanIdentity(
        val ip: String,
        val socketPeer: String
    )

    data class BanEntry(
        val ip: String,
        val socketPeer: String,
        val bannedAt: Long,
        val attempts: Int,
        val lastUsernameTried: String?,
        val lastUserAgent: String?
    ) {
        fun toJson(): JsonObject = JsonObject()
            .put("ip", ip)
            .put("socketPeer", socketPeer)
            .put("bannedAt", bannedAt)
            .put("attempts", attempts)
            .put("lastUsernameTried", lastUsernameTried)
            .put("lastUserAgent", lastUserAgent)
    }

    /** A single line of feedback rendered above the maintenance/login card. */
    data class Notice(
        val translationKey: String,
        val kind: Kind = Kind.DANGER
    ) {
        enum class Kind { DANGER, INFO }

        companion object {
            val THEME_SWITCHING = Notice("maintenance.notice.theme-switching", Kind.INFO)
            val IP_BANNED = Notice("maintenance.notice.ip-banned")

            /** Maps a `?e=` wire code (snake_case) onto a translation key (kebab-case). */
            fun fromErrorCode(code: String?): Notice? = when (code) {
                MaintenanceModeManager.ERROR_INVALID -> Notice("maintenance.notice.invalid")
                MaintenanceModeManager.ERROR_IP_BANNED -> IP_BANNED
                MaintenanceModeManager.ERROR_NO_ACCESS -> Notice("maintenance.notice.no-access")
                MaintenanceModeManager.ERROR_RATE_LIMITED -> Notice("maintenance.notice.rate-limited")
                MaintenanceModeManager.ERROR_CSRF -> Notice("maintenance.notice.csrf")
                else -> null
            }
        }
    }

    private class CachedAccess(val access: Access, val expiresAt: Long)
    private class FailCounter(@Volatile var count: Int, @Volatile var lastAt: Long)
    private class CompiledPage(val template: Template, val lastModified: Long, val length: Long)

    private val accessCache = ConcurrentHashMap<String, CachedAccess>()
    private val translationCache = ConcurrentHashMap<String, String>()
    private val failCounters = ConcurrentHashMap<String, FailCounter>()   // NOT persisted
    private val bans = ConcurrentHashMap<String, BanEntry>()              // persisted

    @Volatile
    private var compiledPage: CompiledPage? = null
    private val compiledPartials = java.util.concurrent.ConcurrentHashMap<PageTemplate, CompiledPage>()

    @Volatile
    private var settingsFingerprint: String = ""

    private val handlebars by lazy { Handlebars() }
    private val bansMutex = Mutex()
    private val secureRandom by lazy { SecureRandom() }

    private val folder: File by lazy { File(AppConstants.MAINTENANCE_FOLDER_PATH) }
    private val pageFile: File by lazy { File(folder, PAGE_FILE_NAME) }
    private val bansFile: File by lazy { File(folder, BANS_FILE_NAME) }

    // --- lifecycle ----------------------------------------------------------

    /**
     * Blocking file I/O; call from `Main` inside `executeBlocking`, before `initRoutes()`.
     * Never throws — a broken maintenance folder must not stop the platform from booting.
     */
    fun init() {
        try {
            if (!folder.exists()) {
                folder.mkdirs()
            }

            loadBansFromDisk()

            // Repairs installs that booted an earlier build, which composed page.hbs before the
            // translation bundle existed and persisted empty slots. Matched on the exact composed
            // signature so a hand-edited page — a documented, supported thing to do — is never
            // clobbered.
            if (!pageFile.exists() || needsRecompose()) {
                composeAndSavePage()
            }
        } catch (t: Throwable) {
            logger.warn("Failed to initialize maintenance mode state; falling back to defaults", t)
        }
    }

    // --- settings -----------------------------------------------------------

    /**
     * Always read through [ConfigManager.config]: live reload replaces the whole [PanoConfig]
     * instance every ~5 s, so caching the object here would pin a stale copy. The fingerprint
     * compare is the entire live-reload story — no listener wiring.
     */
    fun settings(): PanoConfig.Companion.MaintenanceConfig {
        // A hand-deleted `maintenance { }` block deserialises to null through Gson's Unsafe path
        // even though the Kotlin type is non-null.
        val current: PanoConfig.Companion.MaintenanceConfig? = configManager.config.maintenance
        val maintenance = current ?: PanoConfig.Companion.MaintenanceConfig()

        val fingerprint = buildString {
            append(maintenance.enabled); append('|')
            append(maintenance.bypassPermissionNode); append('|')
            append(maintenance.showLoginButton); append('|')
            append(maintenance.customLoginUrl); append('|')
            // The page and its notices are rendered in the platform language, so a locale change
            // has to drop the resolved strings even though it changes no maintenance setting.
            append(configManager.config.locale)
        }

        if (fingerprint != settingsFingerprint) {
            settingsFingerprint = fingerprint
            accessCache.clear()
            translationCache.clear()
        }

        return maintenance
    }

    fun isEnabled(): Boolean = setupManager.isSetupDone() && settings().enabled

    // --- path classification ------------------------------------------------

    /** Callers must pass `context.normalizedPath()`; route matching uses the normalized form. */
    fun classify(normalizedPath: String, method: HttpMethod): PathClass = when {
        normalizedPath.startsWith("/panel/api/") -> PathClass.INFRASTRUCTURE

        // Only the ACME HTTP-01 challenge is exempt, and only for the methods AcmeManager's order-0
        // route actually answers. The rest of /.well-known/ (security.txt, change-password,
        // appspecific/*) is an ordinary page: exempting the whole prefix handed it to the order-5
        // theme proxy, which is the one thing maintenance mode exists to prevent.
        normalizedPath.startsWith("/.well-known/acme-challenge/") &&
                (method == HttpMethod.GET || method == HttpMethod.HEAD) -> PathClass.INFRASTRUCTURE

        normalizedPath.startsWith("/api/") -> PathClass.UNMATCHED_API
        normalizedPath == "/panel" || normalizedPath.startsWith("/panel/") -> PathClass.PANEL
        else -> PathClass.PAGE
    }

    // --- access resolution --------------------------------------------------

    suspend fun resolveAccess(context: RoutingContext): Access {
        context.get<Access>(CTX_ACCESS_KEY)?.let { return it }

        // Fast path: no auth cookie and no Authorization header => definitively anonymous, and the
        // gate sits on /*, so this is what keeps asset traffic at ZERO database queries.
        val token = authProvider.getTokenFromRoutingContext(context)

        if (token == null) {
            context.put(CTX_ACCESS_KEY, Access.ANONYMOUS)

            return Access.ANONYMOUS
        }

        val now = System.currentTimeMillis()

        accessCache[token]?.let {
            if (it.expiresAt > now) {
                context.put(CTX_ACCESS_KEY, it.access)

                return it.access
            }
        }

        val access = computeAccess(context, token)

        if (accessCache.size > MAX_ACCESS_CACHE_ENTRIES) {
            accessCache.clear()
        }

        accessCache[token] = CachedAccess(access, now + ACCESS_TTL_MS)
        context.put(CTX_ACCESS_KEY, access)

        return access
    }

    /** For callers that already authenticated the user (e.g. `Api.checkMaintenance`). */
    suspend fun accessForUser(userId: Long): Access {
        val hasPanelAccess = permissionManager.hasPermission(userId, AccessPanelPermission())

        // A blank node must never reach hasPermissionNode: matchesNode returns true for an empty
        // target only for holders of "*", which is not what "use the default" means.
        val node = settings().bypassPermissionNode.trim()
        val canBypass =
            if (node.isEmpty()) hasPanelAccess
            else permissionManager.hasPermissionNode(userId, node)

        return Access(true, userId, canBypass, hasPanelAccess)
    }

    private suspend fun computeAccess(context: RoutingContext, token: String): Access {
        if (!authProvider.isLoggedIn(context)) {
            return Access.ANONYMOUS
        }

        val userId = try {
            authProvider.getUserIdFromToken(token)
        } catch (_: Exception) {
            return Access.ANONYMOUS
        }

        return try {
            accessForUser(userId)
        } catch (t: Throwable) {
            logger.warn("Failed to resolve maintenance access for user {}: {}", userId, t.message)

            Access(true, userId, false, false)
        }
    }

    fun invalidateAccessCache() {
        accessCache.clear()
    }

    // --- login locations ----------------------------------------------------

    /**
     * A custom address only exists as an alternative to the button: with the button on, the login
     * is where the button points and everyone can see it, so a second secret address would be
     * pointless. The panel greys the field out for the same reason, and this keeps a hand-edited
     * `config.conf` from creating a state the panel cannot show.
     */
    fun loginLocations(): Set<String> {
        val maintenance = settings()

        if (maintenance.showLoginButton) {
            return setOf(DEFAULT_LOGIN_LOCATION)
        }

        // Hiding the button hides the button — it does not move the door. Only an address the
        // admin actually typed replaces /login; anonymous /panel traffic is redirected here.
        return setOf(normalizePath(maintenance.customLoginUrl) ?: DEFAULT_LOGIN_LOCATION)
    }

    /** The URL the "Log in" button points at, or null when the button is turned off. */
    fun advertisedLoginUrl(): String? {
        if (!settings().showLoginButton) {
            return null
        }

        return DEFAULT_LOGIN_LOCATION
    }

    fun isLoginLocation(normalizedPath: String): Boolean {
        val path = trimTrailingSlash(normalizedPath)

        return loginLocations().any { it == path }
    }

    /**
     * Forces a leading slash, drops a trailing slash, rejects anything under the API prefixes.
     *
     * It also performs the three transformations the router performs before matching — decode the
     * unreserved percent-escapes, remove dot segments, collapse duplicate slashes — because
     * [isLoginLocation] compares against `context.normalizedPath()`. Without them a stored
     * `//login` or `/staff/../entrance` could never match a real request, and the login form would
     * be unreachable on a closed site. `config.conf` is a supported (unvalidated) write channel for
     * `custom-login-url`, so the rule has to live here rather than only in the panel endpoint.
     *
     * The steps are reimplemented rather than delegated to `io.vertx.core.internal.net.RFC3986`,
     * which is an internal package with no cross-minor stability guarantee.
     */
    fun normalizePath(raw: String): String? {
        var path = raw.trim()

        if (path.isEmpty()) {
            return null
        }

        path = path.substringBefore('?').substringBefore('#')

        if (!path.startsWith("/")) {
            path = "/$path"
        }

        path = removeDotSegments(decodeUnreservedEscapes(path) ?: return null)
        path = trimTrailingSlash(path)

        val lowered = path.lowercase()

        if (lowered == "/api" || lowered.startsWith("/api/") ||
            lowered == "/panel/api" || lowered.startsWith("/panel/api/")
        ) {
            return null
        }

        return path.ifEmpty { null }
    }

    /**
     * Decodes only the unreserved escapes, the way the router does. Returns null on a malformed
     * escape: Vert.x throws on those, so such a path could never be matched anyway.
     */
    private fun decodeUnreservedEscapes(path: String): String? {
        if (!path.contains('%')) {
            return path
        }

        val out = StringBuilder(path.length)
        var index = 0

        while (index < path.length) {
            val char = path[index]

            if (char != '%') {
                out.append(char)
                index++

                continue
            }

            if (index + 3 > path.length) {
                return null
            }

            // toIntOrNull(16) also parses a sign, so "%-c" would otherwise slip through as -12.
            val octet = path.substring(index + 1, index + 3).toIntOrNull(16) ?: return null

            if (octet < 0) {
                return null
            }

            if (isUnreservedOctet(octet)) {
                out.append(octet.toChar())
            } else {
                out.append(path, index, index + 3)
            }

            index += 3
        }

        return out.toString()
    }

    /** ALPHA / DIGIT / "-" / "." / "_" / "~" — RFC 3986 §2.3. */
    private fun isUnreservedOctet(octet: Int): Boolean =
        octet in 0x41..0x5A || octet in 0x61..0x7A || octet in 0x30..0x39 ||
                octet == 0x2D || octet == 0x2E || octet == 0x5F || octet == 0x7E

    /** RFC 3986 §5.2.4 plus the router's non-standard `//` → `/` collapse. */
    private fun removeDotSegments(raw: String): String {
        var path = raw
        val out = StringBuilder(path.length)
        var index = 0

        while (index < path.length) {
            when {
                path.startsWith("./", index) -> index += 2
                path.startsWith("../", index) -> index += 3
                path.startsWith("/./", index) -> index += 2 // preserve the last slash

                isExactly(path, index, "/.") -> {
                    path = "/"
                    index = 0
                }

                path.startsWith("/../", index) -> {
                    index += 3 // preserve the last slash
                    dropLastSegment(out)
                }

                isExactly(path, index, "/..") -> {
                    path = "/"
                    index = 0
                    dropLastSegment(out)
                }

                isExactly(path, index, ".") || isExactly(path, index, "..") -> return out.toString()

                else -> {
                    if (path[index] == '/') {
                        index++

                        if (out.isEmpty() || out.last() != '/') {
                            out.append('/')
                        }
                    }

                    val nextSlash = path.indexOf('/', index)

                    if (nextSlash == -1) {
                        out.append(path, index, path.length)

                        return out.toString()
                    }

                    out.append(path, index, nextSlash)
                    index = nextSlash
                }
            }
        }

        return out.toString()
    }

    private fun isExactly(path: String, index: Int, segment: String): Boolean =
        path.length - index == segment.length && path.startsWith(segment, index)

    private fun dropLastSegment(out: StringBuilder) {
        val position = out.lastIndexOf("/")

        if (position != -1) {
            out.delete(position, out.length)
        }
    }

    private fun trimTrailingSlash(path: String): String =
        if (path.length > 1 && path.endsWith("/")) path.trimEnd('/').ifEmpty { "/" } else path

    // --- bypass cookie ------------------------------------------------------

    fun hasSkipCookie(context: RoutingContext): Boolean = authProvider.hasMaintenanceSkipCookie(context)

    fun clearSkipCookie(context: RoutingContext) {
        authProvider.clearMaintenanceSkipCookie(context)
    }

    // --- page composition ---------------------------------------------------

    /**
     * Rewrites `maintenance/page.hbs` from the bundled default with the admin's content substituted
     * over the HTML comment sentinels. Plain string replacement, never Handlebars: the admin's
     * rich-text HTML must not be interpreted as a template.
     */
    fun composeAndSavePage() {
        // The full-page editor makes page.hbs the source of truth; composing over it would throw
        // the admin's markup away on the next settings save.
        if (settings().customPage) {
            return
        }

        val composed = composePage(settings()) ?: return

        writeAtomically(pageFile, composed, PAGE_FILE_PERMISSIONS)

        invalidatePageCache()
    }

    /** The markup currently on disk, composing it first if the file is not there yet. */
    fun currentPageHtml(): String {
        if (!pageFile.exists()) {
            val composed = composePage(settings()) ?: return ""

            writeAtomically(pageFile, composed, PAGE_FILE_PERMISSIONS)

            invalidatePageCache()

            return composed
        }

        return try {
            pageFile.readText()
        } catch (t: Throwable) {
            logger.error("Failed to read maintenance/$PAGE_FILE_NAME", t)

            ""
        }
    }

    /** Writes the full-page editor's markup verbatim. The caller sets `custom-page` in config. */
    fun savePageHtml(html: String) {
        writeAtomically(pageFile, html, PAGE_FILE_PERMISSIONS)

        invalidatePageCache()
    }

    /** Hands the page back to Pano: recomposed from the fields, and updated by future releases. */
    fun resetPage(): String {
        val composed = composePage(settings()) ?: return currentPageHtml()

        writeAtomically(pageFile, composed, PAGE_FILE_PERMISSIONS)

        invalidatePageCache()

        return composed
    }

    /** The composition itself, with no side effects — [renderPreview] runs it on unsaved input. */
    private fun composePage(maintenance: PanoConfig.Companion.MaintenanceConfig): String? {
        val template = readDefaultTemplate()

        if (template == null) {
            logger.error("Bundled maintenance page template ($DEFAULT_PAGE_RESOURCE) is missing; keeping the existing page")

            return null
        }

        // A field the admin left empty becomes a render-time slot rather than a baked default, so
        // the fallback text follows the platform locale and any panel translation override without
        // anyone having to re-save the card. It also means composing never needs the translation
        // bundle, which is not loaded before setup finishes.
        val title = if (maintenance.title.isBlank()) {
            DEFAULT_TITLE_SLOT
        } else {
            // escapeExpression leaves braces alone, and the composed file is compiled as a template
            // afterwards, so a title containing "{{" would still become a Handlebars directive.
            neutralize(escapeHtml(maintenance.title))
        }

        val message = if (maintenance.messageHtml.isBlank()) {
            DEFAULT_MESSAGE_SLOT
        } else {
            neutralize(maintenance.messageHtml)
        }

        // <style> is a RAWTEXT element: the tokenizer ends it on "</style" matched
        // case-insensitively, so patching one spelling ("</style" → "<\/style") left "</STYLE" to
        // break out. CSS never needs a literal "<", so escape every one of them instead and the
        // whole class of tokenizer-boundary bugs goes away. "\3c " is inert everywhere it can
        // legally appear — inside strings it still decodes to "<", elsewhere a bare "<" was
        // already a parse error. Hygiene, not a boundary: messageHtml below is spliced in raw for
        // the same permission holder, so nobody gains anything from either spelling.
        val css = neutralize(maintenance.customCss).replace("<", "\\3c ")

        var composed = template
            .replace("<!--PANO_TITLE-->", title)
            .replace("<!--PANO_MESSAGE-->", message)
            .replace("<!--PANO_CSS-->", css)

        if (!maintenance.showSiteLogo) {
            composed = LOGO_BLOCK_REGEX.replace(composed, "")
        }

        return composed
    }

    /**
     * The copy an empty title/message field falls back to, resolved in the platform language. The
     * panel editor seeds its blank fields with these so the admin can see and edit the real markup
     * instead of an empty document — and treats "still identical to this" as blank on save, which
     * is what keeps the fallback following the locale.
     */
    suspend fun defaultTitle(): String = translate("maintenance.default-title")

    suspend fun defaultMessageHtml(): String = translate("maintenance.default-message")

    /**
     * The page the given markup would produce, rendered but never written to disk. Used by the
     * full-page editor, where the admin's own markup *is* the template.
     */
    /**
     * The page the given drafts would produce, rendered but never written to disk.
     *
     * Every block is filled in at once — notice, login form, skip button, credit — because the
     * editor's job is to show the admin the whole interface they can restyle. A live request only
     * ever shows the subset that applies to it.
     */
    suspend fun renderPreviewOf(
        drafts: Map<PageTemplate, String>,
        showSiteLogo: Boolean,
        focus: PageTemplate
    ): String {
        val page = drafts[PageTemplate.PAGE] ?: templateSource(PageTemplate.PAGE)
        val source = if (showSiteLogo) page else LOGO_BLOCK_REGEX.replace(page, "")

        // Only the state that exercises the block being edited is simulated: the login form tab
        // previews the login page, the skip tab previews what a bypasser sees. Filling every slot
        // at once would show a page no visitor ever gets.
        val slots = baseSlots(
            noticeBlock = if (focus == PageTemplate.NOTICE) {
                // Any notice will do; the tab styles the banner, it does not pick its wording.
                renderNotice(Notice.THEME_SWITCHING, drafts)
            } else {
                ""
            },
            loginBlock = when (focus) {
                PageTemplate.LOGIN_FORM -> renderLoginFormBlock(null, drafts)
                PageTemplate.LOGIN_BUTTON -> renderLoginButtonBlock(DEFAULT_LOGIN_LOCATION, drafts)
                else -> ""
            },
            skipBlock = if (focus == PageTemplate.SKIP) renderSkipBlock(drafts) else ""
        )

        return try {
            handlebars.compileInline(source).apply(slots)
        } catch (t: Throwable) {
            // The admin's own markup is the template here, so a broken directive lands in the
            // preview rather than on the live site — which is exactly what the preview is for.
            logger.warn("Failed to render the maintenance page preview: {}", t.message)

            FALLBACK_HTML
        }
    }

    /**
     * True when the file on disk cannot serve the current settings and must be composed again:
     * it was built from an older default template, both content slots are empty (a compose that ran
     * before the translations loaded), or a field the admin left blank was baked in as a fixed
     * string instead of the render-time slot, so it would never follow a locale change.
     *
     * The template-version check is what makes a redesign that ships with an update actually reach
     * existing installations. It also discards hand edits to [PAGE_FILE_NAME] — the same trade the
     * panel's save button already makes, and the docs say to keep a copy.
     */
    private fun needsRecompose(): Boolean = try {
        val text = pageFile.readText()
        val maintenance = settings()

        // A page written by the full-page editor is never regenerated — not for a new default
        // design either. That is the whole point of the mode, and it is why the panel offers
        // "Reset to default" as the way back.
        !maintenance.customPage &&
                (!text.contains(TEMPLATE_VERSION_MARKER) ||
                        (text.contains("<title></title>") &&
                                text.contains("<div class=\"pano-message\"></div>")) ||
                        (maintenance.title.isBlank() && !text.contains(DEFAULT_TITLE_SLOT)) ||
                        (maintenance.messageHtml.isBlank() && !text.contains(DEFAULT_MESSAGE_SLOT)))
    } catch (t: Throwable) {
        logger.warn("Failed to inspect maintenance/$PAGE_FILE_NAME: {}", t.message)

        false
    }

    fun invalidatePageCache() {
        compiledPage = null
        compiledPartials.clear()
        translationCache.clear()
    }

    // --- editable block templates -------------------------------------------

    /**
     * The blocks Pano splices into the page. Each one is a file in the maintenance folder so the
     * panel can offer it as its own tab: the login form and the skip button are markup an admin
     * wants to restyle just as much as the page around them.
     */
    enum class PageTemplate(val fileName: String, val resource: String) {
        PAGE(PAGE_FILE_NAME, DEFAULT_PAGE_RESOURCE),
        LOGIN_FORM("login.hbs", "maintenance/login-form-template.hbs"),
        LOGIN_BUTTON("login-button.hbs", "maintenance/login-button-template.hbs"),
        SKIP("skip.hbs", "maintenance/skip-template.hbs"),
        NOTICE("notice.hbs", "maintenance/notice-template.hbs")
    }

    private fun templateFile(template: PageTemplate) = File(folder, template.fileName)

    private fun readBundled(resource: String): String? = try {
        javaClass.classLoader.getResourceAsStream(resource)
            ?.bufferedReader()
            ?.use { it.readText() }
    } catch (t: Throwable) {
        logger.error("Failed to read the bundled maintenance template $resource", t)

        null
    }

    /** The block's markup on disk, written from the bundled default the first time it is needed. */
    fun templateSource(template: PageTemplate): String {
        if (template == PageTemplate.PAGE) {
            return currentPageHtml()
        }

        val file = templateFile(template)

        if (!file.exists()) {
            val bundled = readBundled(template.resource) ?: return ""

            writeAtomically(file, bundled, PAGE_FILE_PERMISSIONS)

            return bundled
        }

        return try {
            file.readText()
        } catch (t: Throwable) {
            logger.error("Failed to read maintenance/${template.fileName}", t)

            readBundled(template.resource).orEmpty()
        }
    }

    fun saveTemplateSource(template: PageTemplate, html: String) {
        if (template == PageTemplate.PAGE) {
            savePageHtml(html)

            return
        }

        writeAtomically(templateFile(template), html, PAGE_FILE_PERMISSIONS)

        invalidatePageCache()
    }

    /** Restores every block, including the page, to the design Pano ships. */
    fun resetTemplates(): Map<PageTemplate, String> {
        PageTemplate.entries.filter { it != PageTemplate.PAGE }.forEach { template ->
            readBundled(template.resource)?.let { writeAtomically(templateFile(template), it, PAGE_FILE_PERMISSIONS) }
        }

        val page = resetPage()

        return PageTemplate.entries.associateWith { if (it == PageTemplate.PAGE) page else templateSource(it) }
    }

    private fun renderTemplate(
        template: PageTemplate,
        variables: Map<String, Any?>,
        drafts: Map<PageTemplate, String>?
    ): String {
        val draft = drafts?.get(template)

        return try {
            if (draft != null) {
                handlebars.compileInline(draft).apply(variables)
            } else {
                resolveCompiledPartial(template).apply(variables)
            }
        } catch (t: Throwable) {
            // A broken block must not take the whole page down with it — the page still renders,
            // just without this piece, and the preview is where the admin sees that.
            logger.warn("Failed to render maintenance/{}: {}", template.fileName, t.message)

            ""
        }
    }

    private fun resolveCompiledPartial(template: PageTemplate): Template {
        val file = templateFile(template)
        val source = templateSource(template)
        val stamp = file.lastModified() to file.length()

        compiledPartials[template]?.let {
            if (it.lastModified == stamp.first && it.length == stamp.second) {
                return it.template
            }
        }

        val compiled = handlebars.compileInline(source)

        compiledPartials[template] = CompiledPage(compiled, stamp.first, stamp.second)

        return compiled
    }

    /**
     * Neutralises the two things admin-supplied HTML could do to the *next* stage: become a
     * Handlebars directive, or fake one of the compose sentinels.
     */
    private fun neutralize(html: String): String = html
        .replace("{{", "&#123;&#123;")
        .replace("<!--PANO_", "<!-- PANO_")

    private fun readDefaultTemplate(): String? = try {
        javaClass.classLoader.getResourceAsStream(DEFAULT_PAGE_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
    } catch (t: Throwable) {
        logger.error("Failed to read the bundled maintenance page template", t)

        null
    }

    // --- page rendering -----------------------------------------------------

    private fun resolveCompiledPage(): Template {
        if (!pageFile.exists()) {
            composeAndSavePage()
        }

        val lastModified = pageFile.lastModified()
        val length = pageFile.length()

        compiledPage?.let {
            if (it.lastModified == lastModified && it.length == length) {
                return it.template
            }
        }

        val template = handlebars.compileInline(pageFile.readText())

        compiledPage = CompiledPage(template, lastModified, length)

        return template
    }

    suspend fun renderMaintenancePage(
        context: RoutingContext,
        access: Access,
        notice: Notice?
    ): String {
        val banned = isBanned(resolveBanIdentity(context))

        val loginBlock = if (access.isLoggedIn || banned) "" else renderLoginButtonBlock()
        val skipBlock = if (access.canBypass) renderSkipBlock() else ""

        return render(notice, loginBlock, skipBlock)
    }

    suspend fun renderLoginPage(context: RoutingContext, notice: Notice?): String {
        val banned = isBanned(resolveBanIdentity(context))

        // A banned identity gets the page and the reason, never the form.
        val loginBlock = if (banned) "" else renderLoginFormBlock(context)

        return render(notice ?: (if (banned) Notice.IP_BANNED else null), loginBlock, "")
    }

    private suspend fun baseSlots(
        noticeBlock: String,
        loginBlock: String,
        skipBlock: String
    ): Map<String, Any?> = mapOf(
        "lang" to localeCode(),
        "logoUrl" to logoUrl(),
        // Only referenced by pages composed from an empty title/message field; resolving them
        // here is what keeps the fallback copy in the platform language.
        "defaultTitle" to translate("maintenance.default-title"),
        "defaultMessage" to translate("maintenance.default-message"),
        "noticeBlock" to noticeBlock,
        "loginBlock" to loginBlock,
        "skipBlock" to skipBlock,
        "creditBlock" to renderCreditBlock()
    )

    private suspend fun render(notice: Notice?, loginBlock: String, skipBlock: String): String {
        val slots = baseSlots(
            noticeBlock = notice?.let { renderNotice(it) } ?: "",
            loginBlock = loginBlock,
            skipBlock = skipBlock
        )

        return try {
            resolveCompiledPage().apply(slots)
        } catch (t: Throwable) {
            logger.error("maintenance/$PAGE_FILE_NAME is unusable; regenerating from the bundled default", t)

            try {
                composeAndSavePage()

                resolveCompiledPage().apply(slots)
            } catch (t2: Throwable) {
                logger.error("Failed to regenerate the maintenance page", t2)

                FALLBACK_HTML
            }
        }
    }

    /** The same credit the themes carry in their footer, worded from the same kind of string. */
    private suspend fun renderCreditBlock(): String {
        val text = translate("maintenance.created-with")

        if (text.isBlank()) {
            return ""
        }

        val url = configManager.config.panoWebsiteUrl.trim().ifBlank { PanoConfig.PANO_WEBSITE_URL_PRODUCTION }
        val link = "<a href=\"${escapeHtml(url)}\" target=\"_blank\" rel=\"noreferrer noopener\">Pano</a>"

        return escapeHtml(text).replace(PANO_PLACEHOLDER, link)
    }

    /** Skips `GetWebsiteLogoAPI`'s redirect-to-canonical-hash round trip. */
    private fun logoUrl(): String {
        val logo = configManager.config.filePaths.websiteLogoFile

        return if (logo == null) "/api/websiteLogo" else "/api/websiteLogo?hash=${logo.hash}"
    }

    private suspend fun renderNotice(notice: Notice, drafts: Map<PageTemplate, String>? = null): String {
        val text = escapeHtml(translate(notice.translationKey))

        if (text.isBlank()) {
            return ""
        }

        val (modifier, role) = when (notice.kind) {
            Notice.Kind.INFO -> " pano-notice--info" to "status"
            Notice.Kind.DANGER -> "" to "alert"
        }

        return renderTemplate(
            PageTemplate.NOTICE,
            mapOf("text" to text, "modifier" to modifier, "role" to role),
            drafts
        )
    }

    private suspend fun renderLoginButtonBlock(
        forcedUrl: String? = null,
        drafts: Map<PageTemplate, String>? = null
    ): String {
        val url = forcedUrl ?: advertisedLoginUrl() ?: return ""

        return renderTemplate(
            PageTemplate.LOGIN_BUTTON,
            mapOf("url" to escapeHtml(url), "label" to escapeHtml(translate("maintenance.button.login"))),
            drafts
        )
    }

    private suspend fun renderLoginFormBlock(
        context: RoutingContext?,
        drafts: Map<PageTemplate, String>? = null
    ): String = renderTemplate(
        PageTemplate.LOGIN_FORM,
        mapOf(
            "action" to LOGIN_PATH,
            // No context means a preview: there is nothing to bind a nonce to, and issuing one
            // would set a cookie on the panel's own request.
            "nonce" to (context?.let { escapeHtml(issueLoginNonce(it)) } ?: ""),
            "nonceField" to LOGIN_NONCE_FIELD,
            "usernameField" to LOGIN_USERNAME_FIELD,
            "passwordField" to LOGIN_PASSWORD_FIELD,
            "heading" to escapeHtml(translate("maintenance.login.heading")),
            "usernameLabel" to escapeHtml(translate("maintenance.login.username-or-email-label")),
            "usernamePlaceholder" to escapeHtml(translate("maintenance.login.username-or-email-placeholder")),
            "passwordLabel" to escapeHtml(translate("maintenance.login.password-label")),
            "passwordPlaceholder" to escapeHtml(translate("maintenance.login.password-placeholder")),
            "submit" to escapeHtml(translate("maintenance.login.submit-button"))
        ),
        drafts
    )

    private suspend fun renderSkipBlock(drafts: Map<PageTemplate, String>? = null): String = renderTemplate(
        PageTemplate.SKIP,
        mapOf(
            "action" to SKIP_PATH,
            "label" to escapeHtml(translate("maintenance.button.skip")),
            "description" to escapeHtml(translate("maintenance.skip-description"))
        ),
        drafts
    )

    // --- translations -------------------------------------------------------

    private fun localeCode(): String = try {
        configManager.config.locale.ifBlank { AppConstants.DEFAULT_LOCALE_CODE }
    } catch (_: Throwable) {
        AppConstants.DEFAULT_LOCALE_CODE
    }

    /** Render-time lookup, honours database overrides. Cached; cleared with the page cache. */
    private suspend fun translate(key: String): String {
        val locale = localeCode()
        val cacheKey = "$locale|$key"

        translationCache[cacheKey]?.let { return it }

        val value = try {
            i18nManager.getTranslation(TranslationType.PLATFORM, locale, key)
        } catch (t: Throwable) {
            logger.warn("Failed to resolve maintenance translation '{}': {}", key, t.message)

            null
        } ?: originalTranslation(key)

        if (translationCache.size > MAX_TRANSLATION_CACHE_ENTRIES) {
            translationCache.clear()
        }

        translationCache[cacheKey] = value

        return value
    }

    /** Compose-time lookup. Non-suspend, so it deliberately skips database overrides. */
    private fun originalTranslation(key: String): String = originalTranslationOrNull(key) ?: ""

    /** Null means the bundle has not been loaded yet (pre-setup), not "missing key". */
    private fun originalTranslationOrNull(key: String): String? =
        i18nManager.getOriginalTranslation(TranslationType.PLATFORM, localeCode(), key)

    private fun escapeHtml(value: String): String = Handlebars.Utils.escapeExpression(value).toString()

    // --- CSRF / same-origin on the login POST -------------------------------

    /**
     * Issues the double-submit nonce: one short-lived cookie plus the hidden form input rendered
     * with the returned value. Without it any web page could make a visitor's browser burn through
     * the failure budget and get that visitor's IP permanently banned.
     */
    fun issueLoginNonce(context: RoutingContext): String {
        val bytes = ByteArray(24)

        secureRandom.nextBytes(bytes)

        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val isSecure = connectionIsSecure(context.request())

        val cookie = Cookie.cookie(nonceCookieName(isSecure), nonce)

        cookie.maxAge = LOGIN_NONCE_TTL_SECONDS
        cookie.path = "/"
        cookie.isSecure = isSecure
        cookie.isHttpOnly = true
        cookie.sameSite = CookieSameSite.LAX

        context.response().addCookie(cookie)

        return nonce
    }

    /**
     * Same-origin check plus the double-submit nonce. A request that fails this must be answered
     * with 403 and must NOT be counted as a login failure.
     */
    fun verifyLoginRequest(context: RoutingContext): Boolean {
        val request = context.request()

        if (!isSameOriginRequest(request)) {
            return false
        }

        val cookies = parseCookieHeader(request.getHeader("cookie"))
        val expected = cookies[nonceCookieName(true)] ?: cookies[nonceCookieName(false)] ?: return false
        val submitted = request.getFormAttribute(LOGIN_NONCE_FIELD) ?: request.getParam(LOGIN_NONCE_FIELD) ?: return false

        if (expected.isBlank() || submitted.isBlank()) {
            return false
        }

        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), submitted.toByteArray(Charsets.UTF_8))
    }

    private fun isSameOriginRequest(request: HttpServerRequest): Boolean {
        val secFetchSite = request.getHeader("Sec-Fetch-Site")?.trim()?.lowercase()

        if (secFetchSite != null) {
            return secFetchSite == "same-origin"
        }

        // Pre-Fetch-Metadata clients: fall back to Origin, then Referer.
        val claimed = hostnameOf(request.getHeader("Origin"))
            ?: hostnameOf(request.getHeader("Referer"))
            ?: return false

        val requestHost = request.authority()?.host()?.lowercase()
        val configuredHost = hostnameOf(configManager.config.websiteUrl)

        return claimed == requestHost || (configuredHost != null && claimed == configuredHost)
    }

    private fun nonceCookieName(secureVariant: Boolean): String {
        val suffix = if (secureVariant) "" else INSECURE_COOKIE_SUFFIX

        return AppConstants.COOKIE_PREFIX + NONCE_COOKIE_NAME + suffix
    }

    /** Mirrors [AuthProvider]'s inference: TLS is usually terminated before Vert.x. */
    private fun connectionIsSecure(request: HttpServerRequest): Boolean {
        if (request.isSSL) {
            return true
        }

        val forwardedProto = request.getHeader("X-Forwarded-Proto")
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return forwardedProto.contains("https")
    }

    private fun parseCookieHeader(header: String?): Map<String, String> {
        if (header.isNullOrBlank()) {
            return emptyMap()
        }

        val cookies = mutableMapOf<String, String>()

        try {
            header.split(";").forEach { pair ->
                val parts = pair.trim().split("=", limit = 2)

                if (parts.size == 2) {
                    cookies[parts[0]] = parts[1]
                }
            }
        } catch (_: Exception) {
        }

        return cookies
    }

    private fun hostnameOf(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }

        var host = value.trim()
        val schemeIndex = host.indexOf("://")

        if (schemeIndex >= 0) {
            host = host.substring(schemeIndex + 3)
        }

        host = host.substringBefore('/').substringAfterLast('@')

        // Bare form, so it compares equal to HostAndPort.host() for IPv6 literals too.
        if (host.startsWith("[")) {
            val end = host.indexOf(']')

            if (end > 0) {
                return host.substring(1, end).lowercase()
            }
        }

        return host.substringBefore(':').lowercase().ifBlank { null }
    }

    // --- ban identity -------------------------------------------------------

    /**
     * The socket peer always wins, because it is the only thing a client cannot forge — see
     * [TrustedProxyIpResolver], which `RateLimitManager` shares so the ban store and the
     * maintenance rate limit can never disagree about who a request came from.
     *
     * Returns null when the identity must never be counted or banned.
     */
    fun resolveBanIdentity(context: RoutingContext): BanIdentity? {
        val resolved = TrustedProxyIpResolver.resolve(context.request(), trustedProxies()) ?: return null

        // The identity is the socket peer itself here, so the loopback exemption applies: an admin
        // with shell access can always reach the maintenance login. Never applied to a header value.
        if (!resolved.fromForwardedHeader && WebsiteUrlRedirectHandler.isLoopbackIp(resolved.ip)) {
            return null
        }

        return BanIdentity(resolved.ip, resolved.socketPeer)
    }

    private fun trustedProxies(): List<String> = configManager.config.server.trustedProxies

    // --- ban store ----------------------------------------------------------

    fun isBanned(identity: BanIdentity?): Boolean = identity != null && bans.containsKey(identity.ip)

    fun bannedIps(): List<BanEntry> = bans.values.sortedByDescending { it.bannedAt }

    fun bannedIpCount(): Int = bans.size

    /** Returns true when this failure crossed the threshold and the identity is now banned. */
    suspend fun registerLoginFailure(context: RoutingContext, usernameTried: String): Boolean {
        val max = settings().maxLoginAttempts

        if (max <= 0) {
            return false
        }

        val identity = resolveBanIdentity(context) ?: return false

        if (bans.containsKey(identity.ip)) {
            return true
        }

        if (failCounters.size > MAX_BAN_ENTRIES) {
            logger.warn("Maintenance failure counters overflowed ({} entries); resetting", failCounters.size)

            failCounters.clear()
        }

        val counter = failCounters.compute(identity.ip) { _, existing ->
            if (existing == null) {
                FailCounter(1, System.currentTimeMillis())
            } else {
                existing.also {
                    it.count += 1
                    it.lastAt = System.currentTimeMillis()
                }
            }
        }!!

        // "More than max" — with the default of 3 the fourth failure is the one that bans.
        if (counter.count <= max) {
            return false
        }

        if (bans.size >= MAX_BAN_ENTRIES) {
            logger.warn("Maintenance ban list is full ({} entries); refusing to ban {}", bans.size, identity.ip)

            return false
        }

        val entry = BanEntry(
            ip = identity.ip,
            socketPeer = identity.socketPeer,
            bannedAt = System.currentTimeMillis(),
            attempts = counter.count,
            lastUsernameTried = usernameTried.take(MAX_USERNAME_LENGTH).ifBlank { null },
            lastUserAgent = context.request().getHeader("User-Agent")?.take(MAX_USER_AGENT_LENGTH)
        )

        bans[entry.ip] = entry
        failCounters.remove(entry.ip)

        flushBans()
        writeBanActivityLog(entry)

        return true
    }

    fun registerLoginSuccess(context: RoutingContext) {
        val identity = resolveBanIdentity(context) ?: return

        failCounters.remove(identity.ip)
    }

    suspend fun removeBans(
        ips: Collection<String>,
        actingUserId: Long? = null,
        actingUsername: String? = null
    ): Int {
        val removed = ips.mapNotNull { bans.remove(it)?.ip }

        if (removed.isEmpty()) {
            return 0
        }

        removed.forEach { failCounters.remove(it) }

        flushBans()
        writeUnbanActivityLog(removed, actingUserId, actingUsername)

        return removed.size
    }

    suspend fun removeAllBans(
        actingUserId: Long? = null,
        actingUsername: String? = null
    ): Int {
        val removed = bans.keys.toList()

        if (removed.isEmpty()) {
            return 0
        }

        bans.clear()
        failCounters.clear()

        flushBans()
        writeUnbanActivityLog(removed, actingUserId, actingUsername)

        return removed.size
    }

    private suspend fun flushBans() {
        bansMutex.withLock {
            val snapshot = JsonObject()
                .put("version", BANS_FILE_VERSION)
                .put("bans", JsonArray(bans.values.map { it.toJson() }))
                .encode()

            vertx.executeBlocking<Unit> { writeAtomically(bansFile, snapshot, BANS_FILE_PERMISSIONS) }.coAwait()
        }
    }

    /**
     * Fail-open: a corrupt ban file is backed up and ignored rather than blocking every login. The
     * alternative turns a disk hiccup into a total lockout.
     */
    private fun loadBansFromDisk() {
        if (!bansFile.exists()) {
            return
        }

        try {
            val root = JsonObject(bansFile.readText())
            val array = root.getJsonArray("bans") ?: JsonArray()

            bans.clear()

            array.forEach { raw ->
                val entry = raw as? JsonObject ?: return@forEach
                val ip = entry.getString("ip") ?: return@forEach

                bans[ip] = BanEntry(
                    ip = ip,
                    socketPeer = entry.getString("socketPeer") ?: ip,
                    bannedAt = entry.getLong("bannedAt", 0L),
                    attempts = entry.getInteger("attempts", 0),
                    lastUsernameTried = entry.getString("lastUsernameTried"),
                    lastUserAgent = entry.getString("lastUserAgent")
                )
            }
        } catch (t: Throwable) {
            logger.error("$BANS_FILE_NAME is corrupt; backing it up and starting empty", t)

            try {
                bansFile.copyTo(File(folder, "$BANS_FILE_NAME.corrupt-${System.currentTimeMillis()}"), overwrite = true)
            } catch (_: Throwable) {
            }

            bans.clear()
        }
    }

    private suspend fun writeBanActivityLog(entry: BanEntry) {
        try {
            databaseManager.panelActivityLogDao.add(
                MaintenanceModeBannedIpLog(
                    entry.ip,
                    entry.socketPeer,
                    entry.attempts,
                    entry.lastUsernameTried,
                    entry.lastUserAgent
                ),
                databaseManager.getSqlClient()
            )
        } catch (t: Throwable) {
            logger.warn("Failed to write the maintenance ban activity log for {}: {}", entry.ip, t.message)
        }
    }

    private suspend fun writeUnbanActivityLog(ips: List<String>, actingUserId: Long?, actingUsername: String?) {
        if (actingUserId == null) {
            return
        }

        try {
            databaseManager.panelActivityLogDao.add(
                MaintenanceModeUnbannedIpLog(actingUserId, actingUsername.orEmpty(), ips),
                databaseManager.getSqlClient()
            )
        } catch (t: Throwable) {
            logger.warn("Failed to write the maintenance unban activity log: {}", t.message)
        }
    }

    // --- disk ---------------------------------------------------------------

    /** tmp file → atomic move, with the non-atomic fallback and best-effort POSIX permissions. */
    private fun writeAtomically(target: File, content: String, posixPermissions: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")

        try {
            target.parentFile?.mkdirs()

            tmp.writeText(content, Charsets.UTF_8)

            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            try {
                Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString(posixPermissions))
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (Windows); skip.
            } catch (_: Throwable) {
                // Best-effort.
            }
        } catch (t: Throwable) {
            logger.warn("Failed to write maintenance file '{}': {}", target.name, t.message)

            try {
                if (tmp.exists()) {
                    tmp.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }
}
