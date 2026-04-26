package com.panomc.platform.db.dao

/**
 * [ACTIVE] — permanent or not yet expired.
 * [HISTORY] — time-limited bans that have already expired.
 */
enum class BannedIpListFilter {
    ACTIVE,
    HISTORY
}
