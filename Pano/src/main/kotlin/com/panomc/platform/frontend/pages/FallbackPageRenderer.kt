package com.panomc.platform.frontend.pages

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.Helper
import com.github.jknack.handlebars.Template
import com.panomc.platform.frontend.FallbackPageEnvironment
import com.panomc.platform.frontend.RegisteredFallbackPage
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a [RegisteredFallbackPage] into HTML: the page's template, read with the class loader that owns it and
 * compiled with Handlebars, placed in the core shell `fallback/shell.hbs` (doc 05 section 10.3; the pattern of
 * `maintenance/page-template.hbs`). The texts come from `fallback/texts/<locale>.json`, English filling any gap.
 *
 * The page is plain: no inline script or style (the route sends a strict Content-Security-Policy), the one
 * script `fallback.js` reads its configuration from a JSON block.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class FallbackPageRenderer(
    private val environment: FallbackPageEnvironment,
    private val logger: Logger
) {
    private val handlebars = Handlebars().apply {
        registerHelper("pageTitle", Helper<Any?> { text, options ->
            val box = options.context.get(TITLE_BOX_KEY) as? StringBuilder

            if (box != null && box.isEmpty() && text != null) {
                box.append(text.toString())
            }

            ""
        })
    }

    private val compiled = ConcurrentHashMap<String, Template>()
    private val texts = ConcurrentHashMap<String, Map<String, Any?>>()

    private val shell: Template by lazy {
        val source = javaClass.classLoader.getResourceAsStream(SHELL_RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("The fallback page shell $SHELL_RESOURCE is missing from the jar.")

        handlebars.compileInline(source)
    }

    /** Forgets what was compiled for a plugin's pages (the class loader of an unloaded plugin must not stay referenced). */
    fun forget(owner: String) {
        compiled.keys.removeIf { it.startsWith("$owner|") }
    }

    suspend fun render(registered: RegisteredFallbackPage, context: RoutingContext): String {
        val locale = environment.locale()
        val strings = texts(locale)
        val siteName = environment.siteName()

        val model = registered.page.model(context)
        val titleBox = StringBuilder()

        val data = LinkedHashMap<String, Any?>(model)

        data["t"] = strings
        data["lang"] = languageTag(locale)
        data["siteName"] = siteName
        data[TITLE_BOX_KEY] = titleBox

        val content = template(registered).apply(data)

        val pageTitle = (model["title"] as? String)?.takeIf { it.isNotBlank() }
            ?: titleBox.toString().takeIf { it.isNotBlank() }

        val title = when {
            pageTitle == null -> siteName
            siteName.isBlank() -> pageTitle
            else -> "$pageTitle - $siteName"
        }

        val config = JsonObject()
            .put("api", ApiPaths.ROOT)
            .put("target", registered.id)
            .apply { if (registered.owner != com.panomc.platform.frontend.CoreFrontendTargets.OWNER) put("pluginApi", ApiPaths.plugin(registered.owner, "")) }
            .put("home", environment.homeUrl())
            .put("t", JsonObject(strings))

        @Suppress("UNCHECKED_CAST")
        val clientConfig = model["clientConfig"] as? Map<String, Any?>

        // A page may also word its codes in `clientConfig.errors` (`config.page.errors`); `fallback.js` reads
        // `config.errors`, so those texts are merged in, and `FallbackPage.errors()` wins on a clash.
        val clientErrors = (clientConfig?.get("errors") as? Map<*, *>)
            ?.entries
            ?.filter { (it.key as? String)?.isNotBlank() == true && it.value is String }
            ?.associate { it.key as String to it.value }
            .orEmpty()

        (clientErrors + pageErrors(registered, locale)).takeIf { it.isNotEmpty() }
            ?.let { config.put("errors", JsonObject(it)) }

        clientConfig?.let { config.put("page", JsonObject(it)) }

        return shell.apply(
            mapOf(
                "lang" to languageTag(locale),
                "title" to title,
                "siteName" to siteName,
                "logoUrl" to environment.logoUrl(),
                "faviconUrl" to environment.faviconUrl(),
                "content" to Handlebars.SafeString(content),
                "configJson" to Handlebars.SafeString(inScript(config.encode())),
                "scriptUrl" to FALLBACK_JS_PATH,
                "styleUrl" to FALLBACK_CSS_PATH
            )
        )
    }

    /** A page's own error texts; a page whose text source fails must not take the page down. */
    private fun pageErrors(registered: RegisteredFallbackPage, locale: String): Map<String, Any?> = try {
        registered.page.errors(locale).filter { it.key.isNotBlank() }
    } catch (e: Exception) {
        logger.warn("The error texts of the fallback page '{}' cannot be read: {}", registered.id, e.message)

        emptyMap()
    }

    private fun template(registered: RegisteredFallbackPage): Template =
        compiled.computeIfAbsent("${registered.owner}|${registered.page.template}") {
            val source = registered.classLoader.getOwnResourceStream(registered.page.template)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("The template ${registered.page.template} of the fallback page '${registered.id}' is missing.")

            handlebars.compileInline(source)
        }

    /** The texts of [locale]: the English bundle with the bundle of the language laid over it. */
    internal fun texts(locale: String): Map<String, Any?> = texts.computeIfAbsent(locale) {
        val english = readTexts(DEFAULT_LOCALE) ?: emptyMap()

        val own = if (locale == DEFAULT_LOCALE) {
            null
        } else {
            readTexts(locale) ?: readTexts(locale.substringBefore('-'))
        }

        if (own == null) english else merge(english, own)
    }

    private fun readTexts(locale: String): Map<String, Any?>? {
        if (!LOCALE_PATTERN.matches(locale)) {
            return null
        }

        return try {
            javaClass.classLoader.getResourceAsStream("fallback/texts/$locale.json")
                ?.use { JsonObject(it.readBytes().toString(Charsets.UTF_8)).map }
        } catch (e: Exception) {
            logger.warn("Fallback page texts for '{}' cannot be read: {}", locale, e.message)

            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun merge(base: Map<String, Any?>, over: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap(base)

        over.forEach { (key, value) ->
            val current = out[key]

            out[key] = if (current is Map<*, *> && value is Map<*, *>) {
                merge(current as Map<String, Any?>, value as Map<String, Any?>)
            } else {
                value
            }
        }

        return out
    }

    companion object {
        const val SHELL_RESOURCE = "fallback/shell.hbs"
        const val FALLBACK_JS_PATH = "/_pano/assets/fallback.js"
        const val FALLBACK_CSS_PATH = "/_pano/assets/fallback.css"
        const val DEFAULT_LOCALE = "en-US"

        private const val TITLE_BOX_KEY = "pano_fallback_title_box"
        private val LOCALE_PATTERN = Regex("""[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*""")

        /** `en-US` to `en-US`; anything that is not a language tag becomes `en`. */
        internal fun languageTag(locale: String) = if (LOCALE_PATTERN.matches(locale)) locale else "en"

        /** JSON that can sit inside `<script type="application/json">` without ending it or being read as markup. */
        internal fun inScript(json: String) = json
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
    }
}
