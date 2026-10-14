package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * Validation failed on named fields (HTTP 400): `error.fields` maps each field name to its code. [extras] with the
 * key `message` becomes `error.message`, the rest `error.details`.
 */
class InvalidFields private constructor(
    fields: Map<String, Any?>,
    extras: Map<String, Any?>,
    @Suppress("UNUSED_PARAMETER") marker: Unit
) : Error("INVALID_FIELDS", 400, extras = extras, fields = fields) {
    // Plugins link against InvalidFields(), InvalidFields(Map) and its default bridge: kept as they were.
    @JvmOverloads
    constructor(fields: Map<String, Any?> = mapOf()) : this(fields, mapOf(), Unit)

    constructor(fields: Map<String, Any?>, extras: Map<String, Any?>) : this(fields, extras, Unit)
}
