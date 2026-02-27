package com.panomc.platform.api.event

import com.panomc.platform.db.model.User

interface ProfilePictureEventListener : PanoEventListener {
    /**
     * Resolves the profile picture URL for the given user.
     * Return a URL string to override the default profile picture,
     * or null to fall back to the default behavior.
     */
    suspend fun resolveProfilePictureUrl(user: User): String?
}
