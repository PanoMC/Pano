package com.panomc.platform.util

import com.panomc.platform.db.model.User

object BanUtil {
    /**
     * Checks if a user is currently banned.
     *
     * @param bannedUntil The timestamp until which the user is banned (in milliseconds).
     *                    If null, the user is not banned.
     * @return true if the user is currently banned, false otherwise
     */
    private fun isBannedByUntil(bannedUntil: Long?): Boolean {
        return if (bannedUntil == null) {
            true // Not banned if bannedUntil is null
        } else {
            System.currentTimeMillis() < bannedUntil // Banned if bannedUntil is in the future
        }
    }

    /**
     * Checks if a user is currently banned by checking both banned flag and bannedUntil timestamp.
     *
     * @param user The user object to check
     * @return true if the user is currently banned, false otherwise
     */
        fun isBanned(user: User): Boolean {
        // If user is not banned, return false
        if (!user.banned) {
            return false
        }

        // If user has a temporary ban, check if it's still active
        return isBannedByUntil(user.bannedUntil)
    }
}
