package com.panomc.platform.route

import com.panomc.platform.api.ErrorStandIn
import com.panomc.platform.db.model.Notification
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.model.CursorPaging
import com.panomc.platform.notification.NotificationType
import com.panomc.platform.route.api.notification.NotificationPage
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.ui.CustomAppActive
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The last public list bodies before the freeze (CX-04): the notification cursor form and its rule. */
class PublicSurfaceTest {
    private class TestNotification : NotificationType

    private fun notification(id: Long) = Notification(id = id, userId = 1, type = TestNotification())

    private fun wire(body: Map<String, Any?>) = JsonObject(JsonObject(body).encode())

    private val doc = EndpointDoc("x", response = NotificationPage.schema)

    @Test
    fun `a notification list answers items, a cursor page and the unread count`() {
        val body = NotificationPage.payload(listOf(notification(9), notification(8)), 1, { "plugin" }, 10, true, 4L)

        assertEquals(listOf("items", "page", "notificationCount"), body.keys.toList())
        assertEquals(mapOf<String, Any?>("size" to 10, "nextCursor" to "8"), body["page"])
        assertEquals(4L, body["notificationCount"])
        assertEquals(listOf<String>(), doc.check(wire(body)))

        val first = (body["items"] as List<*>)[0] as Map<*, *>

        assertEquals(9L, first["id"])
        assertEquals("plugin", first["pluginId"])
        assertEquals(true, first["isPersonal"])
    }

    @Test
    fun `the last notification page has a null cursor and still matches the doc`() {
        val body = NotificationPage.payload(listOf(notification(3)), 1, { null }, 10, false, 0L)

        assertNull((body["page"] as Map<*, *>)["nextCursor"])
        assertEquals(listOf<String>(), doc.check(wire(body)))
        assertNull((NotificationPage.payload(listOf(), 1, { null }, 5, true, 0L)["page"] as Map<*, *>)["nextCursor"])
    }

    @Test
    fun `the old notifications key is gone and a body without a page is refused by the doc`() {
        val old = JsonObject().put("notifications", io.vertx.core.json.JsonArray()).put("notificationCount", 0)

        assertEquals(false, doc.check(old).isEmpty())
    }

    @Test
    fun `a cursor exists only when a row beyond the limit was read`() {
        val rows = (10L downTo 1L).toList()

        assertEquals(rows.take(3) to "8", CursorPaging.split(rows.take(4), 3) { it })
        assertEquals(rows.take(3) to null, CursorPaging.split(rows.take(3), 3) { it })
        assertEquals(listOf<Long>() to null, CursorPaging.split(listOf<Long>(), 3) { it })
    }

    @Test
    fun `the cursor helpers live in the model package and refuse a bad limit`() {
        assertEquals("com.panomc.platform.model", CursorPaging::class.java.packageName)
        assertEquals(10, CursorPaging.limit(null, 10, 50))

        val failure = assertThrows(InvalidFields::class.java) { CursorPaging.limit("0", 10, 50) }

        assertNotNull(failure)
    }

    @Test
    fun `an error whose constructor needs arguments is built with stand-ins`() {
        val error = ErrorStandIn.create(CustomAppActive::class.java)

        assertNotNull(error)
        assertEquals("CUSTOM_APP_ACTIVE", error!!.code)
    }
}
