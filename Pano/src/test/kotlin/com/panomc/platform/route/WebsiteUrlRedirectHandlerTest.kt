package com.panomc.platform.route

import com.panomc.platform.route.WebsiteUrlRedirectHandler.Companion.buildRedirectTarget
import com.panomc.platform.route.WebsiteUrlRedirectHandler.Companion.decideRedirect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebsiteUrlRedirectHandlerTest {

    private fun decide(
        redirectEnabled: Boolean = true,
        websiteUrl: String = "https://tealmc.com",
        setupDone: Boolean = true,
        path: String = "/",
        upgradeHeader: String? = null,
        clientIp: String? = "203.0.113.10",
        socketPeerIp: String? = "203.0.113.10",
        forwardedHost: String? = null,
        hostHeader: String? = "tealmc.com"
    ) = decideRedirect(
        redirectEnabled = redirectEnabled,
        websiteUrl = websiteUrl,
        setupDone = setupDone,
        path = path,
        upgradeHeader = upgradeHeader,
        clientIp = clientIp,
        socketPeerIp = socketPeerIp,
        forwardedHost = forwardedHost,
        hostHeader = hostHeader
    )

    @Test
    fun `passes when redirect is disabled`() {
        assertEquals(RedirectDecision.PASS, decide(redirectEnabled = false, hostHeader = "other.com"))
    }

    @Test
    fun `passes when website url is blank`() {
        assertEquals(RedirectDecision.PASS, decide(websiteUrl = " ", hostHeader = "other.com"))
    }

    @Test
    fun `passes when setup is not done`() {
        assertEquals(RedirectDecision.PASS, decide(setupDone = false, hostHeader = "other.com"))
    }

    @Test
    fun `passes for api paths`() {
        assertEquals(RedirectDecision.PASS, decide(path = "/api/siteInfo", hostHeader = "other.com"))
    }

    @Test
    fun `passes for acme challenge paths`() {
        assertEquals(
            RedirectDecision.PASS,
            decide(path = "/.well-known/acme-challenge/token", hostHeader = "other.com")
        )
    }

    @Test
    fun `passes for websocket upgrades`() {
        assertEquals(RedirectDecision.PASS, decide(upgradeHeader = "WebSocket", hostHeader = "other.com"))
    }

    @Test
    fun `passes for local clients`() {
        assertEquals(
            RedirectDecision.PASS,
            decide(clientIp = "127.0.0.1", socketPeerIp = "127.0.0.1", hostHeader = "other.com")
        )
    }

    @Test
    fun `skips when loopback proxy does not forward host`() {
        // nginx proxy_pass without proxy_set_header: socket peer is loopback, XFF carries the
        // real client, Host is the proxy's upstream target — must not redirect off it.
        assertEquals(
            RedirectDecision.SKIP_UNTRUSTED_PROXY,
            decide(
                clientIp = "203.0.113.10",
                socketPeerIp = "127.0.0.1",
                forwardedHost = null,
                hostHeader = "127.0.0.1:8090"
            )
        )
    }

    @Test
    fun `redirects when loopback proxy forwards mismatching host`() {
        assertEquals(
            RedirectDecision.REDIRECT,
            decide(socketPeerIp = "127.0.0.1", forwardedHost = "www.tealmc.com", hostHeader = "127.0.0.1:8090")
        )
    }

    @Test
    fun `passes when loopback proxy forwards matching host`() {
        assertEquals(
            RedirectDecision.PASS,
            decide(socketPeerIp = "127.0.0.1", forwardedHost = "tealmc.com", hostHeader = "127.0.0.1:8090")
        )
    }

    @Test
    fun `redirects direct request with mismatching host`() {
        assertEquals(RedirectDecision.REDIRECT, decide(hostHeader = "www.tealmc.com"))
    }

    @Test
    fun `passes when host matches ignoring case and port`() {
        assertEquals(
            RedirectDecision.PASS,
            decide(websiteUrl = "http://tealmc.com:8090", hostHeader = "TealMC.com:80")
        )
    }

    @Test
    fun `passes when host header is missing`() {
        assertEquals(RedirectDecision.PASS, decide(hostHeader = null))
    }

    @Test
    fun `passes when website url is unparseable`() {
        assertEquals(RedirectDecision.PASS, decide(websiteUrl = "http://exa mple", hostHeader = "other.com"))
    }

    @Test
    fun `buildRedirectTarget appends path and query`() {
        assertEquals(
            "https://tealmc.com/profile?tab=1",
            buildRedirectTarget("https://tealmc.com", "/profile", "tab=1")
        )
    }

    @Test
    fun `buildRedirectTarget trims trailing slash and omits blank query`() {
        assertEquals("https://tealmc.com/profile", buildRedirectTarget("https://tealmc.com/", "/profile", ""))
    }

    @Test
    fun `buildRedirectTarget keeps configured non-default port`() {
        assertEquals(
            "http://tealmc.com:8090/profile",
            buildRedirectTarget("http://tealmc.com:8090", "/profile", null)
        )
    }
}
