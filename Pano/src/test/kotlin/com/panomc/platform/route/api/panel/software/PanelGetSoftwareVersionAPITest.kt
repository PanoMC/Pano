package com.panomc.platform.route.api.panel.software

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The `java` range the wizard reads to name the automatic Java choice (SM-63, §2.4.28). */
class PanelGetSoftwareVersionAPITest {
    @Test
    fun `modern versions have a minimum and no ceiling`() {
        val range = PanelGetSoftwareVersionAPI.javaRange("1.21.4", 21)

        assertEquals(21, range.getInteger("minimum"))
        assertNull(range.getValue("maximum"))

        assertEquals(25, PanelGetSoftwareVersionAPI.javaRange("26.1", null).getInteger("minimum"))
    }

    @Test
    fun `old versions are capped`() {
        val range = PanelGetSoftwareVersionAPI.javaRange("1.12.2", 8)

        assertEquals(8, range.getInteger("minimum"))
        assertEquals(16, range.getInteger("maximum"))
    }

    @Test
    fun `a provider that asks for more raises the minimum`() {
        assertEquals(21, PanelGetSoftwareVersionAPI.javaRange("1.20.4", 21).getInteger("minimum"))
        assertEquals(17, PanelGetSoftwareVersionAPI.javaRange("1.20.4", 8).getInteger("minimum"))
    }
}
