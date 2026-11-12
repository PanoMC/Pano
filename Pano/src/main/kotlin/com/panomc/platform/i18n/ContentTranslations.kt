package com.panomc.platform.i18n

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Translation.Companion.TranslationType
import com.panomc.platform.ticket.TicketFields
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Translations of admin-written content (ticket categories and their fields, the registration agreement). The text
 * typed in the panel is the default (the platform language); per-locale translations live in the `translation`
 * table under type [TranslationType.CONTENT], so Panel → Languages edits the same rows the panel's translation
 * popovers write. Keys:
 *
 * - `ticket-category.<id>.title` / `.description`
 * - `ticket-category.<id>.field.<fieldKey>.label` / `.placeholder` / `.help` / `.option.<value>`
 * - `register-agreement`
 *
 * On the wire every translatable object carries `i18n`: locale code → { prop → text } (see [categoryI18n],
 * [fieldI18n]); the client picks its language and falls back to the default text.
 */
@Lazy
@Component
class ContentTranslations(
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager
) {
    /** One stored translation. */
    class Entry(val localeCode: String, val key: String, val value: String)

    private fun table() = "`${databaseManager.getTablePrefix()}translation`"

    private fun localeTable() = "`${databaseManager.getTablePrefix()}locale`"

    /** Every `CONTENT` translation whose key starts with one of [prefixes]. */
    suspend fun load(prefixes: Collection<String>, sqlClient: SqlClient): List<Entry> {
        if (prefixes.isEmpty()) return emptyList()

        val likes = prefixes.joinToString(" OR ") { "t.`key` LIKE ?" }
        val tuple = Tuple.tuple().addString(TranslationType.CONTENT.name)

        prefixes.forEach { tuple.addString(escapeLike(it) + "%") }

        return sqlClient.preparedQuery(
            "SELECT l.`code`, t.`key`, t.`value` FROM ${table()} t JOIN ${localeTable()} l ON l.`id` = t.`localeId` " +
                    "WHERE t.`type` = ? AND ($likes)"
        ).execute(tuple).coAwait().map { Entry(it.getString(0), it.getString(1), it.getString(2)) }
    }

    /**
     * Replaces every `CONTENT` translation under [prefix] with [entries] (key → locale code → text). Blank texts and
     * unknown locale codes are dropped.
     */
    suspend fun replace(prefix: String, entries: Map<String, Map<String, String>>, sqlClient: SqlClient) {
        sqlClient.preparedQuery("DELETE FROM ${table()} WHERE `type` = ? AND `key` LIKE ?")
            .execute(Tuple.of(TranslationType.CONTENT.name, escapeLike(prefix) + "%")).coAwait()

        val localeIds = databaseManager.localeDao.getAll(sqlClient).associate { it.code to it.id }
        val now = System.currentTimeMillis()
        val rows = entries.flatMap { (key, byLocale) ->
            byLocale.mapNotNull { (code, text) ->
                val localeId = localeIds[code] ?: return@mapNotNull null

                text.trim().takeIf { it.isNotEmpty() }?.let {
                    Tuple.of(localeId.toString(), TranslationType.CONTENT.name, key, it, now, now)
                }
            }
        }

        if (rows.isEmpty()) return

        sqlClient.preparedQuery(
            "INSERT INTO ${table()} (`localeId`, `type`, `key`, `value`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?)"
        ).executeBatch(rows).coAwait()
    }

    /** `%` and `_` of [text] escaped for a `LIKE` pattern. */
    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ----- ticket categories -------------------------------------------------------------------------------------------

    fun categoryPrefix(categoryId: Long) = ContentI18n.categoryPrefix(categoryId)

    /** The translations of [categoryIds], grouped by category id (key → locale code → text). */
    suspend fun loadCategories(categoryIds: Collection<Long>, sqlClient: SqlClient): Map<Long, Map<String, Map<String, String>>> {
        val entries = load(categoryIds.map { categoryPrefix(it) }, sqlClient)
        val out = HashMap<Long, MutableMap<String, MutableMap<String, String>>>()

        for (entry in entries) {
            val id = entry.key.removePrefix("ticket-category.").substringBefore('.').toLongOrNull() ?: continue

            out.getOrPut(id) { HashMap() }.getOrPut(entry.key) { HashMap() }[entry.localeCode] = entry.value
        }

        return out
    }

    fun categoryI18n(categoryId: Long, translations: Map<String, Map<String, String>>?) = ContentI18n.categoryI18n(categoryId, translations)

    // ----- posts and post categories -----------------------------------------------------------------------------------

    /** The translations under `<root><id>.` of every id, grouped by id (key → locale code → text). */
    suspend fun loadGrouped(root: String, ids: Collection<Long>, sqlClient: SqlClient): Map<Long, Map<String, Map<String, String>>> {
        val out = HashMap<Long, MutableMap<String, MutableMap<String, String>>>()

        for (entry in load(ids.distinct().map { "$root$it." }, sqlClient)) {
            val id = entry.key.removePrefix(root).substringBefore('.').toLongOrNull() ?: continue

            out.getOrPut(id) { HashMap() }.getOrPut(entry.key) { HashMap() }[entry.localeCode] = entry.value
        }

        return out
    }

    suspend fun loadPosts(ids: Collection<Long>, sqlClient: SqlClient) = loadGrouped(ContentI18n.POST, ids, sqlClient)

    suspend fun loadPostCategories(ids: Collection<Long>, sqlClient: SqlClient) = loadGrouped(ContentI18n.POST_CATEGORY, ids, sqlClient)


    fun withFieldI18n(categoryId: Long, definitions: io.vertx.core.json.JsonArray, translations: Map<String, Map<String, String>>?) =
        ContentI18n.withFieldI18n(categoryId, definitions, translations)

    fun withSnapshotI18n(categoryId: Long, snapshot: io.vertx.core.json.JsonArray, translations: Map<String, Map<String, String>>?) =
        ContentI18n.withSnapshotI18n(categoryId, snapshot, translations)

    fun parseCategory(categoryId: Long, rawI18n: Any?, rawFields: io.vertx.core.json.JsonArray?, fields: List<TicketFields.Definition>) =
        ContentI18n.parseCategory(categoryId, rawI18n, rawFields, fields)

    // ----- registration agreement --------------------------------------------------------------------------------------

    /** The registration agreement in every translated language: locale code → html. */
    suspend fun registerAgreement(sqlClient: SqlClient): JsonObject {
        val out = JsonObject()

        load(listOf(REGISTER_AGREEMENT), sqlClient).filter { it.key == REGISTER_AGREEMENT }.forEach { out.put(it.localeCode, it.value) }

        return out
    }

    /** Replaces the agreement's translations with [raw] (locale code → html); a text that is not a string is dropped. */
    suspend fun setRegisterAgreement(raw: JsonObject, sanitize: (String) -> String, sqlClient: SqlClient) {
        val byLocale = raw.map.mapNotNull { (code, value) -> (value as? String)?.let { code to sanitize(it) } }.toMap()

        // the prefix is the whole key: nothing else starts with it
        replace(REGISTER_AGREEMENT, mapOf(REGISTER_AGREEMENT to byLocale), sqlClient)
    }

    // ----- Panel → Languages ------------------------------------------------------------------------------------------

    /** Every translatable key that exists now with its default text: the "original" column of Panel → Languages. */
    suspend fun originals(sqlClient: SqlClient): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        configManager.config.registerAgreement.takeIf { it.isNotBlank() }?.let { out[REGISTER_AGREEMENT] = it }

        val categories = databaseManager.ticketCategoryDao.getList(Int.MAX_VALUE, 0L, sqlClient)
        val fieldSets = databaseManager.ticketCategoryFieldSetDao.getByCategoryIds(categories.map { it.id }, sqlClient)

        for (category in categories) {
            val prefix = categoryPrefix(category.id)

            out[prefix + "title"] = category.title
            if (category.description.isNotBlank()) out[prefix + "description"] = category.description

            for (field in TicketFields.definitionsOf(fieldSets[category.id])) {
                val fp = ContentI18n.fieldPrefix(category.id, field.key)

                out[fp + "label"] = field.label
                if (field.placeholder.isNotBlank()) out[fp + "placeholder"] = field.placeholder
                if (field.help.isNotBlank()) out[fp + "help"] = field.help
                field.options.forEach { out["${fp}option.${it.value}"] = it.label }
            }
        }

        for (category in databaseManager.postCategoryDao.getAll(sqlClient)) {
            val prefix = "${ContentI18n.POST_CATEGORY}${category.id}."

            out[prefix + "title"] = category.title
            if (category.description.isNotBlank()) out[prefix + "description"] = category.description
        }

        val posts = sqlClient.query("SELECT `id`, `title`, `text` FROM `${databaseManager.getTablePrefix()}post`").execute().coAwait()

        for (row in posts) {
            val prefix = "${ContentI18n.POST}${row.getLong(0)}."

            out[prefix + "title"] = row.getString(1).orEmpty()
            row.getString(2)?.takeIf { it.isNotBlank() }?.let { out[prefix + "text"] = it }
        }

        return out
    }

    companion object {
        const val REGISTER_AGREEMENT = "register-agreement"
        const val TITLE_MAX = 32
        const val DESCRIPTION_MAX = 2000
    }
}

/** The pure half of [ContentTranslations]: keys, the `i18n` objects of the answers and the parsing of a save. */
object ContentI18n {
    fun categoryPrefix(categoryId: Long) = "ticket-category.$categoryId."

    fun fieldPrefix(categoryId: Long, fieldKey: String) = "${categoryPrefix(categoryId)}field.$fieldKey."

    /** `i18n` of a category: locale code → { title, description }. */
    fun categoryI18n(categoryId: Long, translations: Map<String, Map<String, String>>?): JsonObject {
        val out = JsonObject()
        val prefix = categoryPrefix(categoryId)

        for (prop in listOf("title", "description")) {
            translations?.get(prefix + prop)?.forEach { (code, text) -> obj(out, code).put(prop, text) }
        }

        return out
    }

    /** `i18n` of a field: locale code → { label, placeholder, help, options: { value → label } }. */
    fun fieldI18n(categoryId: Long, fieldKey: String, translations: Map<String, Map<String, String>>?): JsonObject {
        val out = JsonObject()
        val prefix = fieldPrefix(categoryId, fieldKey)

        translations?.forEach { (key, byLocale) ->
            if (!key.startsWith(prefix)) return@forEach

            val rest = key.removePrefix(prefix)

            byLocale.forEach { (code, text) ->
                when {
                    rest in FIELD_PROPS -> obj(out, code).put(rest, text)
                    rest.startsWith("option.") -> {
                        val locale = obj(out, code)
                        val options = locale.getJsonObject("options") ?: JsonObject().also { locale.put("options", it) }

                        options.put(rest.removePrefix("option."), text)
                    }
                }
            }
        }

        return out
    }

    private fun obj(parent: JsonObject, key: String): JsonObject =
        parent.getJsonObject(key) ?: JsonObject().also { parent.put(key, it) }

    /** [definitions] (the stored JSON array) with every field's `i18n` added. */
    fun withFieldI18n(categoryId: Long, definitions: io.vertx.core.json.JsonArray, translations: Map<String, Map<String, String>>?) =
        io.vertx.core.json.JsonArray(definitions.map { item ->
            val field = (item as? JsonObject)?.copy() ?: return@map item

            field.put("i18n", fieldI18n(categoryId, field.getString("key").orEmpty(), translations))
        })

    /**
     * The translations a category save sends (`i18n` of the category, `i18n` of each field in [rawFields]), as the
     * entries of [replace]. Throws [TicketFields.Invalid] with `i18n.<code>.<prop>` / `fields.<i>.i18n.<code>.<prop>`
     * keys for a text that is too long or not a string.
     */
    fun parseCategory(categoryId: Long, rawI18n: Any?, rawFields: io.vertx.core.json.JsonArray?, fields: List<TicketFields.Definition>): Map<String, Map<String, String>> {
        val errors = LinkedHashMap<String, String>()
        val out = HashMap<String, MutableMap<String, String>>()

        fun put(key: String, code: String, raw: Any?, max: Int, errorKey: String) {
            if (raw == null) return

            val text = (raw as? String)?.trim() ?: run {
                errors[errorKey] = "INVALID"

                return
            }

            if (text.isEmpty()) return
            if (text.length > max) errors[errorKey] = "TOO_LONG"

            out.getOrPut(key) { HashMap() }[code] = text
        }

        (rawI18n as? JsonObject)?.forEach { (code, value) ->
            val props = value as? JsonObject ?: return@forEach

            put(categoryPrefix(categoryId) + "title", code, props.getValue("title"), ContentTranslations.TITLE_MAX, "i18n.$code.title")
            put(categoryPrefix(categoryId) + "description", code, props.getValue("description"), ContentTranslations.DESCRIPTION_MAX, "i18n.$code.description")
        }

        rawFields?.forEachIndexed { index, item ->
            val raw = item as? JsonObject ?: return@forEachIndexed
            val definition = fields.getOrNull(index) ?: return@forEachIndexed
            val prefix = fieldPrefix(categoryId, definition.key)

            (raw.getValue("i18n") as? JsonObject)?.forEach { (code, value) ->
                val props = value as? JsonObject ?: return@forEach
                val p = "fields.$index.i18n.$code"

                put(prefix + "label", code, props.getValue("label"), TicketFields.MAX_LABEL, "$p.label")
                put(prefix + "placeholder", code, props.getValue("placeholder"), TicketFields.MAX_PLACEHOLDER, "$p.placeholder")
                put(prefix + "help", code, props.getValue("help"), TicketFields.MAX_HELP, "$p.help")

                (props.getValue("options") as? JsonObject)?.forEach { (optionValue, label) ->
                    // a translation of an option the field no longer has is dropped
                    if (definition.options.any { it.value == optionValue }) {
                        put("${prefix}option.$optionValue", code, label, TicketFields.MAX_LABEL, "$p.options.$optionValue")
                    }
                }
            }
        }

        if (errors.isNotEmpty()) throw TicketFields.Invalid(errors)

        return out
    }

    /** [snapshot] (a ticket's field values) with the `i18n` of each entry's field in its category: label and options. */
    fun withSnapshotI18n(categoryId: Long, snapshot: io.vertx.core.json.JsonArray, translations: Map<String, Map<String, String>>?) =
        io.vertx.core.json.JsonArray(snapshot.map { item ->
            val entry = (item as? JsonObject)?.copy() ?: return@map item
            val i18n = fieldI18n(categoryId, entry.getString("key").orEmpty(), translations)

            // what the ticket page needs: the label and the option labels
            i18n.forEach { (_, props) -> (props as? JsonObject)?.let { it.remove("placeholder"); it.remove("help") } }

            entry.put("i18n", i18n)
        })

    private val FIELD_PROPS = setOf("label", "placeholder", "help")

    const val POST = "post."
    const val POST_CATEGORY = "post-category."
    const val POST_TITLE_MAX = 255

    /** A post's translated html may be as long as the column allows (`MEDIUMTEXT`). */
    const val POST_TEXT_MAX = 16_000_000

    /** `i18n` of a flat object under [prefix]: locale code → { prop → text } for [props]. */
    fun flatI18n(prefix: String, props: Collection<String>, translations: Map<String, Map<String, String>>?): JsonObject {
        val out = JsonObject()

        for (prop in props) {
            translations?.get(prefix + prop)?.forEach { (code, text) -> obj(out, code).put(prop, text) }
        }

        return out
    }

    /**
     * The translations a save sends for a flat object (`i18n`: locale code → { prop → text }), as the entries of
     * `ContentTranslations.replace`. [limits] names the props and their max length, [clean] an optional cleaner per
     * prop (html). Throws [TicketFields.Invalid] with `i18n.<code>.<prop>` keys.
     */
    fun parseFlat(
        prefix: String, raw: Any?, limits: Map<String, Int>, clean: Map<String, (String) -> String> = emptyMap()
    ): Map<String, Map<String, String>> {
        val errors = LinkedHashMap<String, String>()
        val out = HashMap<String, MutableMap<String, String>>()

        (raw as? JsonObject)?.forEach { (code, value) ->
            val props = value as? JsonObject ?: return@forEach

            for ((prop, max) in limits) {
                val rawText = props.getValue(prop) ?: continue
                val text = (rawText as? String)?.let { clean[prop]?.invoke(it) ?: it }?.trim()

                when {
                    text == null -> errors["i18n.$code.$prop"] = "INVALID"
                    text.isEmpty() -> Unit
                    text.length > max -> errors["i18n.$code.$prop"] = "TOO_LONG"
                    else -> out.getOrPut(prefix + prop) { HashMap() }[code] = text
                }
            }
        }

        if (errors.isNotEmpty()) throw TicketFields.Invalid(errors)

        return out
    }

    /** `i18n` sent as a JSON text (a multipart form field): the object, `null` when absent. Throws on bad JSON. */
    fun parseJsonField(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null

        return try {
            JsonObject(text)
        } catch (e: Exception) {
            throw TicketFields.Invalid(mapOf("i18n" to "INVALID"))
        }
    }

    fun postPrefix(postId: Long) = "$POST$postId."

    fun postCategoryPrefix(categoryId: Long) = "$POST_CATEGORY$categoryId."

    fun postI18n(postId: Long, translations: Map<String, Map<String, String>>?) =
        flatI18n(postPrefix(postId), listOf("title", "text"), translations)

    fun postCategoryI18n(categoryId: Long, translations: Map<String, Map<String, String>>?) =
        flatI18n(postCategoryPrefix(categoryId), listOf("title", "description"), translations)
}
