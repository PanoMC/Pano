package com.panomc.platform.route.api.panel.server

import com.panomc.platform.route.api.panel.server.PanelGetServerIconAPI.Companion.decode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Base64

class PanelGetServerIconAPITest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test
    fun `decodes a base64 raster data url`() {
        val icon = decode("data:image/png;base64," + Base64.getEncoder().encodeToString(png))!!

        assertEquals("image/png", icon.first)
        assertArrayEquals(png, icon.second)
    }

    @Test
    fun `refuses what is not a plain raster icon`() {
        assertNull(decode(null))
        assertNull(decode(""))
        assertNull(decode("data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(png)))
        assertNull(decode("data:image/png;utf8,abc"))
        assertNull(decode("data:image/png;base64,***"))
        assertNull(decode("data:image/png;base64,"))
        assertNull(decode("https://example.com/icon.png"))
    }
}
