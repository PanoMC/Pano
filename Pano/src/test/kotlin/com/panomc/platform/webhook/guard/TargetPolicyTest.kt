package com.panomc.platform.webhook.guard

import com.panomc.platform.webhook.guard.UrlGuard.Reason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** `TargetPolicy.check(url, resolvedAddresses, allowPrivate)` (17 section 4 S10, section 11.1). */
class TargetPolicyTest {
    private fun ip(vararg text: String): List<InetAddress> = text.map { InetAddress.getByName(it) }

    private fun refusal(url: String, resolved: List<InetAddress>?, allowPrivate: Boolean = false, discord: Boolean = false): Reason? =
        (TargetPolicy.check(url, resolved, allowPrivate, discord) as? TargetPolicy.Result.Refused)?.reason

    @Test
    fun `a public name resolving to public addresses is allowed and the first address is the pinned one`() {
        val r = TargetPolicy.check("https://hooks.example.com/x", ip("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"), false)
        val allowed = r.allowed
        assertNotNull(allowed)
        assertEquals("93.184.216.34", allowed!!.addresses.first().hostAddress)
        assertEquals("hooks.example.com", allowed.target.host)
    }

    @Test
    fun `a public name that resolves to a private address is refused`() {
        for (private in listOf("127.0.0.1", "10.0.0.8", "172.16.5.5", "192.168.1.10", "100.64.1.1", "::1", "fc00::5", "fd12::1")) {
            assertEquals(Reason.PRIVATE_ADDRESS, refusal("https://evil.example.com/", ip(private)), private)
        }
    }

    @Test
    fun `one private address among public ones refuses the name`() {
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("https://evil.example.com/", ip("93.184.216.34", "10.0.0.1")))
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("https://evil.example.com/", ip("10.0.0.1", "93.184.216.34")))
    }

    @Test
    fun `link-local and metadata are refused even with allowPrivate`() {
        for (bad in listOf("169.254.169.254", "169.254.1.1", "fe80::1", "fd00:ec2::254", "100.100.100.200", "0.0.0.0", "224.0.0.1")) {
            assertEquals(Reason.PRIVATE_ADDRESS, refusal("https://evil.example.com/", ip(bad), allowPrivate = true), bad)
            assertEquals(Reason.PRIVATE_ADDRESS, refusal("https://evil.example.com/", ip(bad), allowPrivate = false), bad)
        }
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("http://169.254.169.254/latest/", null, allowPrivate = true))
    }

    @Test
    fun `loopback, private and mapped addresses are allowed with the flag`() {
        assertNull(refusal("https://dev.example.com:8443/", ip("127.0.0.1"), allowPrivate = true))
        assertNull(refusal("https://dev.example.com/", ip("10.0.0.1", "192.168.0.4"), allowPrivate = true))
        assertNull(refusal("https://dev.example.com/", ip("::1", "fc00::1"), allowPrivate = true))
        assertNull(refusal("http://127.0.0.1:9000/", null, allowPrivate = true))
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("http://127.0.0.1:9000/", null, allowPrivate = false))
    }

    @Test
    fun `ipv4-mapped ipv6 literals follow the embedded address`() {
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("http://[::ffff:10.0.0.1]/", null))
        assertNull(refusal("http://[::ffff:10.0.0.1]:8080/", null, allowPrivate = true))
        assertEquals(Reason.PRIVATE_ADDRESS, refusal("http://[::ffff:169.254.169.254]/", null, allowPrivate = true))
    }

    @Test
    fun `non-http schemes and url syntax errors are refused before any address is looked at`() {
        assertEquals(Reason.SCHEME, refusal("ftp://example.com/", ip("93.184.216.34")))
        assertEquals(Reason.SCHEME, refusal("file:///etc/passwd", null, allowPrivate = true))
        assertEquals(Reason.USERINFO, refusal("https://a:b@example.com/", ip("93.184.216.34")))
        assertEquals(Reason.PORT, refusal("https://example.com:22/", ip("93.184.216.34")))
        assertEquals(Reason.HOST, refusal("http://2130706433/", null, allowPrivate = true))
    }

    @Test
    fun `a failed or empty resolution is a retryable DNS refusal`() {
        assertEquals(Reason.DNS, refusal("https://nx.example.com/", null))
        assertEquals(Reason.DNS, refusal("https://nx.example.com/", emptyList()))
        assertTrue(Reason.DNS.retryable)
    }

    @Test
    fun `discord format needs a discord url`() {
        assertNull(refusal("https://discord.com/api/webhooks/1/abc", ip("162.159.135.232"), discord = true))
        assertEquals(Reason.DISCORD_URL, refusal("https://example.com/api/webhooks/1/abc", ip("93.184.216.34"), discord = true))
    }

    @Test
    fun `the flag is forced off when hosted`() {
        assertTrue(TargetPolicy.effectiveAllowPrivate(flag = true, hosted = false))
        assertFalse(TargetPolicy.effectiveAllowPrivate(flag = true, hosted = true))
        assertFalse(TargetPolicy.effectiveAllowPrivate(flag = false, hosted = false))
    }

    @Test
    fun `a refusal names its last error`() {
        val r = TargetPolicy.check("http://10.0.0.1/", null, false) as TargetPolicy.Result.Refused
        assertEquals("URL_GUARD:PRIVATE_ADDRESS", r.lastError)
        assertTrue(TargetPolicy.syntaxOk("https://example.com/x", false))
        assertFalse(TargetPolicy.syntaxOk("http://10.0.0.1/", false))
    }
}
