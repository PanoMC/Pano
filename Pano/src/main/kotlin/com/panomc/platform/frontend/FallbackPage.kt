package com.panomc.platform.frontend

import io.vertx.ext.web.RoutingContext

/**
 * A plain built-in page at `/_pano/<target id>` for a flow that has a link in a mail, a redirect or a payment
 * return (doc 05 section 10.3). It exists until a front-end takes the target over through the URL map.
 *
 * A core page gives its full target id (`auth.activate`). A plugin page gives its target *without* the
 * namespace (`order`), and the [FallbackPageRegistry] prefixes the plugin's namespace (`market.order`).
 *
 * @property template a Handlebars resource of the class that owns the page (`fallback/order.hbs`); it is
 *   placed in the core shell `fallback/shell.hbs`. The page can use `t` (the texts of the site language),
 *   `lang`, `siteName`, every key of [model] and the helper `{{pageTitle "..."}}`.
 */
abstract class FallbackPage(val target: String, val template: String) {
    /**
     * The values for [template]. Reserved keys (`t`, `lang`, `siteName`) are set by the renderer. Two keys
     * have a meaning: `title` (the page title; otherwise the `pageTitle` helper or the site name) and
     * `clientConfig` (a map handed to `fallback.js` as `config.page`).
     */
    open suspend fun model(context: RoutingContext): Map<String, Any?> = emptyMap()

    /**
     * The page's own error texts, error code to text, for the site [locale] (`en-US`, `tr`, `ru`) from the page's own
     * text source (a plugin keeps them in its resources). The renderer hands them to `fallback.js` as `config.errors`,
     * which looks a code up there before the core table, so a plugin can word the codes of its own endpoints.
     */
    open fun errors(locale: String): Map<String, String> = emptyMap()
}

/** What the fallback pages need to know about the running platform. A separate interface so the route can be tested. */
interface FallbackPageEnvironment {
    /** False before setup is done: there is no site yet, so there are no pages. */
    fun ready(): Boolean

    /** The check of `Api.checkMaintenance`: maintenance mode is on and this visitor is not let through. */
    suspend fun blockedByMaintenance(context: RoutingContext): Boolean

    fun siteName(): String

    /** The site language (`en-US`, `tr`, `ru`). */
    fun locale(): String

    fun logoUrl(): String

    fun faviconUrl(): String

    /** Where a visitor goes when a flow is finished: the front-end's site, else `/`. */
    fun homeUrl(): String
}
