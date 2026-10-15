package com.panomc.platform.ui

/**
 * Who serves the website's pages at `/` (doc 05 §8). The value is stored as text in
 * `frontend.mode` of config.conf, so a hand edit with another spelling still works.
 */
enum class FrontendMode {
    /** A theme run by Pano (`current-theme`). The default; what every install did before modes existed. */
    THEME,

    /** An uploaded app (`custom-apps/<id>/index.js`), run by Pano as it runs a theme. */
    CUSTOM_APP,

    /** Pano proxies the pages to `frontend.upstream-url`; nothing is started. */
    EXTERNAL,

    /** No pages at all: `/` goes to the panel (or `site-url`), everything else is a 404. */
    NONE;

    companion object {
        /** Reads a config or request value leniently (`custom-app`, ` external `, `None`); anything else is [THEME]. */
        fun parse(raw: String?): FrontendMode = parseOrNull(raw) ?: THEME

        /** Like [parse] but null for a blank or unknown value, so an API can refuse it. */
        fun parseOrNull(raw: String?): FrontendMode? {
            val normalized = raw?.trim()?.uppercase()?.replace('-', '_') ?: return null

            return entries.firstOrNull { it.name == normalized }
        }
    }
}
