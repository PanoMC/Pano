package com.panomc.platform.error

import com.panomc.platform.model.Error

/** An e-mail was asked for but e-mail sending is turned off in the platform settings. */
class MailDisabled(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error("MAIL_DISABLED", 409, statusMessage, extras)
