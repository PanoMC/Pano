package com.panomc.platform.error

import com.panomc.platform.model.Error

/**
 * A console grant's deny policy refused this command (§2.4.12).
 *
 * 403 rather than 400: the request is well formed and the caller does hold console permission,
 * they are simply not allowed to run *this*. `extras` carries the `pattern` that matched, because
 * the panel shows it — a refusal that only says "no" leaves an admin guessing which entry of
 * their own policy they ran into.
 */
class CommandDenied(
    statusMessage: String = "",
    extras: Map<String, Any?> = mapOf()
) : Error(403, statusMessage, extras)
