package com.panomc.platform.panoplus

import com.panomc.platform.route.api.panel.panoPlus.PanelGetPanoPlusAPI
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PanoPlusTest {
    private val enabledBody = JsonObject(
        """
        {"result":"ok","data":{"enabled":true,"priceMonthly":"3.99","priceYearly":"39.99","currency":"USD",
          "benefits":{"backupGb":50,"freeBackupGb":1,"mailPerMonth":3000,"mailPerDay":400,"freeMailPerMonth":500},
          "includedPackageIds":["standard","network"],
          "me":{"active":true,"status":"GRACE","source":"SUBSCRIPTION","renewsAt":null,"graceUntil":1790000000000,
                "cancelAtPeriodEnd":false,"hostPackageId":null,"period":"YEARLY"}}}
        """.trimIndent()
    )

    @Test
    fun `reads the platform api answer`() {
        val info = PanoPlusInfo.fromApi(enabledBody)

        assertTrue(info.enabled)
        assertEquals("3.99", info.priceMonthly)
        assertEquals("USD", info.currency)
        assertEquals("39.99", info.priceYearly)
        assertEquals("YEARLY", info.me?.period)
        assertEquals(50, info.benefits["backupGb"])
        assertEquals(3000, info.benefits["mailPerMonth"])
        assertEquals("GRACE", info.me?.status)
        assertEquals(true, info.me?.active)
        assertEquals(1790000000000L, info.me?.graceUntil)
        assertNull(info.me?.renewsAt)
    }

    @Test
    fun `switched off, missing or odd answers show nothing`() {
        assertEquals(PanoPlusInfo.DISABLED, PanoPlusInfo.fromApi(JsonObject("""{"result":"ok","data":{"enabled":false}}""")))
        assertEquals(PanoPlusInfo.DISABLED, PanoPlusInfo.fromApi(JsonObject("""{"enabled":"yes"}""")))
        assertEquals(PanoPlusInfo.DISABLED, PanoPlusInfo.fromApi(null))

        val odd = PanoPlusInfo.fromApi(
            JsonObject("""{"enabled":true,"priceMonthly":"<b>","currency":"usd","me":{"active":true,"status":"WHAT","source":"X"}}""")
        )
        assertTrue(odd.enabled)
        assertNull(odd.priceMonthly)
        assertNull(odd.currency)
        assertEquals("NONE", odd.me?.status)
        assertEquals(false, odd.me?.active)
        assertNull(odd.me?.source)
        assertNull(odd.priceYearly, "missing yearly price is fine")
        assertNull(odd.me?.period, "missing period is fine")
        assertNull(PanoPlusInfo.fromApi(JsonObject("""{"enabled":true,"priceYearly":"soon","me":{"status":"ACTIVE","period":"WEEKLY"}}""")).me?.period)
    }

    @Test
    fun `panel view hides everything while disabled or without a website address`() {
        val off = PanelGetPanoPlusAPI.view(PanoPlusInfo.DISABLED, true, "https://panomc.com/plus")
        assertEquals(false, off["enabled"])
        assertNull(off["me"])
        assertNull(off["plusUrl"])
        assertEquals(true, off["hintsDismissed"])

        val noUrl = PanelGetPanoPlusAPI.view(PanoPlusInfo.fromApi(enabledBody), false, null)
        assertEquals(false, noUrl["enabled"])

        val on = PanelGetPanoPlusAPI.view(PanoPlusInfo.fromApi(enabledBody), false, "https://panomc.com/plus")
        assertEquals(true, on["enabled"])
        assertEquals("https://panomc.com/plus", on["plusUrl"])
        assertEquals(false, on["hintsDismissed"])
        assertEquals("GRACE", (on["me"] as Map<*, *>)["status"])
        assertEquals("YEARLY", (on["me"] as Map<*, *>)["period"])
        assertEquals("39.99", on["priceYearly"])
        assertEquals(setOf("enabled", "priceMonthly", "priceYearly", "currency", "benefits", "me", "hintsDismissed", "plusUrl"), on.keys)
    }

    @Test
    fun `plus url follows the website address`() {
        assertEquals("https://panomc.com/plus", PanoPlusManager.plusUrlOf("https://panomc.com/"))
        assertEquals("https://local.panomc.com:3003/plus", PanoPlusManager.plusUrlOf("https://local.panomc.com:3003"))
        assertNull(PanoPlusManager.plusUrlOf("javascript:alert(1)"))
        assertNull(PanoPlusManager.plusUrlOf(""))
        assertNull(PanoPlusManager.plusUrlOf(null))
    }
}

class PanoPlusLicenseClaimTest {
    private fun decoded(build: com.auth0.jwt.JWTCreator.Builder.() -> Unit) =
        com.auth0.jwt.JWT.decode(com.auth0.jwt.JWT.create().apply(build).sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("test")))

    @Test
    fun `a license says it came through pano plus only when the claim is true`() {
        assertTrue(com.panomc.platform.license.panoPlusClaimOf(decoded { withClaim("panoPlus", true) }))
        assertFalse(com.panomc.platform.license.panoPlusClaimOf(decoded { withClaim("panoPlus", false) }))
        assertFalse(com.panomc.platform.license.panoPlusClaimOf(decoded { withClaim("panoPlus", "true") }))
        assertFalse(com.panomc.platform.license.panoPlusClaimOf(decoded { withClaim("uid", "1") }))
    }
}

class PanoPlusCacheTest {
    private var now = 0L
    private var calls = 0
    private var next: () -> PanoPlusInfo = { on }
    private val errors = mutableListOf<Throwable>()

    private val on = PanoPlusInfo(enabled = true, priceMonthly = "3.99")

    private val cache = PanoPlusCache(
        fetch = { calls++; next() },
        ttlMs = 300_000, retryMs = 60_000, minRefreshMs = 15_000, clock = { now }, onError = { errors += it }
    )

    @Test
    fun `not connected never asks panomc com`() = runBlocking {
        assertEquals(PanoPlusInfo.DISABLED, cache.get(null))
        assertEquals(0, calls)
    }

    @Test
    fun `caches for the ttl, no fetch without a read`() = runBlocking {
        assertEquals(on, cache.get("t"))
        now += 299_000
        assertEquals(on, cache.get("t"))
        assertEquals(1, calls)

        now += 1_000
        cache.get("t")
        assertEquals(2, calls)
    }

    @Test
    fun `the panel can ask for a fresh answer, at most every few seconds`() = runBlocking {
        cache.get("t")
        now += 5_000
        cache.get("t", refresh = true)
        assertEquals(1, calls, "too soon")

        now += 10_000
        next = { PanoPlusInfo.DISABLED }
        assertEquals(PanoPlusInfo.DISABLED, cache.get("t", refresh = true))
        assertEquals(2, calls)
    }

    @Test
    fun `keeps the last good answer on errors and retries later`() = runBlocking {
        cache.get("t")
        now += 300_000
        next = { throw IllegalStateException("down") }
        assertEquals(on, cache.get("t"))
        assertEquals(1, errors.size)

        now += 59_000
        cache.get("t")
        assertEquals(2, calls, "no refetch inside the retry window")
    }

    @Test
    fun `a failed first fetch shows nothing`() = runBlocking {
        next = { throw IllegalStateException("down") }
        assertEquals(PanoPlusInfo.DISABLED, cache.get("t"))
    }

    @Test
    fun `another account never sees the previous answer`() = runBlocking {
        cache.get("a")
        next = { throw IllegalStateException("down") }
        assertEquals(PanoPlusInfo.DISABLED, cache.get("b"))
        assertEquals(2, calls)

        assertEquals(PanoPlusInfo.DISABLED, cache.get(null))
    }
}
