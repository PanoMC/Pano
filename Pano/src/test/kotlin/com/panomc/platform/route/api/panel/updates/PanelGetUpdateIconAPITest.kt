package com.panomc.platform.route.api.panel.updates

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PanelGetUpdateIconAPITest {
    @Test
    fun `stored icon names are accepted`() {
        assertTrue(PanelGetUpdateIconAPI.isValidIconFileName(UUID.randomUUID().toString()))
    }

    @Test
    fun `paths that leave the icon folder are refused`() {
        listOf(
            "../../config.conf",
            "..\\..\\config.conf",
            "../${UUID.randomUUID()}",
            "config.conf",
            "",
            "..",
            "${UUID.randomUUID()}/../x"
        ).forEach { assertFalse(PanelGetUpdateIconAPI.isValidIconFileName(it), it) }
    }
}
