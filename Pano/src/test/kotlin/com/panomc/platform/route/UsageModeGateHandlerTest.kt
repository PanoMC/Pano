package com.panomc.platform.route

import com.panomc.platform.route.UsageModeGateHandler.Companion.decide
import com.panomc.platform.route.UsageModeGateHandler.Decision
import com.panomc.platform.util.UsageMode
import io.vertx.core.http.HttpMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UsageModeGateHandlerTest {

    private fun decision(
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
        method = method,
        secFetchDest = secFetchDest,
        accept = accept
    )

    @Test
    fun `passes in both mode`() {
        assertEquals(Decision.PASS, decision(mode = UsageMode.BOTH))
    }

    @Test
    fun `passes in website mode`() {
        assertEquals(Decision.PASS, decision(mode = UsageMode.WEBSITE))
    }

    @Test
    fun `passes while setup is not done`() {
        assertEquals(Decision.PASS, decision(setupDone = false))
    }

    @Test
    fun `passes for panel paths`() {
        assertEquals(Decision.PASS, decision(path = "/panel"))
        assertEquals(Decision.PASS, decision(path = "/panel/servers"))
    }

    @Test
    fun `passes for api paths`() {
        assertEquals(Decision.PASS, decision(path = "/api/siteInfo"))
        assertEquals(Decision.PASS, decision(path = "/panel/api/basicData"))
    }

    @Test
    fun `redirects the index document request`() {
        assertEquals(Decision.REDIRECT, decision(path = "/"))
    }

    @Test
    fun `passes for auth pages`() {
        assertEquals(Decision.PASS, decision(path = "/login"))
        assertEquals(Decision.PASS, decision(path = "/login/"))
        assertEquals(Decision.PASS, decision(path = "/reset-password"))
        assertEquals(Decision.PASS, decision(path = "/renew-password/token"))
        assertEquals(Decision.PASS, decision(path = "/activate"))
        assertEquals(Decision.PASS, decision(path = "/activate-new-email/token"))
    }

    @Test
    fun `redirects a document request detected by accept only`() {
        assertEquals(
            Decision.REDIRECT,
            decision(path = "/profile", secFetchDest = null, accept = "text/html,application/xhtml+xml")
        )
    }

    @Test
    fun `passes a form post without sec fetch dest`() {
        assertEquals(
            Decision.PASS,
            decision(
                path = "/profile",
                method = HttpMethod.POST,
                secFetchDest = null,
                accept = "text/html,application/xhtml+xml"
            )
        )
    }

    @Test
    fun `passes for theme assets`() {
        assertEquals(Decision.PASS, decision(path = "/_app/immutable/entry/app.js"))
        assertEquals(Decision.PASS, decision(path = "/style.css"))
        assertEquals(Decision.PASS, decision(path = "/favicon.png"))
    }

    @Test
    fun `passes for non document requests`() {
        assertEquals(
            Decision.PASS,
            decision(path = "/profile", secFetchDest = null, accept = "application/json")
        )
        assertEquals(Decision.PASS, decision(path = "/profile", secFetchDest = "empty"))
    }
}
