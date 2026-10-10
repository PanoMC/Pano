package com.panomc.platform.error

import com.panomc.platform.model.Error

/** The Pano Mail address name is taken by another account. */
class PanoMailNameTaken(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_MAIL_NAME_TAKEN", 409, statusMessage, extras)

/** The website refused the Pano Mail address name as invalid. */
class PanoMailNameInvalid(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_MAIL_NAME_INVALID", 422, statusMessage, extras)

/** The address name was changed too recently; `extras.minutes` / `retryAfter` say when it may change again. */
class PanoMailNameRateLimited(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("PANO_MAIL_NAME_RATE_LIMITED", 429, statusMessage, extras)
