package com.panomc.platform.route

import com.panomc.platform.db.model.Locale
import com.panomc.platform.route.api.GetLocalesAPI
import com.panomc.platform.route.api.panel.install.PanelGetInstallResourceLocalStreamAPI
import com.panomc.platform.route.api.panel.theme.PanelGetThemesAPI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FX-03: the route-walk fixes that can be tested without a running Pano. */
class RouteWalkFixesTest {
    @Test
    fun `locales body is items only`() {
        val locales = listOf(Locale(code = "en-US", name = "English", dateFnsCode = "enUS", derivatives = listOf("en")))

        val body = GetLocalesAPI.payload(locales)

        assertEquals(setOf("items"), body.keys)
        assertSame(locales, body["items"])
    }

    @Test
    fun `locales body of an empty table keeps the items key`() {
        assertEquals(mapOf<String, Any?>("items" to emptyList<Locale>()), GetLocalesAPI.payload(emptyList()))
    }

    @Test
    fun `themes body moves the list from data to items`() {
        val items = listOf(mapOf<String, Any?>("id" to "vanilla-theme", "active" to true))

        val body = PanelGetThemesAPI.payload(items)

        assertEquals(setOf("items"), body.keys)
        assertFalse(body.containsKey("data"))
        assertSame(items, body["items"])
    }

    @Test
    fun `a failure before the stream started is left to the JSON envelope`() {
        assertFalse(PanelGetInstallResourceLocalStreamAPI.shouldSendAsEvent(headWritten = false))
    }

    @Test
    fun `a failure after the stream started goes out as an event`() {
        assertTrue(PanelGetInstallResourceLocalStreamAPI.shouldSendAsEvent(headWritten = true))
    }
}
