package com.panomc.platform.notification

/**
 * Placeholder for a stored notification whose type is no longer known (plugin removed or not loaded yet).
 * Keeps the original name so the row can still be listed, marked read and deleted.
 */
class UnknownNotificationType(private val typeName: String) : NotificationType {
    override fun getName() = typeName
}
