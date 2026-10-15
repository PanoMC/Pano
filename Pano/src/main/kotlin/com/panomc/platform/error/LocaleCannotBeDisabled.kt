package com.panomc.platform.error

import com.panomc.platform.model.Error

/** The language is the one Pano falls back to (English) or the website's own language: it stays on. */
class LocaleCannotBeDisabled(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("LOCALE_CANNOT_BE_DISABLED", 422, statusMessage, extras)
