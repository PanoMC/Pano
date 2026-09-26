package com.panomc.platform.hosted

import com.panomc.platform.model.Successful
import com.panomc.platform.route.api.panel.PanelGetHostedAPI
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PanelGetHostedAPITest {
    private val context = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RoutingContext::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as RoutingContext

    private fun api(env: Map<String, String>, feed: HostNoticeFeed = object : HostNoticeFeed { override suspend fun notices() = emptyList<HostNotice>() }) =
        PanelGetHostedAPI(feed).also { it.env = HostedEnvConfig(env) }

    @Suppress("UNCHECKED_CAST")
    private fun respond(api: PanelGetHostedAPI) = runBlocking { (api.handle(context) as Successful).responseMap }

    private val hostedEnv = mapOf(
        "PANO_HOSTED" to "pano-host",
        "PANO_HOST_WORKLOAD_ID" to "wl_abc123",
        "PANO_HOST_INSTANCE_SECRET" to "s3cret",
        "PANO_HOST_API_URL" to "https://api.panomc.com"
    )

    @Test
    fun `not hosted answers hosted false and never asks the feed`() {
        val feed = object : HostNoticeFeed {
            override suspend fun notices(): List<HostNotice> = fail("feed must not be called")
        }
        val body = respond(api(mapOf("PANO_CONTAINER" to "1", "PANO_HOST_WORKLOAD_ID" to "x"), feed))

        assertEquals(false, body["hosted"])
        assertNull(body["workloadId"])
        assertNull(body["manageUrl"])
        assertEquals(emptyList<Any>(), body["notices"])
        assertFalse(body.toString().contains("s3cret"))
    }

    @Test
    fun `hosted answers workload, manage url and the stub's empty notices without the secret`() {
        val body = respond(api(hostedEnv))

        assertEquals(true, body["hosted"])
        assertEquals("wl_abc123", body["workloadId"])
        assertEquals("https://panomc.com/host/manage/wl_abc123", body["manageUrl"])
        assertEquals(emptyList<Any>(), body["notices"])
        assertFalse(body.toString().contains("s3cret"))
        assertTrue(Successful(body).encode(mapOf()).contains("\"hosted\":true"))
    }

    @Test
    fun `manage url override must be http(s)`() {
        assertEquals(
            "https://dev.panomc.com/host/manage/x",
            respond(api(hostedEnv + ("PANO_HOST_MANAGE_URL" to "https://dev.panomc.com/host/manage/x")))["manageUrl"]
        )
        assertEquals(
            "https://panomc.com/host/manage/wl_abc123",
            respond(api(hostedEnv + ("PANO_HOST_MANAGE_URL" to "javascript:alert(1)")))["manageUrl"]
        )
    }

    @Test
    fun `hosted passes feed notices through, sanitised`() {
        val feed = object : HostNoticeFeed {
            override suspend fun notices() = listOf(
                HostNotice("n1", "WARNING", "Disk 90% full", "Storage", "https://panomc.com/host/manage/wl_abc123", 1L),
                HostNotice("n2", "shout", "Unknown level", url = "javascript:alert(1)"),
                HostNotice("", "info", "no id"),
                HostNotice("n3", "info", " ")
            )
        }

        @Suppress("UNCHECKED_CAST")
        val notices = respond(api(hostedEnv, feed))["notices"] as List<Map<String, Any?>>

        assertEquals(listOf("n1", "n2"), notices.map { it["id"] })
        assertEquals("warning", notices[0]["level"])
        assertEquals("Storage", notices[0]["title"])
        assertEquals("https://panomc.com/host/manage/wl_abc123", notices[0]["url"])
        assertEquals("info", notices[1]["level"])
        assertNull(notices[1]["url"])
    }

    @Test
    fun `a failing feed yields no notices instead of an error`() {
        val feed = object : HostNoticeFeed {
            override suspend fun notices(): List<HostNotice> = throw IllegalStateException("control plane down")
        }
        val body = respond(api(hostedEnv, feed))

        assertEquals(true, body["hosted"])
        assertEquals(emptyList<Any>(), body["notices"])
    }
}
