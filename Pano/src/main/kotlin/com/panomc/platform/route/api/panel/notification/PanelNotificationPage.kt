package com.panomc.platform.route.api.panel.notification

import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.model.CursorPaging

/**
 * The page shape of the three panel notification lists (doc 04 section 4, cursor variant): the rows as
 * `items`, `page: { size, nextCursor }` and the badge counts beside them. The cursor is the id of the last
 * row shown, which `GET /notifications/:id/more` takes as its path parameter.
 */
object PanelNotificationPage {
    /** Rows one page holds (the DAO reads that many). */
    const val SIZE = 10

    /** The rows of the small list in the navbar. */
    const val QUICK_SIZE = 5

    /** One row, as the panel reads it. */
    fun item(notification: PanelNotification, userId: Long, ownerOf: (String) -> String?): Map<String, Any?> = mapOf(
        "id" to notification.id,
        "type" to notification.type.getName(),
        "pluginId" to ownerOf(notification.type.getName()),
        "details" to notification.details.map,
        "status" to notification.status.name,
        "isPersonal" to (notification.userId == userId),
        "createdAt" to notification.createdAt,
        "updatedAt" to notification.updatedAt,
    )

    /**
     * The response body. [hasMore] says an older row exists; the cursor is then the id of the last row shown.
     * [extra] holds the counts the navbar badge reads.
     */
    fun payload(
        notifications: List<PanelNotification>,
        userId: Long,
        ownerOf: (String) -> String?,
        size: Int,
        hasMore: Boolean,
        extra: Map<String, Any?>
    ): Map<String, Any?> = CursorPaging.response(
        notifications.map { item(it, userId, ownerOf) },
        size,
        if (hasMore) notifications.lastOrNull()?.id?.toString() else null,
        extra
    )
}
