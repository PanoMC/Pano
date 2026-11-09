package com.panomc.platform.route.api.panel.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PanelUploadResourceFileAPITest {
    @Test
    fun `plain theme and plugin names are kept`() {
        assertEquals("vanilla-theme-1.2.0.zip", PanelUploadResourceFileAPI.safeFileName("vanilla-theme-1.2.0.zip"))
        assertEquals("pano-plugin-faq.jar", PanelUploadResourceFileAPI.safeFileName("pano-plugin-faq.jar"))
    }

    @Test
    fun `a path keeps only its last segment`() {
        assertEquals("evil.jar", PanelUploadResourceFileAPI.safeFileName("../plugins/evil.jar"))
        assertEquals("evil.zip", PanelUploadResourceFileAPI.safeFileName("..\\..\\themes\\evil.zip"))
        assertEquals("x.zip", PanelUploadResourceFileAPI.safeFileName("C:/Windows/x.zip"))
    }

    @Test
    fun `other names are refused`() {
        assertNull(PanelUploadResourceFileAPI.safeFileName(null))
        assertNull(PanelUploadResourceFileAPI.safeFileName(""))
        assertNull(PanelUploadResourceFileAPI.safeFileName(".."))
        assertNull(PanelUploadResourceFileAPI.safeFileName("../"))
        assertNull(PanelUploadResourceFileAPI.safeFileName("config.conf"))
        assertNull(PanelUploadResourceFileAPI.safeFileName("x.zip:stream"))
        assertNull(PanelUploadResourceFileAPI.safeFileName("a\u0000.zip"))
    }
}
