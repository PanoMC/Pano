package com.panomc.platform.webhook.guard

import com.panomc.platform.webhook.guard.UrlGuard.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** The pure part of the SSRF guard (11 section 7.2 steps 1 to 9). */
class UrlGuardTest {
    private fun reason(url: String, allowPrivate: Boolean = false, discord: Boolean = false): Reason? =
        UrlGuard.validate(url, allowPrivate, discord).reason

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `plain http and https urls pass with the default ports`() {
        val https = UrlGuard.validate("https://Hooks.Example.com/path?a=b", false).target!!
        assertEquals("hooks.example.com", https.host)
        assertEquals(443, https.port)
        assertTrue(https.https)
        assertEquals(80, UrlGuard.validate("http://example.com/x", false).target!!.port)
        assertEquals(8443, UrlGuard.validate("https://example.com:8443/x", false).target!!.port)
    }

    @Test
    fun `length, syntax and scheme`() {
        assertEquals(Reason.MALFORMED, reason(""))
        assertEquals(Reason.MALFORMED, reason("https://example.com/" + "a".repeat(1010)))
        assertNull(reason("https://example.com/" + "a".repeat(1000)))
        assertEquals(Reason.MALFORMED, reason("https://exa mple.com/"))
        assertEquals(Reason.MALFORMED, reason("/relative/path"))
        assertEquals(Reason.MALFORMED, reason("mailto:a@b.c"))
        assertEquals(Reason.SCHEME, reason("ftp://example.com/x"))
        assertEquals(Reason.SCHEME, reason("file:///etc/passwd"))
        assertEquals(Reason.SCHEME, reason("gopher://example.com/"))
        assertNull(reason("HTTPS://example.com/"))
    }

    @Test
    fun `user info and fragments are refused`() {
        assertEquals(Reason.USERINFO, reason("https://user:pw@example.com/"))
        assertEquals(Reason.USERINFO, reason("https://user@example.com/"))
        assertEquals(Reason.MALFORMED, reason("https://example.com/x#frag"))
    }

    @Test
    fun `ports are 80, 443 or 1024 to 65535`() {
        for (p in listOf(22, 25, 53, 81, 443 + 1, 1023, 0, 65536, 99999)) {
            assertEquals(Reason.PORT, reason("https://example.com:$p/"), "port $p")
        }
        for (p in listOf(80, 443, 1024, 8080, 65535)) assertNull(reason("https://example.com:$p/"), "port $p")
    }

    @Test
    fun `local names are refused unless private targets are allowed`() {
        for (host in listOf("localhost", "api.localhost", "printer.local", "db.internal", "nas.lan", "x.home.arpa")) {
            assertEquals(Reason.HOST, reason("https://$host/"), host)
            assertNull(reason("https://$host/", allowPrivate = true), "$host with the flag")
        }
    }

    @Test
    fun `numeric spellings of an address are refused as host`() {
        for (host in listOf("2130706433", "0x7f.1", "017700000001", "0x7f000001", "127.1", "1.2.3", "010.0.0.1", "256.1.1.1", "1.2.3.4.5")) {
            assertEquals(Reason.HOST, reason("http://$host/"), host)
            assertEquals(Reason.HOST, reason("http://$host/", allowPrivate = true), "$host with the flag")
        }
    }

    @Test
    fun `an ip literal is classified at once`() {
        assertEquals(Reason.PRIVATE_ADDRESS, reason("http://127.0.0.1/"))
        assertEquals(Reason.PRIVATE_ADDRESS, reason("http://10.1.2.3/"))
        assertEquals(Reason.PRIVATE_ADDRESS, reason("http://[::1]/"))
        assertEquals(Reason.PRIVATE_ADDRESS, reason("http://[::ffff:127.0.0.1]/"))
        assertNull(reason("http://127.0.0.1:8080/", allowPrivate = true))
        assertNull(reason("http://[::1]:8080/", allowPrivate = true))
        assertNull(reason("http://93.184.216.34/"))
        assertEquals("93.184.216.34", UrlGuard.validate("http://93.184.216.34/", false).target!!.literal!!.hostAddress)
    }

    @Test
    fun `link-local and metadata literals are refused even with the flag`() {
        for (url in listOf(
            "http://169.254.169.254/latest/meta-data/", "http://169.254.0.1/", "http://[fe80::1]/", "http://[fd00:ec2::254]/",
            "http://100.100.100.200/", "http://192.0.0.192/", "http://168.63.129.16/", "http://0.0.0.0/", "http://224.0.0.1/",
            "http://255.255.255.255/", "http://[ff02::1]/", "http://[::]/", "http://[::ffff:169.254.169.254]/"
        )) {
            assertEquals(Reason.PRIVATE_ADDRESS, reason(url, allowPrivate = true), url)
            assertEquals(Reason.PRIVATE_ADDRESS, reason(url, allowPrivate = false), url)
        }
    }

    @Test
    fun `malformed ipv6 literals are refused`() {
        assertEquals(Reason.MALFORMED, reason("http://[zzzz::1]/"))
        assertEquals(Reason.MALFORMED, reason("http://[::1/"))
    }

    @Test
    fun `discord urls need a discord host and the webhook path`() {
        assertNull(reason("https://discord.com/api/webhooks/123/abc", discord = true))
        assertNull(reason("https://ptb.discord.com/api/webhooks/123/abc", discord = true))
        assertNull(reason("https://discordapp.com/api/webhooks/123/abc", discord = true))
        assertEquals(Reason.DISCORD_URL, reason("https://example.com/api/webhooks/123/abc", discord = true))
        assertEquals(Reason.DISCORD_URL, reason("https://discord.com/other", discord = true))
        assertEquals(Reason.DISCORD_URL, reason("https://evil.discord.com.example.com/api/webhooks/1/a", discord = true))
        assertNull(reason("https://example.com/api/webhooks/123/abc", discord = false))
    }

    @Test
    fun `resolved addresses must all be allowed`() {
        assertNull(UrlGuard.checkAddresses(listOf(ip("93.184.216.34")), false))
        assertNull(UrlGuard.checkAddresses(listOf(ip("93.184.216.34"), ip("2606:2800:220:1:248:1893:25c8:1946")), false))
        assertEquals(Reason.PRIVATE_ADDRESS, UrlGuard.checkAddresses(listOf(ip("93.184.216.34"), ip("10.0.0.5")), false))
        assertNull(UrlGuard.checkAddresses(listOf(ip("93.184.216.34"), ip("10.0.0.5")), true))
        assertEquals(Reason.PRIVATE_ADDRESS, UrlGuard.checkAddresses(listOf(ip("93.184.216.34"), ip("169.254.169.254")), true))
        assertEquals(Reason.DNS, UrlGuard.checkAddresses(emptyList(), true))
        assertTrue(Reason.DNS.retryable)
        assertFalse(Reason.PRIVATE_ADDRESS.retryable)
    }

    @Test
    fun `a validated target keeps the url`() {
        val t = UrlGuard.validate("https://example.com/hook", false).target
        assertNotNull(t)
        assertEquals("https://example.com/hook", t!!.url)
    }
}
