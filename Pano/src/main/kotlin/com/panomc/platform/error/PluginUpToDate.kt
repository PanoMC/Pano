package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * There is nothing newer to install than what is already there.
 *
 * A 400 rather than a quiet success: the panel only offers the button when it has been told an
 * update exists, so reaching this means the page is looking at an answer that has since changed —
 * somebody else updated it, or the source withdrew the release — and the honest thing is to say
 * so rather than to start a task that reinstalls the same file.
 */
class PluginUpToDate(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(400, statusMessage, extras)
