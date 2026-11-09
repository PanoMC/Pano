package com.panomc.platform.route.api.panel.players

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PanelBanIpAPITest {
    @Test
    fun `address literals are accepted`() {
        assertEquals("1.2.3.4", PanelBanIpAPI.parseIpLiteral(" 1.2.3.4 "))
        assertEquals("0:0:0:0:0:0:0:1", PanelBanIpAPI.parseIpLiteral("::1"))
        assertEquals("2001:db8:0:0:0:0:0:1", PanelBanIpAPI.parseIpLiteral("2001:db8::1"))
    }

    @Test
    fun `host names are refused without a lookup`() {
        assertNull(PanelBanIpAPI.parseIpLiteral("localhost"))
        assertNull(PanelBanIpAPI.parseIpLiteral("example.com"))
        assertNull(PanelBanIpAPI.parseIpLiteral("1.2.3"))
        assertNull(PanelBanIpAPI.parseIpLiteral("256.1.1.1"))
        assertNull(PanelBanIpAPI.parseIpLiteral("fe80::1%eth0"))
        assertNull(PanelBanIpAPI.parseIpLiteral(""))
    }
}
