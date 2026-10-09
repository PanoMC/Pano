package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity

/**
 * A key that identifies one front-end **server** to the API (open front-end plan, doc 05 §3).
 *
 * Only the SHA-256 hex of the key is stored ([keyHash]); the key itself is shown once, when it is
 * created. [keyHint] is its last four characters, so the panel can tell two keys apart.
 * [lastUsedAt] is `null` until the first request carries the key.
 */
data class FrontendKey(
    val id: Long = -1,
    val name: String,
    val keyHash: String,
    val keyHint: String,
    val createdBy: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
) : DBEntity()
