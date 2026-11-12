package com.panomc.platform.i18n

import com.panomc.platform.db.model.PostCategory
import com.panomc.platform.util.HtmlSanitizer
import util.StringUtil
import io.vertx.core.json.JsonObject

/**
 * The `i18n` of the posts and post categories an answer shows, looked up by id (already loaded by the route). The
 * default ([PostI18n] with no lookups) gives no translations, so a caller that does not load them answers as before.
 */
class PostI18n(
    private val postLookup: (Long) -> JsonObject? = { null },
    private val categoryLookup: (Long) -> JsonObject? = { null }
) {
    fun post(id: Long): JsonObject? = postLookup(id)

    fun category(id: Long): JsonObject? = categoryLookup(id)

    /** A category as the lists show it: with its `i18n` when it has translations, else the row as before. */
    fun categoryMap(category: PostCategory): Any {
        val i18n = category(category.id)?.takeIf { !it.isEmpty } ?: return category

        return mapOf(
            "id" to category.id,
            "title" to category.title,
            "description" to category.description,
            "url" to category.url,
            "color" to category.color,
            "i18n" to i18n
        )
    }

    companion object {
        /**
         * [value] (an answer's map / list tree) without the `i18n` entries that hold nothing (`null` or `{}`), so an
         * answer of a site without translations stays exactly what it was.
         */
        fun compact(value: Any?): Any? = when (value) {
            is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { out ->
                value.forEach { (key, item) ->
                    if (key == "i18n" && (item == null || (item is JsonObject && item.isEmpty))) return@forEach

                    out[key] = compact(item)
                }
            }

            is List<*> -> value.map { compact(it) }
            else -> value
        }

        @Suppress("UNCHECKED_CAST")
        fun compactMap(value: Map<String, Any?>): Map<String, Any?> = compact(value) as Map<String, Any?>

        /** [i18n] of a post with every translated `text` cut like the list's excerpt (500 characters of html). */
        fun excerpt(i18n: JsonObject): JsonObject {
            val out = i18n.copy()

            out.forEach { (_, value) ->
                val props = value as? JsonObject ?: return@forEach

                (props.getValue("text") as? String)?.let {
                    props.put("text", StringUtil.truncateHTML(HtmlSanitizer.sanitize(it), 500, "&hellip;"))
                }
            }

            return out
        }

        /** [i18n] of a post with only the titles (a neighbour link, a table row). */
        fun titleOnly(i18n: JsonObject?): JsonObject? {
            if (i18n == null) return null

            val out = JsonObject()

            i18n.forEach { (code, value) ->
                ((value as? JsonObject)?.getValue("title") as? String)?.let { out.put(code, JsonObject().put("title", it)) }
            }

            return out
        }
    }
}
