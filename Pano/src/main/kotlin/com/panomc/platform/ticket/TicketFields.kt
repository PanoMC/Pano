package com.panomc.platform.ticket

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Custom fields of ticket categories. Pure: no I/O.
 *
 * A category has an ordered list of field definitions ([parseDefinitions] checks and normalises what the panel
 * sends). When a player opens a ticket, the visible fields ([visibleKeys]) are checked ([validateSubmission]) and a
 * snapshot of their definition and value is stored on the ticket ([snapshot]); staff edit that snapshot later
 * ([applyEdit]). The panel and the theme follow the same rules in JavaScript; the server is the authority.
 */
object TicketFields {
    enum class Type { TEXT, TEXTAREA, NUMBER, EMAIL, URL, DATE, SELECT, MULTISELECT, CHECKBOX }

    enum class Match { ALL, ANY }

    enum class Op { EQUALS, NOT_EQUALS, CONTAINS, FILLED, EMPTY }

    class Option(val value: String, val label: String)

    class Rule(val field: String, val op: Op, val value: String)

    class Conditions(val match: Match, val rules: List<Rule>)

    class Definition(
        val key: String,
        val label: String,
        val type: Type,
        val required: Boolean,
        val placeholder: String,
        val help: String,
        val options: List<Option>,
        val min: Double?,
        val max: Double?,
        val conditions: Conditions?
    )

    const val MAX_FIELDS = 30
    const val MAX_OPTIONS = 50
    const val MAX_RULES = 10
    const val MAX_LABEL = 100
    const val MAX_PLACEHOLDER = 150
    const val MAX_HELP = 300
    const val TEXT_DEFAULT_MAX = 200
    const val TEXTAREA_DEFAULT_MAX = 2000
    const val TEXT_CAP = 500
    const val TEXTAREA_CAP = 5000
    const val EMAIL_MAX = 254
    const val URL_MAX = 500

    private val KEY = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** The types `CONTAINS` searches in as text; for the others it is `EQUALS`. */
    private val TEXT_LIKE = setOf(Type.TEXT, Type.TEXTAREA, Type.EMAIL, Type.URL)

    /** Thrown with the field errors (`fields.<…>` → code) of a request. */
    class Invalid(val errors: Map<String, String>) : RuntimeException("invalid ticket fields")

    // ----- definitions ------------------------------------------------------------------------------------------------

    /**
     * The definitions of [raw] (the panel's `fields` array), normalised; throws [Invalid] with
     * `fields.<index>.<prop>` keys (`REQUIRED`, `INVALID`, `TOO_LONG`, `DUPLICATE`, `TOO_MANY`, `UNKNOWN_FIELD`,
     * `OUT_OF_RANGE`).
     */
    fun parseDefinitions(raw: Any?): List<Definition> {
        if (raw == null) return emptyList()

        val array = raw as? JsonArray ?: throw Invalid(mapOf("fields" to "INVALID"))

        if (array.size() > MAX_FIELDS) throw Invalid(mapOf("fields" to "TOO_MANY"))

        val errors = LinkedHashMap<String, String>()
        val out = ArrayList<Definition>()
        val seen = LinkedHashMap<String, Definition>()

        array.forEachIndexed { index, value ->
            val p = "fields.$index"
            val obj = value as? JsonObject

            if (obj == null) {
                errors[p] = "INVALID"

                return@forEachIndexed
            }

            val key = (obj.getValue("key") as? String)?.trim().orEmpty()

            when {
                key.isEmpty() -> errors["$p.key"] = "REQUIRED"
                !KEY.matches(key) -> errors["$p.key"] = "INVALID"
                key in seen -> errors["$p.key"] = "DUPLICATE"
            }

            val label = (obj.getValue("label") as? String)?.trim().orEmpty()

            when {
                label.isEmpty() -> errors["$p.label"] = "REQUIRED"
                label.length > MAX_LABEL -> errors["$p.label"] = "TOO_LONG"
            }

            val type = (obj.getValue("type") as? String)?.let { name -> Type.entries.firstOrNull { it.name == name } }

            if (type == null) errors["$p.type"] = "INVALID"

            val required = obj.getValue("required").let { if (it is Boolean) it else { if (it != null) errors["$p.required"] = "INVALID"; false } }
            val placeholder = text(obj, "placeholder", MAX_PLACEHOLDER, "$p.placeholder", errors)
            val help = text(obj, "help", MAX_HELP, "$p.help", errors)

            // options
            val options = ArrayList<Option>()

            if (type == Type.SELECT || type == Type.MULTISELECT) {
                val rawOptions = obj.getValue("options") as? JsonArray

                when {
                    rawOptions == null || rawOptions.isEmpty -> errors["$p.options"] = "REQUIRED"
                    rawOptions.size() > MAX_OPTIONS -> errors["$p.options"] = "TOO_MANY"
                    else -> rawOptions.forEachIndexed { o, item ->
                        val option = item as? JsonObject
                        val optionValue = (option?.getValue("value") as? String)?.trim().orEmpty()
                        val optionLabel = (option?.getValue("label") as? String)?.trim().orEmpty()

                        when {
                            optionValue.isEmpty() -> errors["$p.options.$o.value"] = "REQUIRED"
                            !KEY.matches(optionValue) -> errors["$p.options.$o.value"] = "INVALID"
                            options.any { it.value == optionValue } -> errors["$p.options.$o.value"] = "DUPLICATE"
                        }

                        when {
                            optionLabel.isEmpty() -> errors["$p.options.$o.label"] = "REQUIRED"
                            optionLabel.length > MAX_LABEL -> errors["$p.options.$o.label"] = "TOO_LONG"
                        }

                        options += Option(optionValue, optionLabel)
                    }
                }
            }

            // bounds
            var min: Double? = null
            var max: Double? = null

            if (type == Type.NUMBER || type == Type.TEXT || type == Type.TEXTAREA) {
                min = bound(obj.getValue("min"), type, "$p.min", errors)
                max = bound(obj.getValue("max"), type, "$p.max", errors)

                if (min != null && max != null && min > max) errors["$p.max"] = "OUT_OF_RANGE"
            }

            // conditions: only on earlier fields
            val conditions = parseConditions(obj.getValue("conditions"), "$p.conditions", seen, errors)

            if (type != null && key.isNotEmpty()) {
                val definition = Definition(key, label, type, required, placeholder, help, options, min, max, conditions)

                if (key !in seen) seen[key] = definition

                out += definition
            }
        }

        if (errors.isNotEmpty()) throw Invalid(errors)

        return out
    }

    private fun text(obj: JsonObject, key: String, max: Int, field: String, errors: MutableMap<String, String>): String {
        val value = obj.getValue(key) ?: return ""
        val text = (value as? String)?.trim()

        if (text == null) {
            errors[field] = "INVALID"

            return ""
        }

        if (text.length > max) errors[field] = "TOO_LONG"

        return text
    }

    private fun bound(raw: Any?, type: Type, field: String, errors: MutableMap<String, String>): Double? {
        if (raw == null) return null

        val number = (raw as? Number)?.toDouble() ?: (raw as? String)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        if (number == null || number.isNaN() || number.isInfinite()) {
            if (!(raw is String && raw.isBlank())) errors[field] = "INVALID"

            return null
        }

        if (type != Type.NUMBER) {
            val cap = if (type == Type.TEXT) TEXT_CAP else TEXTAREA_CAP

            if (number < 0 || number != Math.floor(number)) errors[field] = "INVALID"
            else if (number > cap) errors[field] = "OUT_OF_RANGE"
        }

        return number
    }

    private fun parseConditions(raw: Any?, p: String, earlier: Map<String, Definition>, errors: MutableMap<String, String>): Conditions? {
        if (raw == null) return null

        val obj = raw as? JsonObject ?: run {
            errors[p] = "INVALID"

            return null
        }

        val match = (obj.getValue("match") as? String)?.let { name -> Match.entries.firstOrNull { it.name == name } }

        if (match == null) errors["$p.match"] = "INVALID"

        val rules = obj.getValue("rules") as? JsonArray

        if (rules == null || rules.isEmpty) {
            // an empty condition set is the same as none
            return null
        }

        if (rules.size() > MAX_RULES) {
            errors["$p.rules"] = "TOO_MANY"

            return null
        }

        val out = ArrayList<Rule>()

        rules.forEachIndexed { r, item ->
            val rule = item as? JsonObject
            val field = (rule?.getValue("field") as? String)?.trim().orEmpty()
            val op = (rule?.getValue("op") as? String)?.let { name -> Op.entries.firstOrNull { it.name == name } }
            val value = (rule?.getValue("value") as? String)?.trim().orEmpty()

            if (field !in earlier) errors["$p.rules.$r.field"] = "UNKNOWN_FIELD"
            if (op == null) errors["$p.rules.$r.op"] = "INVALID"

            if (op == Op.EQUALS || op == Op.NOT_EQUALS || op == Op.CONTAINS) {
                if (value.isEmpty()) errors["$p.rules.$r.value"] = "REQUIRED"
                else if (value.length > MAX_LABEL) errors["$p.rules.$r.value"] = "TOO_LONG"
            }

            if (op != null) out += Rule(field, op, value)
        }

        return match?.let { Conditions(it, out) }
    }

    /** The JSON of [definitions], as stored and as the APIs answer. */
    fun definitionsJson(definitions: List<Definition>): JsonArray = JsonArray(definitions.map { d ->
        JsonObject()
            .put("key", d.key)
            .put("label", d.label)
            .put("type", d.type.name)
            .put("required", d.required)
            .put("placeholder", d.placeholder)
            .put("help", d.help)
            .put("options", optionsJson(d.options))
            .put("min", d.min?.let { number(it) })
            .put("max", d.max?.let { number(it) })
            .put("conditions", d.conditions?.let { c ->
                JsonObject().put("match", c.match.name).put("rules", JsonArray(c.rules.map { rule ->
                    JsonObject().put("field", rule.field).put("op", rule.op.name).put("value", rule.value)
                }))
            })
    })

    private fun optionsJson(options: List<Option>) = JsonArray(options.map { JsonObject().put("value", it.value).put("label", it.label) })

    /** A whole number as a Long, so `5` does not come back as `5.0`. */
    private fun number(value: Double): Any = if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong() else value

    /** The stored definitions (the column) as JSON; an unreadable value is no fields. */
    fun storedDefinitions(json: String?): JsonArray = runCatching { JsonArray(json ?: "[]") }.getOrDefault(JsonArray())

    /** The stored definitions, parsed again (they were checked when saved; a broken row is no fields). */
    fun definitionsOf(json: String?): List<Definition> = runCatching { parseDefinitions(storedDefinitions(json)) }.getOrDefault(emptyList())

    // ----- values -----------------------------------------------------------------------------------------------------

    /** The value of [raw] for [type] after trimming, or `null` when it is empty (see the contract's "empty"). */
    fun normalise(type: Type, raw: Any?): Any? = when (type) {
        Type.CHECKBOX -> (raw as? Boolean)?.takeIf { it }
        Type.MULTISELECT -> (raw as? JsonArray)?.list?.map { it?.toString()?.trim().orEmpty() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            ?: (raw as? List<*>)?.map { it?.toString()?.trim().orEmpty() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
        Type.NUMBER -> when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: it }
            else -> null
        }
        else -> when (raw) {
            is String -> raw.trim().takeIf { it.isNotEmpty() }
            is Number, is Boolean -> raw.toString()
            else -> null
        }
    }

    /** Whether a rule holds for [value] (already normalised) of the field of [type]. */
    private fun holds(rule: Rule, type: Type?, value: Any?): Boolean {
        fun equalsValue(): Boolean {
            if (value == null) return type == Type.CHECKBOX && rule.value == "false"

            return when (type) {
                Type.NUMBER -> (value as? Double)?.let { it == rule.value.toDoubleOrNull() } ?: false
                Type.MULTISELECT -> (value as List<*>).contains(rule.value)
                Type.CHECKBOX -> value.toString() == rule.value
                else -> value.toString().equals(rule.value, ignoreCase = true)
            }
        }

        return when (rule.op) {
            Op.FILLED -> value != null
            Op.EMPTY -> value == null
            Op.EQUALS -> equalsValue()
            Op.NOT_EQUALS -> !equalsValue()
            Op.CONTAINS -> when {
                value == null -> false
                type == Type.MULTISELECT -> (value as List<*>).contains(rule.value)
                type in TEXT_LIKE -> value.toString().contains(rule.value, ignoreCase = true)
                else -> equalsValue()
            }
        }
    }

    /** The keys of the visible fields, in order, for the [values] (normalised) the player entered. */
    fun visibleKeys(definitions: List<Definition>, values: Map<String, Any?>): Set<String> {
        val visible = LinkedHashSet<String>()
        val types = definitions.associate { it.key to it.type }

        for (definition in definitions) {
            val conditions = definition.conditions

            val shown = conditions == null || conditions.rules.isEmpty() || run {
                // a field that is not visible counts as empty
                val results = conditions.rules.map { rule -> holds(rule, types[rule.field], if (rule.field in visible) values[rule.field] else null) }

                if (conditions.match == Match.ALL) results.all { it } else results.any { it }
            }

            if (shown) visible += definition.key
        }

        return visible
    }

    /** The error code of [value] (normalised, not empty) for [definition], or `null` (the contract's section 2). */
    fun valueError(definition: Definition, value: Any): String? = when (definition.type) {
        Type.TEXT, Type.TEXTAREA -> {
            val text = value as? String ?: return "INVALID"
            val max = definition.max?.toInt() ?: if (definition.type == Type.TEXT) TEXT_DEFAULT_MAX else TEXTAREA_DEFAULT_MAX

            when {
                definition.min != null && text.length < definition.min -> "TOO_SHORT"
                text.length > max -> "TOO_LONG"
                else -> null
            }
        }

        Type.EMAIL -> when {
            value !is String -> "INVALID"
            value.length > EMAIL_MAX -> "TOO_LONG"
            !EMAIL.matches(value) -> "INVALID"
            else -> null
        }

        Type.URL -> when {
            value !is String -> "INVALID"
            value.length > URL_MAX -> "TOO_LONG"
            !(value.startsWith("http://", true) || value.startsWith("https://", true)) -> "INVALID"
            runCatching { java.net.URI(value).host }.getOrNull().isNullOrBlank() -> "INVALID"
            else -> null
        }

        Type.DATE -> if (value is String && runCatching { LocalDate.parse(value, DATE) }.isSuccess) null else "INVALID"

        Type.NUMBER -> when {
            value !is Double || value.isNaN() || value.isInfinite() -> "INVALID"
            definition.min != null && value < definition.min -> "OUT_OF_RANGE"
            definition.max != null && value > definition.max -> "OUT_OF_RANGE"
            else -> null
        }

        Type.SELECT -> if (definition.options.any { it.value == value }) null else "UNKNOWN_OPTION"

        Type.MULTISELECT -> {
            val list = value as? List<*> ?: return "INVALID"

            when {
                list.size != list.toSet().size -> "INVALID"
                list.any { item -> definition.options.none { it.value == item } } -> "UNKNOWN_OPTION"
                else -> null
            }
        }

        Type.CHECKBOX -> if (value == true) null else "INVALID"
    }

    /**
     * The values a player submits for [definitions]: only the visible ones are kept, the required visible ones must
     * be there and every value must fit its field. Throws [Invalid] with `fields.<key>` keys. Answers the normalised
     * values of the visible fields (empty ones as `null`), in definition order.
     */
    fun validateSubmission(definitions: List<Definition>, raw: JsonObject?): LinkedHashMap<String, Any?> {
        val values = definitions.associate { it.key to normalise(it.type, raw?.getValue(it.key)) }
        val visible = visibleKeys(definitions, values)
        val errors = LinkedHashMap<String, String>()
        val out = LinkedHashMap<String, Any?>()

        for (definition in definitions) {
            if (definition.key !in visible) continue

            val value = values[definition.key]

            if (value == null) {
                if (definition.required) errors["fields.${definition.key}"] = "REQUIRED"
            } else {
                valueError(definition, value)?.let { errors["fields.${definition.key}"] = it }
            }

            out[definition.key] = value
        }

        if (errors.isNotEmpty()) throw Invalid(errors)

        return out
    }

    // ----- snapshot ---------------------------------------------------------------------------------------------------

    /** The readable value: option labels, the number or text as is, `true` / `false`, `""` when empty. */
    fun display(type: Type, options: List<Option>, value: Any?): String = when {
        value == null -> ""
        type == Type.SELECT -> options.firstOrNull { it.value == value }?.label ?: value.toString()
        type == Type.MULTISELECT -> (value as List<*>).joinToString(", ") { item -> options.firstOrNull { it.value == item }?.label ?: item.toString() }
        type == Type.NUMBER -> number(value as Double).toString()
        else -> value.toString()
    }

    private fun valueJson(type: Type, value: Any?): Any? = when {
        value == null -> null
        type == Type.MULTISELECT -> JsonArray((value as List<*>).toList())
        type == Type.NUMBER -> number(value as Double)
        else -> value
    }

    /** The snapshot stored on a ticket: every visible field with its definition and value. */
    fun snapshot(definitions: List<Definition>, values: Map<String, Any?>): JsonArray = JsonArray(
        definitions.filter { it.key in values }.map { d ->
            val value = values[d.key]

            JsonObject()
                .put("key", d.key)
                .put("label", d.label)
                .put("type", d.type.name)
                .put("options", optionsJson(d.options))
                .put("min", d.min?.let { number(it) })
                .put("max", d.max?.let { number(it) })
                .put("value", valueJson(d.type, value))
                .put("display", display(d.type, d.options, value))
        }
    )

    /** The stored snapshot as JSON; an unreadable value is no fields. */
    fun storedSnapshot(json: String?): JsonArray = runCatching { JsonArray(json ?: "[]") }.getOrDefault(JsonArray())

    /** A snapshot entry as a definition (for checking an edit against it). */
    private fun entryDefinition(entry: JsonObject): Definition? {
        val type = (entry.getValue("type") as? String)?.let { name -> Type.entries.firstOrNull { it.name == name } } ?: return null
        val options = ((entry.getValue("options") as? JsonArray) ?: JsonArray()).mapNotNull { item ->
            (item as? JsonObject)?.let { Option(it.getString("value").orEmpty(), it.getString("label").orEmpty()) }
        }

        return Definition(
            entry.getString("key").orEmpty(), entry.getString("label").orEmpty(), type, false, "", "", options,
            (entry.getValue("min") as? Number)?.toDouble(), (entry.getValue("max") as? Number)?.toDouble(), null
        )
    }

    /**
     * A staff edit of [stored] (the snapshot): every key of [raw] that is in the snapshot gets its new value, checked
     * by the entry's type, options and bounds (not `required`, not conditions). Throws [Invalid] with `fields.<key>`.
     * Answers the new snapshot and whether anything changed.
     */
    fun applyEdit(stored: JsonArray, raw: JsonObject): Pair<JsonArray, Boolean> {
        val errors = LinkedHashMap<String, String>()
        val out = JsonArray()
        var changed = false

        for (item in stored) {
            val entry = (item as? JsonObject)?.copy() ?: continue
            val key = entry.getString("key").orEmpty()

            if (!raw.containsKey(key)) {
                out.add(entry)
                continue
            }

            val definition = entryDefinition(entry)

            if (definition == null) {
                out.add(entry)
                continue
            }

            val value = normalise(definition.type, raw.getValue(key))

            value?.let { v -> valueError(definition, v)?.let { errors["fields.$key"] = it } }

            val json = valueJson(definition.type, value)

            if (json != entry.getValue("value")) changed = true

            entry.put("value", json).put("display", display(definition.type, definition.options, value))
            out.add(entry)
        }

        if (errors.isNotEmpty()) throw Invalid(errors)

        return out to changed
    }

    /** The readable values of [snapshot], one per line: what the panel's ticket search looks in. */
    fun searchText(snapshot: JsonArray): String =
        snapshot.mapNotNull { (it as? JsonObject)?.getString("display")?.takeIf { text -> text.isNotEmpty() } }.joinToString("\n")

    /** `{ key: display }` of the entries with a value: the `fields` of the `core.ticket.created` webhook. */
    fun webhookValues(snapshot: JsonArray): JsonObject {
        val out = JsonObject()

        for (item in snapshot) {
            val entry = item as? JsonObject ?: continue
            val display = entry.getString("display").orEmpty()

            if (display.isNotEmpty()) out.put(entry.getString("key"), display)
        }

        return out
    }
}
