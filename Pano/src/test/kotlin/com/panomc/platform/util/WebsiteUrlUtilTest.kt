package com.panomc.platform.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebsiteUrlUtilTest {

    @Test
    fun `normalize drops default http port`() {
        assertEquals("http://tealmc.com", WebsiteUrlUtil.normalize("http://tealmc.com:80"))
    }

    @Test
    fun `normalize drops default https port`() {
        assertEquals("https://tealmc.com", WebsiteUrlUtil.normalize("https://tealmc.com:443"))
    }

    @Test
    fun `normalize keeps non-default port`() {
        assertEquals("http://tealmc.com:8090", WebsiteUrlUtil.normalize("http://tealmc.com:8090"))
    }

    @Test
    fun `normalize prepends https to bare host`() {
        assertEquals("https://tealmc.com", WebsiteUrlUtil.normalize("tealmc.com"))
    }

    @Test
    fun `normalize keeps non-default port on bare host`() {
        assertEquals("https://tealmc.com:8090", WebsiteUrlUtil.normalize("tealmc.com:8090"))
    }

    @Test
    fun `normalize trims whitespace and trailing slash`() {
        assertEquals("https://tealmc.com", WebsiteUrlUtil.normalize("  https://tealmc.com/  "))
    }

    @Test
    fun `normalize lowercases scheme and host`() {
        assertEquals("https://tealmc.com", WebsiteUrlUtil.normalize("HTTPS://TealMC.Com"))
    }

    @Test
    fun `normalize keeps path without trailing slash`() {
        assertEquals("https://tealmc.com/forum", WebsiteUrlUtil.normalize("https://tealmc.com/forum/"))
    }

    @Test
    fun `normalize keeps ipv6 with non-default port`() {
        assertEquals("https://[::1]:8090", WebsiteUrlUtil.normalize("https://[::1]:8090"))
    }

    @Test
    fun `normalize drops default port from ipv6`() {
        assertEquals("http://[::1]", WebsiteUrlUtil.normalize("http://[::1]:80"))
    }

    @Test
    fun `normalize returns blank for blank`() {
        assertEquals("", WebsiteUrlUtil.normalize("   "))
    }

    @Test
    fun `normalize falls back to trimmed input on garbage`() {
        assertEquals("http://exa mple", WebsiteUrlUtil.normalize(" http://exa mple/ "))
    }

    @Test
    fun `explicitPort returns port when present`() {
        assertEquals(8090, WebsiteUrlUtil.explicitPort("http://tealmc.com:8090"))
    }

    @Test
    fun `explicitPort returns null without port`() {
        assertNull(WebsiteUrlUtil.explicitPort("http://tealmc.com"))
    }

    @Test
    fun `explicitPort handles bare host with port`() {
        assertEquals(8090, WebsiteUrlUtil.explicitPort("tealmc.com:8090"))
    }

    @Test
    fun `host returns hostname without port`() {
        assertEquals("tealmc.com", WebsiteUrlUtil.host("http://tealmc.com:8090/path"))
    }

    @Test
    fun `host returns null for unparseable input`() {
        assertNull(WebsiteUrlUtil.host("http://exa mple"))
    }

    @Test
    fun `stripPort strips port and keeps ipv6 brackets`() {
        assertEquals("tealmc.com", WebsiteUrlUtil.stripPort("tealmc.com:8090"))
        assertEquals("[::1]", WebsiteUrlUtil.stripPort("[::1]:8080"))
        assertEquals("tealmc.com", WebsiteUrlUtil.stripPort("tealmc.com"))
    }
}
