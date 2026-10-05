package com.panomc.platform.notification

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.notification.type.panel.NewTicketNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P-5.2 */
class NotificationGsonTest {
    private val registry = NotificationTypeRegistry { listOf(NewTicketNotification()) }

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(NotificationType::class.java, NotificationTypeDeserializer(registry))
        .create()

    @Test
    fun unknownTypeRoundTripDoesNotThrow() {
        val json = """{"type":"GONE_PLUGIN_THING","details":{}}"""
        val holder = gson.fromJson(json, Holder::class.java)

        assertTrue(holder.type is UnknownNotificationType)
        assertEquals("GONE_PLUGIN_THING", holder.type.getName())
    }

    @Test
    fun knownTypeStillResolves() {
        val holder = gson.fromJson("""{"type":"NEW_TICKET"}""", Holder::class.java)

        assertTrue(holder.type is NewTicketNotification)
    }

    @Test
    fun panelNotificationRowWithUnknownTypeIsReadable() {
        val json = """{"id":1,"userId":2,"type":"GONE","status":"NOT_READ"}"""
        val row = gson.fromJson(json, PanelNotification::class.java)

        assertEquals("GONE", row.type.getName())
    }

    private class Holder(val type: NotificationType)
}
