package com.panomc.platform.hosted

import com.panomc.platform.hosted.PanoHostManager.Companion.websiteOriginOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PanoWebsiteOriginTest {
    @Test
    fun `takes the website of each environment from the manage url`() {
        assertEquals("https://panomc.com", websiteOriginOf("https://panomc.com/host/manage/instances/p-aaaaaaaaaa"))
        assertEquals("https://dev.panomc.com", websiteOriginOf(" https://dev.panomc.com/host/manage/instances/p-aaaaaaaaaa "))
        assertEquals(
            "https://local.panomc.com:3003",
            websiteOriginOf("https://local.panomc.com:3003/host/manage/instances/p-aaaaaaaaaa?x=1")
        )
        assertEquals("http://localhost:3003", websiteOriginOf("HTTP://localhost:3003/host"))
    }

    @Test
    fun `refuses anything that is not a plain http url`() {
        assertNull(websiteOriginOf(null))
        assertNull(websiteOriginOf(""))
        assertNull(websiteOriginOf("javascript:alert(1)"))
        assertNull(websiteOriginOf("/host/manage/instances/p-aaaaaaaaaa"))
        assertNull(websiteOriginOf("https://panomc.com@evil.example/host"))
        assertNull(websiteOriginOf("https://exa mple.com/host"))
    }
}
