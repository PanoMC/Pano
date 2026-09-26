package com.panomc.platform.hosted

import io.vertx.core.Vertx
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PanoHostClientTest {
    private val secret = "inst-secret-0123456789abcdef-XYZ"

    private lateinit var vertx: Vertx
    private lateinit var plane: FakeControlPlane
    private val logs = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        plane = FakeControlPlane(vertx, secret).start()
        logs.clear()
    }

    @AfterEach
    fun tearDown() {
        plane.stop()
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    private fun client(secret: String = this.secret) = PanoHostClient(vertx, plane.baseUrl + "/", secret, { logs += it })

    @Test
    fun `announces capabilities under the api prefix with the bearer secret`() = runBlocking {
        assertTrue(client().announceCapabilities(true))
        assertEquals(true, plane.ssoSupported)

        val seen = plane.requests.single()
        assertEquals("/api/host/instance/capabilities", seen.path)
        assertEquals("Bearer $secret", seen.authorization)
        assertEquals(setOf("ssoSupported"), seen.body!!.fieldNames())
    }

    @Test
    fun `retries transient failures with backoff and stops on a rejected secret`() = runBlocking {
        plane.failCapabilities.set(2)
        val waits = mutableListOf<Long>()

        assertTrue(client().announceWithRetry(wait = { waits += it }))
        assertEquals(listOf(5_000L, 10_000L), waits)
        assertEquals(3, plane.requests.size)

        waits.clear()
        assertFalse(client("wrong-secret-0123456789").announceWithRetry(wait = { waits += it }))
        assertTrue(waits.isEmpty(), "401 is final, no retry")

        assertEquals(600_000L, PanoHostClient.backoff(30))
    }

    @Test
    fun `redeems a ticket exactly once`() = runBlocking {
        plane.issue("ticket-aaaaaaaaaaaaaaaa", "acc-1", "Owner@Example.com")

        val identity = client().redeemSso("ticket-aaaaaaaaaaaaaaaa")
        assertEquals("acc-1", identity.accountId)
        assertEquals("Owner@Example.com", identity.email)
        assertEquals("owner", identity.role)
        assertEquals("w1", identity.workloadId)
        assertEquals("shop.panomc.site", identity.hostname)
        assertFalse(identity.isSupport)

        val again = assertThrows(PanoHostClient.HostApiException::class.java) {
            runBlocking { client().redeemSso("ticket-aaaaaaaaaaaaaaaa") }
        }
        assertEquals(401, again.status)
        assertEquals("INVALID_TOKEN", again.code)
        assertFalse(again.retryable)
    }

    @Test
    fun `unreachable control plane is a retryable error`() = runBlocking {
        val port = plane.port
        plane.stop()
        val error = assertThrows(PanoHostClient.HostApiException::class.java) {
            runBlocking { PanoHostClient(vertx, "http://127.0.0.1:$port", secret, { logs += it }, 2_000).announceCapabilities() }
        }
        assertTrue(error.retryable)
        plane = FakeControlPlane(vertx, secret).start()
    }

    @Test
    fun `secret never reaches logs or error messages`() = runBlocking {
        plane.failCapabilities.set(1)
        client().announceWithRetry(wait = {})
        client("another-secret-0123456789").announceWithRetry(wait = {})

        val error = assertThrows(PanoHostClient.HostApiException::class.java) {
            runBlocking { client().redeemSso("unknown-ticket-0000000000") }
        }

        assertTrue(logs.isNotEmpty())
        (logs + listOfNotNull(error.message, error.toString())).forEach {
            assertFalse(it.contains(secret) || it.contains("another-secret"), "leaked: $it")
        }
    }

    @Test
    fun `fromEnv needs hosted mode, url and secret`() {
        fun env(vararg pairs: Pair<String, String>) = HostedEnvConfig(mapOf(*pairs))

        assertNull(PanoHostClient.fromEnv(vertx, env("PANO_HOST_API_URL" to plane.baseUrl, "PANO_HOST_INSTANCE_SECRET" to secret)) {})
        assertNull(PanoHostClient.fromEnv(vertx, env("PANO_HOSTED" to "pano-host", "PANO_HOST_INSTANCE_SECRET" to secret)) {})
        assertNull(PanoHostClient.fromEnv(vertx, env("PANO_HOSTED" to "pano-host", "PANO_HOST_API_URL" to "ftp://x", "PANO_HOST_INSTANCE_SECRET" to secret)) {})

        val client = PanoHostClient.fromEnv(
            vertx,
            env("PANO_HOSTED" to "pano-host", "PANO_HOST_API_URL" to "https://api.panomc.com/", "PANO_HOST_INSTANCE_SECRET" to secret)
        ) {}
        assertEquals("https://api.panomc.com/host/sso/redeem", client!!.url("/host/sso/redeem"))
    }

    @Test
    fun `reads the notice feed with the bearer secret and drops incomplete entries`() = runBlocking {
        plane.notices = io.vertx.core.json.JsonArray()
            .add(io.vertx.core.json.JsonObject().put("id", "disk").put("level", "warning").put("type", "HOST_DISK_QUOTA")
                .put("title", "Storage almost full").put("message", "92%").put("data", io.vertx.core.json.JsonObject().put("percent", 92)))
            .add(io.vertx.core.json.JsonObject().put("id", "n2").put("message", "no level").put("createdAt", 7L))
            .add(io.vertx.core.json.JsonObject().put("level", "info").put("message", "no id"))
            .add(io.vertx.core.json.JsonObject().put("id", "n3"))
            .add("not an object")

        val notices = client().notices()

        assertEquals(listOf("disk", "n2"), notices.map { it.id })
        assertEquals("warning", notices[0].level)
        assertEquals("HOST_DISK_QUOTA", notices[0].type)
        assertEquals(mapOf("percent" to 92), notices[0].data)
        assertEquals("info", notices[1].level)
        assertEquals(7L, notices[1].createdAt)

        val seen = plane.requests.last()
        assertEquals("/api/host/instance/notices", seen.path)
        assertEquals("Bearer $secret", seen.authorization)

        val error = assertThrows(PanoHostClient.HostApiException::class.java) { runBlocking { client("wrong-secret-0123456789").notices() } }
        assertEquals("INVALID_TOKEN", error.code)
    }
}
