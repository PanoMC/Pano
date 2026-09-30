package com.panomc.platform.route

import com.panomc.platform.route.ServersModeRootHandler.Companion.decide
import com.panomc.platform.route.ServersModeRootHandler.Companion.panelLocation
import com.panomc.platform.util.UsageMode
import io.vertx.core.http.HttpMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServersModeRootHandlerTest {

    private fun redirects(
        mode: UsageMode = UsageMode.SERVERS,
        setupDone: Boolean = true,
        path: String = "/",
        method: HttpMethod = HttpMethod.GET,
        secFetchDest: String? = "document",
        accept: String? = "text/html,application/xhtml+xml"
    ) = decide(
        mode = mode,
        setupDone = setupDone,
        path = path,
        secFetchDest = secFetchDest,
        accept = accept,
        method = method
    )

    @Test
    fun `sends the root of a servers-only install to the panel`() {
        assertTrue(redirects())
    }

    @Test
    fun `claims the theme auth pages the usage-mode gate lets through`() {
        // These used to be served by the theme, which is why the gate passes them. With no theme
        // and a panel-native login, this handler is what an old bookmark lands on.
        assertTrue(redirects(path = "/login"))
        assertTrue(redirects(path = "/reset-password"))
        assertTrue(redirects(path = "/activate/abc"))
    }

    @Test
    fun `never redirects the panel to itself`() {
        // Reaching order 6 under /panel means panel-ui is not bound; a redirect here would be an
        // infinite loop instead of a retryable 503.
        assertFalse(redirects(path = "/panel"))
        assertFalse(redirects(path = "/panel/login"))
        assertFalse(redirects(path = "/panel/servers/4"))
    }

    @Test
    fun `leaves every other mode alone`() {
        assertFalse(redirects(mode = UsageMode.BOTH))
        assertFalse(redirects(mode = UsageMode.WEBSITE))
    }

    @Test
    fun `leaves a half-installed platform to the setup wizard`() {
        assertFalse(redirects(setupDone = false))
    }

    @Test
    fun `does not turn a missing asset or api call into a redirect`() {
        assertFalse(redirects(path = "/api/siteInfo", secFetchDest = "empty", accept = "application/json"))
        assertFalse(redirects(path = "/style.css", secFetchDest = "style", accept = "text/css,*/*;q=0.1"))
        assertFalse(redirects(path = "/_app/immutable/x.js", secFetchDest = "script", accept = "*/*"))
        assertFalse(redirects(path = "/", secFetchDest = "empty", accept = "application/json"))
    }

    @Test
    fun `falls back to the method and Accept when the browser sends no fetch metadata`() {
        // Sec-Fetch-Dest is authoritative when present, so a form POST that navigates still counts
        // as a document. Without it, only a safe method asking for HTML does.
        assertTrue(redirects(method = HttpMethod.POST, secFetchDest = "document"))

        assertTrue(redirects(method = HttpMethod.GET, secFetchDest = null))
        assertFalse(redirects(method = HttpMethod.POST, secFetchDest = null))
        assertFalse(redirects(method = HttpMethod.GET, secFetchDest = null, accept = null))
    }

    @Test
    fun `sends a bare site page to the dashboard`() {
        assertEquals("/panel", panelLocation("/", null))
        assertEquals("/panel", panelLocation("/profile", null))
        assertEquals("/panel", panelLocation("/login", ""))
    }

    @Test
    fun `keeps path and query of a sign-in hop`() {
        // OAuth callbacks, magic links and login errors carry their state in the query; the panel
        // serves these pages itself, so they move under /panel unchanged.
        assertEquals(
            "/panel/social-login/callback?provider=discord&code=a&state=b",
            panelLocation("/social-login/callback", "provider=discord&code=a&state=b")
        )
        assertEquals("/panel/login?socialError=INVALID_FLOW_STATE", panelLocation("/login", "socialError=INVALID_FLOW_STATE"))
        assertEquals("/panel?utm_source=x", panelLocation("/", "utm_source=x"))
    }
}
