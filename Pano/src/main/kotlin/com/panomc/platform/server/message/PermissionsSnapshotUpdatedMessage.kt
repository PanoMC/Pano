package com.panomc.platform.server.message

import com.panomc.platform.server.PlatformMessage

/**
 * Broadcast message sent to servers to indicate the permission snapshot has been updated on the platform.
 * Servers that have permission integration enabled should re-sync from the platform.
 */
data class PermissionsSnapshotUpdatedMessage(
    val updatedAt: Long = System.currentTimeMillis()
) : PlatformMessage



