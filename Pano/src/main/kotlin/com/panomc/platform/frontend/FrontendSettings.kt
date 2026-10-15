package com.panomc.platform.frontend

import com.panomc.platform.AppConstants
import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.SystemPropertyDao
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.model.Error
import com.panomc.platform.ui.FrontendMode
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * Schema-driven front-end settings (open front-end plan, doc 05 section 9).
 *
 * A front-end (a theme, a custom app, or an EXTERNAL / NONE site with a descriptor) describes its settings in
 * `settingsSchema`: the themes' `tabs` / `defaultTab` plus `fields`. Pano validates the admin's values by
 * `fields`, stores them in the system property `theme_settings` under the id of the active front-end and serves
 * them, with the defaults filled in, from the public `GET /frontend/settings`.
 */

// --- errors ------------------------------------------------------------------

/** The `settingsSchema` of a front-end cannot be used. [key] names the tab, field or schema key at fault. */
class FrontendSettingsSchemaInvalid(key: String, reason: String) : Error(
    "FRONTEND_SETTINGS_SCHEMA_INVALID",
    400,
    "",
    mapOf("message" to "settingsSchema is invalid at '$key': $reason.", "key" to key, "reason" to reason)
)

/** A value does not fit the field it is written to. */
class FrontendSettingInvalid(key: String, val reason: String) : Error(
    "FRONTEND_SETTING_INVALID",
    400,
    "",
    mapOf("message" to "The value of '$key' is not accepted: $reason.", "key" to key, "reason" to reason)
)

/** The active front-end declares no `fields`, so there is nothing to validate a write by. */
class FrontendSettingsNoSchema : Error(
    "FRONTEND_SETTINGS_NO_SCHEMA",
    409,
    "",
    mapOf("message" to "The active front-end has no settingsSchema with fields; its settings are not edited here.")
)

// --- schema ------------------------------------------------------------------

/** The eight field types of doc 05 section 9. */
enum class SettingType(val wire: String) {
    TEXT("text"),
    TEXTAREA("textarea"),
    BOOLEAN("boolean"),
    NUMBER("number"),
    SELECT("select"),
    COLOR("color"),
    URL("url"),
    IMAGE("image");

    companion object {
        fun of(wire: String?): SettingType? = entries.firstOrNull { it.wire == wire }
    }
}

class SettingOption(val value: Any, val label: String)

/**
 * One entry of `fields`.
 *
 * @property label plain text, or a key of the front-end's own lang files when it contains a dot (resolved by the panel)
 * @property default used while nothing is stored; always a valid value of the field
 * @property min / max the bound of a number, the length of a text or textarea
 */
class SettingField(
    val key: String,
    val type: SettingType,
    val label: String,
    val help: String?,
    val default: Any?,
    val options: List<SettingOption>,
    val required: Boolean,
    val min: Double?,
    val max: Double?
) {
    fun toJson(): JsonObject {
        val json = JsonObject().put("type", type.wire).put("label", label)

        help?.let { json.put("help", it) }
        default?.let { json.put("default", it) }

        if (type == SettingType.SELECT) {
            json.put("options", JsonArray(options.map { JsonObject().put("value", it.value).put("label", it.label) }))
        }

        if (required) {
            json.put("required", true)
        }

        min?.let { json.put("min", numberOf(it)) }
        max?.let { json.put("max", numberOf(it)) }

        return json
    }

    private fun numberOf(value: Double): Any = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong() else value

    /** The value the field stores for [value], or a [FrontendSettingInvalid] naming the field. */
    fun check(value: Any): Any {
        try {
            return normalize(value)
        } catch (problem: Problem) {
            throw FrontendSettingInvalid(key, problem.reason)
        }
    }

    internal fun checkOrNull(value: Any?): Any? {
        if (value == null) return null

        return try {
            normalize(value)
        } catch (_: Problem) {
            null
        }
    }

    private fun normalize(value: Any): Any {
        when (type) {
            SettingType.TEXT, SettingType.TEXTAREA -> {
                val text = value as? String ?: problem("WRONG_TYPE")
                val length = text.codePointCount(0, text.length)

                if (min != null && length < min.toInt()) problem("TOO_SHORT")

                val cap = max?.toInt() ?: if (type == SettingType.TEXT) TEXT_CAP else TEXTAREA_CAP

                if (length > cap) problem("TOO_LONG")

                return text
            }

            SettingType.BOOLEAN -> return value as? Boolean ?: problem("WRONG_TYPE")

            SettingType.NUMBER -> {
                val number = value as? Number ?: problem("WRONG_TYPE")
                val double = number.toDouble()

                if (double.isNaN() || double.isInfinite()) problem("WRONG_TYPE")

                if (min != null && double < min) problem("BELOW_MIN")
                if (max != null && double > max) problem("ABOVE_MAX")

                return number
            }

            SettingType.SELECT -> return options.firstOrNull { sameValue(it.value, value) }?.value ?: problem("NOT_AN_OPTION")

            SettingType.COLOR -> {
                val text = value as? String ?: problem("WRONG_TYPE")

                if (text.isNotEmpty() && !COLOR.matches(text)) problem("INVALID_COLOR")

                return text
            }

            SettingType.URL -> {
                val text = value as? String ?: problem("WRONG_TYPE")

                if (text.isNotEmpty() && (text.length > URL_CAP || !isUrl(text))) problem("INVALID_URL")

                return text
            }

            // An image is a file, never a value in `settings`.
            SettingType.IMAGE -> problem("WRONG_TYPE")
        }
    }

    internal class Problem(val reason: String) : RuntimeException(reason, null, false, false)

    private fun problem(reason: String): Nothing = throw Problem(reason)

    companion object {
        const val TEXT_CAP = 10_000
        const val TEXTAREA_CAP = 100_000
        const val URL_CAP = 2_000

        private val COLOR = Regex("^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

        /** A site path (`/x`, never `//x`) or an `http(s)` URL with a host; no blanks, no control characters. */
        fun isUrl(text: String): Boolean {
            if (text.any { it.isWhitespace() || it.isISOControl() }) return false

            if (text.startsWith("/")) return !text.startsWith("//")

            val uri = runCatching { URI(text) }.getOrNull() ?: return false

            return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrEmpty()
        }

        fun sameValue(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    }
}

/** A file the admin sent with a settings write: the multipart field [fieldName], where Vert.x spooled it, what it was called. */
class SettingsUpload(val fieldName: String, val path: String, val fileName: String?, val contentType: String?)

/** What a write changes: the object to store, the uploads to copy into the settings folder and the files to delete. */
class SettingsPlan(val settings: JsonObject, val copies: List<Pair<SettingsUpload, String>>, val deletes: List<String>)

/** The settings an admin sees and a front-end reads: values with defaults filled in, and the uploaded files by field. */
class SettingsReading(val settings: JsonObject, val files: JsonObject)

/**
 * The `settingsSchema` of a front-end (doc 05 section 9). [parse] returns null for a schema without `fields`
 * (the themes' older format), which is not an error: such a front-end keeps its own settings page.
 */
class FrontendSettingsSchema private constructor(
    val tabs: Map<String, List<String>>?,
    val defaultTab: String?,
    val fields: Map<String, SettingField>
) {
    fun toJson(): JsonObject {
        val json = JsonObject()

        tabs?.let { json.put("tabs", JsonObject().apply { it.forEach { (name, keys) -> put(name, JsonArray(keys)) } }) }
        defaultTab?.let { json.put("defaultTab", it) }
        json.put("fields", JsonObject().apply { fields.forEach { (key, field) -> put(key, field.toJson()) } })

        return json
    }

    /**
     * Checks a write against the schema and plans it. [request] is the `settings` object of the call: a value per
     * field key, `files` (the file names to keep, per image field) and `remove-files`. Keys that are no field are dropped.
     * The result replaces what is stored (the files of image fields are kept unless named in `remove-files` or left
     * out of a `files` entry that is present), so a front-end sends the whole form.
     */
    fun prepare(
        current: JsonObject?,
        request: JsonObject,
        uploads: List<SettingsUpload> = emptyList(),
        newName: (SettingsUpload) -> String = FrontendSettingsStorage::generatedName
    ): SettingsPlan {
        val values = JsonObject()

        for ((key, field) in fields) {
            if (field.type == SettingType.IMAGE) continue

            val raw = request.getValue(key)

            if (raw == null) {
                if (field.required && field.default == null) {
                    throw FrontendSettingInvalid(key, "REQUIRED")
                }

                continue
            }

            if (field.required && raw is String && raw.isEmpty() && field.type != SettingType.SELECT && field.type != SettingType.BOOLEAN) {
                throw FrontendSettingInvalid(key, "REQUIRED")
            }

            values.put(key, field.check(raw))
        }

        val currentFiles = current?.getValue("files") as? JsonObject
        val keepFiles = JsonObject()

        (request.getValue("files") as? JsonObject)?.forEach { (key, value) ->
            if (fields[key]?.type != SettingType.IMAGE) return@forEach

            val existing = FrontendSettingsStorage.namesOf(currentFiles?.getValue(key)).toSet()

            keepFiles.put(key, JsonArray(FrontendSettingsStorage.namesOf(value).filter { it in existing }))
        }

        val accepted = uploads.filter { fields[it.fieldName]?.type == SettingType.IMAGE }

        accepted.forEach { if (!isImage(it)) throw FrontendSettingInvalid(it.fieldName, "NOT_AN_IMAGE") }

        // A `files` object is always carried, so a write that sets no value at all still merges the stored
        // files (an image is removed by naming it, never by leaving it out).
        val candidate = values.copy().put("files", keepFiles)

        (request.getValue("remove-files") as? JsonArray)?.let { candidate.put("remove-files", it) }

        val plan = FrontendSettingsStorage.plan(current, candidate, accepted, newName)
        val files = plan.settings.getValue("files") as? JsonObject ?: JsonObject()

        for ((key, field) in fields) {
            if (field.type == SettingType.IMAGE && field.required && FrontendSettingsStorage.namesOf(files.getValue(key)).isEmpty()) {
                throw FrontendSettingInvalid(key, "REQUIRED")
            }
        }

        // Only image fields keep files; the rest of `files` (stale keys) is dropped.
        for (key in files.fieldNames().toList()) {
            if (fields[key]?.type != SettingType.IMAGE) files.remove(key)
        }

        if (files.isEmpty) plan.settings.remove("files")

        return plan
    }

    /** What is stored, as the admin and the front-end read it: values with the defaults filled in, files of image fields. */
    fun read(stored: JsonObject?): SettingsReading {
        val settings = JsonObject()
        val files = JsonObject()
        val storedFiles = stored?.getValue("files") as? JsonObject

        for ((key, field) in fields) {
            if (field.type == SettingType.IMAGE) {
                val names = FrontendSettingsStorage.namesOf(storedFiles?.getValue(key))

                if (names.isNotEmpty()) files.put(key, JsonArray(names))

                continue
            }

            val value = field.checkOrNull(stored?.getValue(key)) ?: field.default

            if (value != null) settings.put(key, value)
        }

        return SettingsReading(settings, files)
    }

    private fun isImage(upload: SettingsUpload): Boolean {
        val type = upload.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

        if (type.startsWith("image/")) return true

        if (type.isNotEmpty() && type != "application/octet-stream") return false

        val extension = upload.fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        return extension in IMAGE_EXTENSIONS
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
        private val FIELD_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
        private val RESERVED_KEYS = setOf("files", "remove-files")

        private fun invalid(key: String, reason: String): Nothing = throw FrontendSettingsSchemaInvalid(key, reason)

        /** The settings of a plain `settings` object without a schema: everything but the file bookkeeping. */
        fun readRaw(stored: JsonObject?): SettingsReading {
            val settings = stored?.copy() ?: JsonObject()
            val files = settings.getValue("files") as? JsonObject ?: JsonObject()

            settings.remove("files")
            settings.remove("remove-files")

            return SettingsReading(settings, files.copy())
        }

        /**
         * Parses [source] (a `settingsSchema` value). Null when there is no object with a non-empty `fields`;
         * [FrontendSettingsSchemaInvalid] when there is one and it breaks a rule of doc 05 section 9.
         */
        fun parse(source: Any?): FrontendSettingsSchema? {
            val json = source as? JsonObject ?: return null

            if (!json.containsKey("fields")) return null

            val fieldsJson = json.getValue("fields") as? JsonObject ?: invalid("fields", "NOT_AN_OBJECT")

            if (fieldsJson.isEmpty) return null

            val fields = linkedMapOf<String, SettingField>()

            for (key in fieldsJson.fieldNames()) {
                fields[key] = parseField(key, fieldsJson.getValue(key))
            }

            var tabs: LinkedHashMap<String, List<String>>? = null
            val tabsValue = json.getValue("tabs")

            if (json.containsKey("tabs") && tabsValue != null) {
                val tabsJson = tabsValue as? JsonObject ?: invalid("tabs", "NOT_AN_OBJECT")
                val parsed = linkedMapOf<String, List<String>>()
                val tabOf = hashMapOf<String, String>()

                for (name in tabsJson.fieldNames()) {
                    val list = tabsJson.getValue(name) as? JsonArray ?: invalid(name, "TAB_NOT_AN_ARRAY")
                    val keys = mutableListOf<String>()

                    for (entry in list) {
                        val key = entry as? String ?: invalid(name, "TAB_KEY_NOT_A_STRING")

                        if (!fields.containsKey(key)) invalid(key, "TAB_KEY_WITHOUT_FIELD")
                        if (tabOf.containsKey(key)) invalid(key, "KEY_IN_TWO_TABS")

                        tabOf[key] = name
                        keys.add(key)
                    }

                    parsed[name] = keys
                }

                fields.keys.firstOrNull { !tabOf.containsKey(it) }?.let { invalid(it, "FIELD_WITHOUT_TAB") }

                tabs = parsed
            }

            var defaultTab: String? = null
            val defaultValue = json.getValue("defaultTab")

            if (json.containsKey("defaultTab") && defaultValue != null) {
                defaultTab = defaultValue as? String ?: invalid("defaultTab", "NOT_A_STRING")

                if (tabs == null || !tabs.containsKey(defaultTab)) invalid("defaultTab", "UNKNOWN_TAB")
            }

            return FrontendSettingsSchema(tabs, defaultTab, fields)
        }

        private fun parseField(key: String, value: Any?): SettingField {
            if (!FIELD_KEY.matches(key) || key in RESERVED_KEYS) invalid(key, "INVALID_KEY")

            val json = value as? JsonObject ?: invalid(key, "NOT_AN_OBJECT")
            val type = SettingType.of(json.getValue("type") as? String) ?: invalid(key, "UNKNOWN_TYPE")
            val label = (json.getValue("label") as? String)?.takeIf { it.isNotBlank() } ?: invalid(key, "LABEL_REQUIRED")

            val help = json.getValue("help")

            if (help != null && help !is String) invalid(key, "HELP_NOT_A_STRING")

            val required = json.getValue("required")

            if (required != null && required !is Boolean) invalid(key, "REQUIRED_NOT_A_BOOLEAN")

            val min = numberOrNull(key, json, "min")
            val max = numberOrNull(key, json, "max")

            if (min != null && max != null && min > max) invalid(key, "MIN_ABOVE_MAX")

            if ((min != null || max != null) && type in setOf(SettingType.TEXT, SettingType.TEXTAREA)) {
                if ((min != null && (min < 0 || min != Math.rint(min))) || (max != null && (max < 0 || max != Math.rint(max)))) {
                    invalid(key, "LENGTH_BOUND_NOT_AN_INTEGER")
                }
            }

            val options = if (type == SettingType.SELECT) parseOptions(key, json.getValue("options")) else emptyList()

            // The bounds mean nothing to the other types and are not kept, so they cannot disagree with a value.
            val bounded = type == SettingType.NUMBER || type == SettingType.TEXT || type == SettingType.TEXTAREA

            val field = SettingField(
                key = key,
                type = type,
                label = label,
                help = help as String?,
                default = null,
                options = options,
                required = required == true,
                min = if (bounded) min else null,
                max = if (bounded) max else null
            )

            val default = json.getValue("default") ?: return field

            if (type == SettingType.IMAGE) invalid(key, "IMAGE_HAS_NO_DEFAULT")

            val checked = try {
                field.check(default)
            } catch (e: FrontendSettingInvalid) {
                invalid(key, "DEFAULT_" + e.reason)
            }

            return SettingField(key, type, label, field.help, checked, options, field.required, field.min, field.max)
        }

        private fun numberOrNull(key: String, json: JsonObject, name: String): Double? {
            val value = json.getValue(name) ?: return null
            val number = value as? Number ?: invalid(key, "${name.uppercase()}_NOT_A_NUMBER")

            return number.toDouble().takeIf { !it.isNaN() && !it.isInfinite() } ?: invalid(key, "${name.uppercase()}_NOT_A_NUMBER")
        }

        private fun parseOptions(key: String, value: Any?): List<SettingOption> {
            val list = value as? JsonArray ?: invalid(key, "OPTIONS_REQUIRED")

            if (list.isEmpty) invalid(key, "OPTIONS_REQUIRED")

            val options = mutableListOf<SettingOption>()

            for (entry in list) {
                val option = entry as? JsonObject ?: invalid(key, "OPTION_NOT_AN_OBJECT")
                val optionValue = option.getValue("value")

                if (optionValue !is String && optionValue !is Number && optionValue !is Boolean) invalid(key, "OPTION_VALUE_INVALID")

                val label = (option.getValue("label") as? String)?.takeIf { it.isNotBlank() } ?: invalid(key, "OPTION_LABEL_REQUIRED")

                if (options.any { SettingField.sameValue(it.value, optionValue) }) invalid(key, "OPTION_DUPLICATE")

                options.add(SettingOption(optionValue, label))
            }

            return options
        }

        /** The schema in a `core-meta.json` / `manifest.json` file, null when the file has none; a broken one is logged and counts as none. */
        fun fromFile(file: File?, logger: Logger? = null): FrontendSettingsSchema? {
            if (file == null || !file.isFile) return null

            return try {
                parse(JsonObject(file.readText()).getValue("settingsSchema"))
            } catch (e: Throwable) {
                logger?.warn("settingsSchema in {} is not usable and is ignored: {}", file.path, e.message)

                null
            }
        }
    }
}

// --- storage -------------------------------------------------------------------

/** One system property, read and written as text. The platform uses [SystemPropertyAccess]; tests use a map. */
interface PropertyAccess {
    suspend fun read(): String?

    suspend fun write(value: String)
}

class SystemPropertyAccess(
    private val dao: SystemPropertyDao,
    private val sqlClient: SqlClient,
    private val option: String = FrontendSettingsStorage.PROPERTY
) : PropertyAccess {
    override suspend fun read(): String? = dao.getByOption(option, sqlClient)?.value

    override suspend fun write(value: String) {
        if (dao.getByOption(option, sqlClient) != null) {
            dao.update(option, value, sqlClient)
        } else {
            dao.add(SystemProperty(option = option, value = value), sqlClient)
        }
    }
}

/**
 * Where the settings of every front-end are kept: the system property `theme_settings`, one object per
 * front-end id, with the uploaded files of that front-end in the `theme-settings` uploads folder. The theme
 * settings endpoint and the schema-driven one both write through this class.
 *
 * A write is planned first ([plan], no I/O) and committed after ([commit]), so a schema can refuse a write
 * before a single file is copied.
 */
class FrontendSettingsStorage(private val folder: File) {
    suspend fun load(access: PropertyAccess, frontendId: String): JsonObject? = readAll(access).getValue(frontendId) as? JsonObject

    /** Copies the uploads, stores the settings under [frontendId] and deletes the files the write replaced. */
    suspend fun commit(access: PropertyAccess, frontendId: String, plan: SettingsPlan) {
        withContext(Dispatchers.IO) {
            if (!folder.exists() || !folder.isDirectory) {
                folder.deleteRecursively()
                folder.mkdirs()
            }

            plan.copies.forEach { (upload, name) ->
                val source = File(upload.path)

                source.copyTo(File(folder, name), true)
                source.delete()
            }
        }

        val all = readAll(access)

        all.put(frontendId, plan.settings)
        access.write(all.encode())

        withContext(Dispatchers.IO) {
            plan.deletes.forEach { name ->
                val file = File(folder, name)

                if (file.isFile) file.delete()
            }
        }
    }

    private suspend fun readAll(access: PropertyAccess): JsonObject {
        val text = access.read() ?: return JsonObject()

        return try {
            JsonObject(text)
        } catch (_: Exception) {
            JsonObject()
        }
    }

    companion object {
        /** The system property that holds the settings of every front-end. */
        const val PROPERTY = "theme_settings"

        private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private val EXTENSION = Regex("^[A-Za-z0-9]{1,10}$")

        fun isSafeName(name: String) = SAFE_NAME.matches(name) && !name.contains("..")

        /** The file names in a `files` value (one name or a list), unsafe ones left out. */
        fun namesOf(value: Any?): List<String> = when (value) {
            is String -> if (value.isEmpty()) emptyList() else listOf(value)
            is JsonArray -> value.map { it?.toString().orEmpty() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }.filter { isSafeName(it) }

        /** A fresh name for an upload: a UUID and the extension of the spooled file, as the theme settings always did. */
        fun generatedName(upload: SettingsUpload): String {
            val extension = if (upload.path.contains(".")) upload.path.split(".").last() else ""

            return UUID.randomUUID().toString() + if (EXTENSION.matches(extension)) ".$extension" else ""
        }

        /**
         * Works out what a write stores, without touching a file or the database.
         *
         * - [requested] is the new object of the front-end. Its `files` (field to name or names) are the files it
         *   keeps; the [uploads] are added to them under their field name; `remove-files` names files to delete.
         * - Unless the request is empty, the stored files of a field the request does not mention are kept.
         * - A file the new object no longer lists is deleted.
         * - Names that could leave the folder (`..`, a path) are ignored everywhere.
         */
        fun plan(
            current: JsonObject?,
            requested: JsonObject,
            uploads: List<SettingsUpload> = emptyList(),
            newName: (SettingsUpload) -> String = ::generatedName
        ): SettingsPlan {
            val settings = requested.copy()
            val copies = mutableListOf<Pair<SettingsUpload, String>>()

            var files: JsonObject? = (settings.getValue("files") as? JsonObject)?.let { source ->
                JsonObject().also { out -> source.forEach { (key, value) -> out.put(key, JsonArray(namesOf(value))) } }
            }

            if (uploads.isNotEmpty()) {
                val target = files ?: JsonObject()

                uploads.forEach { upload ->
                    val name = newName(upload)

                    copies.add(upload to name)
                    target.put(upload.fieldName, JsonArray(namesOf(target.getValue(upload.fieldName)) + name))
                }

                files = target
            }

            val removed = namesOf(settings.getValue("remove-files"))

            settings.remove("remove-files")

            val currentFiles = current?.getValue("files") as? JsonObject

            if (currentFiles != null && !(settings.isEmpty && files == null)) {
                val target = files ?: JsonObject()

                currentFiles.forEach { (key, value) ->
                    if (!target.containsKey(key)) target.put(key, JsonArray(namesOf(value)))
                }

                files = target
            }

            if (files != null) {
                for (key in files.fieldNames().toList()) {
                    val kept = namesOf(files.getValue(key)).filter { it !in removed }

                    if (kept.isEmpty()) files.remove(key) else files.put(key, JsonArray(kept))
                }

                settings.put("files", files)
            }

            val finalNames = files?.let { f -> f.fieldNames().flatMap { namesOf(f.getValue(it)) }.toSet() } ?: emptySet()
            val deletes = linkedSetOf<String>()

            deletes.addAll(removed)

            currentFiles?.forEach { (_, value) -> namesOf(value).forEach { if (it !in finalNames) deletes.add(it) } }

            copies.forEach { (_, name) -> deletes.remove(name) }

            return SettingsPlan(settings, copies, deletes.toList())
        }
    }
}

// --- the service ---------------------------------------------------------------

/** What `GET /frontend/settings` and the panel read: who the settings belong to, the schema if there is one, the values. */
class FrontendSettingsView(
    val id: String,
    val mode: FrontendMode,
    val schema: FrontendSettingsSchema?,
    val reading: SettingsReading
)

/**
 * The settings of the active front-end. The schema comes from the front-end itself: `settingsSchema` in the
 * theme's `core-meta.json`, in the custom app's `manifest.json`, or in the cached descriptor of an EXTERNAL / NONE
 * site ([FrontendDescriptor]). Collaborators come through providers so this bean can be injected anywhere.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class FrontendSettings(
    private val configManager: ConfigManager,
    private val uiManager: ObjectProvider<UIManager>,
    private val databaseManager: ObjectProvider<DatabaseManager>,
    private val frontendDescriptor: FrontendDescriptor,
    private val logger: Logger
) {
    private class CachedSchema(val stamp: Long, val schema: FrontendSettingsSchema?)

    private val fileSchemas = ConcurrentHashMap<String, CachedSchema>()

    /** [UIManager.activeFrontendId], after the cached descriptor was read (its `id` is part of the answer for EXTERNAL / NONE). */
    suspend fun activeId(sqlClient: SqlClient): String {
        frontendDescriptor.ensureLoaded(sqlClient)

        return uiManager.getObject().activeFrontendId()
    }

    fun storage(): FrontendSettingsStorage =
        FrontendSettingsStorage(File(configManager.config.fileUploadsFolder, AppConstants.THEME_SETTINS_FILE_UPLOAD_FOLDER))

    fun access(sqlClient: SqlClient): PropertyAccess =
        SystemPropertyAccess(databaseManager.getObject().systemPropertyDao, sqlClient)

    /** The schema of the active front-end; null when it declares no `fields`. */
    suspend fun schema(sqlClient: SqlClient): FrontendSettingsSchema? {
        frontendDescriptor.ensureLoaded(sqlClient)

        val ui = uiManager.getObject()

        return when (ui.frontendMode) {
            FrontendMode.THEME -> ui.activeTheme.takeIf { it.isNotEmpty() }?.let { cachedFile(ui.getThemeFile(it, "core-meta.json")) }

            FrontendMode.CUSTOM_APP -> cachedFile(File(File(ui.customAppsFolder, ui.activeFrontendId()), "manifest.json"))

            FrontendMode.EXTERNAL, FrontendMode.NONE -> frontendDescriptor.cached()?.schema
        }
    }

    private fun cachedFile(file: File?): FrontendSettingsSchema? {
        if (file == null || !file.isFile) return null

        val stamp = file.lastModified() * 31 + file.length()
        val cached = fileSchemas[file.path]

        if (cached != null && cached.stamp == stamp) return cached.schema

        val schema = FrontendSettingsSchema.fromFile(file, logger)

        fileSchemas[file.path] = CachedSchema(stamp, schema)

        return schema
    }

    /** The settings of the active front-end with the defaults filled in. */
    suspend fun read(sqlClient: SqlClient): FrontendSettingsView {
        val id = activeId(sqlClient)
        val schema = schema(sqlClient)
        val stored = storage().load(access(sqlClient), id)

        return FrontendSettingsView(
            id,
            uiManager.getObject().frontendMode,
            schema,
            schema?.read(stored) ?: FrontendSettingsSchema.readRaw(stored)
        )
    }

    /** Validates [request] by the schema of the active front-end and stores it. */
    suspend fun update(request: JsonObject, uploads: List<SettingsUpload>, sqlClient: SqlClient): FrontendSettingsView {
        val id = activeId(sqlClient)
        val schema = schema(sqlClient) ?: throw FrontendSettingsNoSchema()
        val access = access(sqlClient)
        val storage = storage()

        val plan = schema.prepare(storage.load(access, id), request, uploads)

        storage.commit(access, id, plan)

        return FrontendSettingsView(id, uiManager.getObject().frontendMode, schema, schema.read(plan.settings))
    }
}
