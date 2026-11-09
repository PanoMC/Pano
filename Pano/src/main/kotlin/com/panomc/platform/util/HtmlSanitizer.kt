package com.panomc.platform.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Cleans rich text written in the panel editor (TipTap) before it is stored or handed to a page that renders it
 * as HTML (posts, staff ticket replies, ticket templates).
 *
 * Keeps what the editor produces (paragraphs, headings, lists, quotes, code, links, images and the text style
 * span) and drops everything else: scripts, event handler attributes, iframes, forms, `javascript:` / `data:` URLs
 * and style declarations other than the text style ones.
 */
object HtmlSanitizer {
    private const val BASE_URI = "https://pano.invalid/"

    private val allowedStyleProperties = setOf(
        "color",
        "background-color",
        "font-family",
        "font-size",
        "line-height",
        "text-align"
    )

    private val safelist: Safelist = Safelist()
        .addTags(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "strong", "b", "em", "i", "u", "s", "strike", "del", "sub", "sup", "mark", "small",
            "blockquote", "pre", "code", "ul", "ol", "li", "span", "a", "img", "div"
        )
        .addAttributes(":all", "style")
        .addAttributes("a", "href", "target", "rel", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addAttributes("ol", "start", "type")
        .addAttributes("code", "class")
        .addProtocols("a", "href", "http", "https", "mailto", "#")
        .addProtocols("img", "src", "http", "https")
        .preserveRelativeLinks(true)

    private val outputSettings = Document.OutputSettings().prettyPrint(false)

    /** [html] with only the allowed markup left. Null and empty text come back unchanged. */
    fun sanitize(html: String?): String {
        if (html.isNullOrEmpty()) {
            return html ?: ""
        }

        val dirty = Jsoup.parseBodyFragment(html, BASE_URI)
        val clean = Cleaner(safelist).clean(dirty)

        clean.outputSettings(outputSettings)

        clean.body().select("[style]").forEach { filterStyle(it) }
        clean.body().select("code[class]").forEach { element ->
            val languages = element.classNames().filter { it.matches(LANGUAGE_CLASS) }

            if (languages.isEmpty()) element.removeAttr("class") else element.attr("class", languages.joinToString(" "))
        }
        clean.body().select("a[target]").forEach { element ->
            if (element.attr("target") != "_blank") {
                element.removeAttr("target")
            } else {
                element.attr("rel", "noopener noreferrer nofollow")
            }
        }

        return clean.body().html()
    }

    private fun filterStyle(element: Element) {
        val kept = element.attr("style")
            .split(';')
            .mapNotNull { declaration ->
                val colon = declaration.indexOf(':')

                if (colon <= 0) return@mapNotNull null

                val property = declaration.substring(0, colon).trim().lowercase()
                val value = declaration.substring(colon + 1).trim()

                if (property !in allowedStyleProperties || value.isEmpty() || !value.matches(SAFE_STYLE_VALUE)) {
                    return@mapNotNull null
                }

                "$property: $value"
            }

        if (kept.isEmpty()) element.removeAttr("style") else element.attr("style", kept.joinToString("; "))
    }

    // Colours, sizes and font names: letters, digits, spaces and a few separators. No `(` keeps url(), expression()
    // and var() out, rgb() colours are allowed through their own pattern.
    private val SAFE_STYLE_VALUE =
        Regex("^(?:[#%.,'\"\\-\\w ]+|rgba?\\(\\s*[\\d.%]+\\s*,\\s*[\\d.%]+\\s*,\\s*[\\d.%]+\\s*(?:,\\s*[\\d.]+\\s*)?\\))$")

    private val LANGUAGE_CLASS = Regex("^language-[\\w+#-]{1,40}$")
}
