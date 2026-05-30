package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A short-lived handle to "I've already identified the user; please complete the auth lifecycle
 * (run the remaining hooks and issue the session)." Created by any flow that authenticates a user
 * out-of-band — social OAuth callback, magic link, SAML, etc. — and consumed by
 * `POST /api/auth/complete-pending`, which dispatches the standard `onBeforeLogin` pipeline so
 * cross-cutting plugins (2FA, …) apply uniformly.
 *
 * `source` is an opaque string the creator passes through (e.g. "social-login:google"). The core
 * never interprets it; it's available to listeners and UI for context only.
 */
data class PendingAuthSession(
    val id: Long = -1,
    val token: String,
    val userId: Long,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long
) : DBEntity()
