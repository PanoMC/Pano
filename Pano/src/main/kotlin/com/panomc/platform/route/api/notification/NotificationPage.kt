package com.panomc.platform.route.api.notification

import com.panomc.platform.db.model.Notification
import com.panomc.platform.model.CursorPaging
import com.panomc.platform.schema.CoreSchemas
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * The page shape of the two public notification lists (doc 04 section 4, cursor variant):
 * `{ items, page: { size, nextCursor }, notificationCount }`. The cursor is the id of the last row shown, which
 * `GET /notifications/:id/more` takes as its path parameter; it is null when no older row exists.
 */
object NotificationPage {
    /** Rows one `more` page holds (the DAO reads that many). */
    const val SIZE = 10

    /** The rows of the small list in the navbar. */
    const val QUICK_SIZE = 5

    /** One row, as the public API writes it. */
    fun item(notification: Notification, userId: Long, ownerOf: (String) -> String?): Map<String, Any?> = mapOf(
        "id" to notification.id,
        "type" to notification.type.getName(),
        "pluginId" to ownerOf(notification.type.getName()),
        "details" to notification.details.map,
        "status" to notification.status,
        "isPersonal" to (notification.userId == userId),
        "createdAt" to notification.createdAt,
        "updatedAt" to notification.updatedAt,
    )

    /** The response body. [hasMore] says an older row exists; the cursor is then the id of the last row shown. */
    fun payload(
        notifications: List<Notification>,
        userId: Long,
        ownerOf: (String) -> String?,
        size: Int,
        hasMore: Boolean,
        notificationCount: Long
    ): Map<String, Any?> = CursorPaging.response(
        notifications.map { item(it, userId, ownerOf) },
        size,
        if (hasMore) notifications.lastOrNull()?.id?.toString() else null,
        mapOf("notificationCount" to notificationCount)
    )

    /** The documented body of both lists. */
    val schema: ObjectSchemaBuilder
        get() = objectSchema()
            .requiredProperty("items", arraySchema().items(CoreSchemas.notification))
            .requiredProperty("page", CoreSchemas.cursorPage)
            .requiredProperty("notificationCount", intSchema())
}
