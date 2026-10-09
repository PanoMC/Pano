package com.panomc.platform.error

import com.panomc.platform.model.Error

/** The file is bigger than this operation allows. */
class FileTooLarge(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("FILE_TOO_LARGE", 400, statusMessage, extras)
