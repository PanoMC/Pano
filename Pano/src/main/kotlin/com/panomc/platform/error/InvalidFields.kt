package com.panomc.platform.error

import com.panomc.platform.model.Error

/** Validation failed on named fields (HTTP 400): `error.fields` maps each field name to its code. */
class InvalidFields(
    fields: Map<String, Any?> = mapOf()
) : Error("INVALID_FIELDS", 400, fields = fields)
