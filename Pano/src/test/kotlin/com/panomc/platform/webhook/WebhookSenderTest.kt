package com.panomc.platform.webhook

import com.panomc.platform.db.model.WebhookEndpoint
import com.panomc.platform.db.model.WebhookSigning
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Real HTTP from `WebhookSender` / `OutboundHttp` to a local receiver: headers, signature, status handling, the guard. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookSenderTest {
    private lateinit var vertx: Vertx
    private val receivers = ArrayList<TestReceiver>()
    private val clock = java.util.concurrent.atomic.AtomicLong(1_760_000_000_000L)
    private val cipher = WebhookTestSupport.cipher()
    private val secret = "whsec_test_0123456789abcdef"

    @BeforeAll
    fun start() {
        vertx = Vertx.vertx()
    }

    @AfterAll
    fun stop() {
        vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @AfterEach
    fun closeReceivers() {
        receivers.forEach { it.close() }
        receivers.clear()
    }

    private fun receiver(handler: (ReceivedRequest) -> ReceiverReply = { ReceiverReply(200) }) =
        TestReceiver(vertx, handler).also { receivers += it }

    private fun sender(allowPrivate: Boolean = true) =
        WebhookSender(WebhookTestSupport.outbound(vertx, StubResolver()), { cipher }, { clock.get() }, "test", { allowPrivate })

    @Test
    fun `a delivery carries the event headers, the exact body bytes and a signature that verifies`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(200, """{"ok":true}""") }
        val body = """{"id":"e1","event":"market.order.paid","name":"Çağrı \"quoted\"\n🎮"}"""
        val row = WebhookTestSupport.row("${r.baseUrl}/hook", body, WebhookSigning.HMAC_SHA256, cipher.encrypt(secret), attempts = 3, id = 77)

        val attempt = sender().send(row, null)

        assertEquals(200, attempt.statusCode)
        assertNull(attempt.error)
        assertEquals("""{"ok":true}""", attempt.response)

        val request = r.requests.single()

        assertEquals("POST", request.method)
        assertEquals(body, request.body)
        assertEquals("application/json; charset=utf-8", request.header("Content-Type"))
        assertEquals("Pano/test", request.header("User-Agent"))
        assertEquals("market.order.paid", request.header("X-Pano-Event"))
        assertEquals("00000000-0000-4000-8000-000000000001", request.header("X-Pano-Event-Id"))
        assertEquals("77", request.header("X-Pano-Delivery"))
        assertEquals("3", request.header("X-Pano-Attempt"))

        val signature = request.header("X-Pano-Signature")

        assertNotNull(signature)
        assertTrue(signature!!.startsWith("t=${clock.get() / 1000},v1="))
        assertTrue(WebhookSigner.verify(signature, secret, request.body, clock.get() / 1000))
    }

    @Test
    fun `signing NONE sends no signature header`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(204) }

        val attempt = sender().send(WebhookTestSupport.row("${r.baseUrl}/hook", signing = WebhookSigning.NONE, secret = cipher.encrypt(secret)), null)

        assertEquals(204, attempt.statusCode)
        assertNull(r.requests.single().header("X-Pano-Signature"))
    }

    @Test
    fun `the timestamp of the signature is fresh per attempt while the body stays identical`(): Unit = runBlocking {
        val r = receiver()
        val s = sender()

        s.send(WebhookTestSupport.row("${r.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = cipher.encrypt(secret)), null)
        clock.addAndGet(61_000)
        s.send(WebhookTestSupport.row("${r.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = cipher.encrypt(secret), attempts = 2), null)

        val (first, second) = r.requests

        assertEquals(first.body, second.body)
        assertEquals(first.header("X-Pano-Event-Id"), second.header("X-Pano-Event-Id"))
        assertFalse(first.header("X-Pano-Signature") == second.header("X-Pano-Signature"))
        assertEquals("2", second.header("X-Pano-Attempt"))
    }

    @Test
    fun `an HMAC delivery with an unreadable secret is never sent`(): Unit = runBlocking {
        val r = receiver()

        for (bad in listOf(null, "", "v1:AAAA", "v1:not base64 !!")) {
            val attempt = sender().send(WebhookTestSupport.row("${r.baseUrl}/hook", signing = WebhookSigning.HMAC_SHA256, secret = bad), null)

            assertEquals("SECRET_UNREADABLE", attempt.error, "$bad")
            assertFalse(attempt.retryable)
            assertNull(attempt.statusCode)
        }

        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun `the stored admin headers are decrypted and sent, X-Pano ones are dropped`(): Unit = runBlocking {
        val r = receiver()
        val headers = JsonObject().put("X-Token", "abc").put("X-Pano-Event", "forged").put("Host", "evil.test").encode()
        val endpoint = WebhookEndpoint(id = 1, headers = cipher.encrypt(headers))

        sender().send(WebhookTestSupport.row("${r.baseUrl}/hook"), endpoint)

        val request = r.requests.single()

        assertEquals("abc", request.header("X-Token"))
        assertEquals("market.order.paid", request.header("X-Pano-Event"))
        assertFalse(request.header("Host") == "evil.test")
    }

    @Test
    fun `admin headers that cannot be read stop the delivery`(): Unit = runBlocking {
        val r = receiver()

        val attempt = sender().send(WebhookTestSupport.row("${r.baseUrl}/hook"), WebhookEndpoint(id = 1, headers = "v1:AAAA"))

        assertEquals("HEADERS_UNREADABLE", attempt.error)
        assertFalse(attempt.retryable)
        assertTrue(r.requests.isEmpty())
    }

    @Test
    fun `a 3xx answer is a failed attempt and the redirect is not followed`(): Unit = runBlocking {
        val r = receiver { request ->
            if (request.path == "/hook") ReceiverReply(302, headers = mapOf("Location" to "/elsewhere")) else ReceiverReply(200)
        }

        val attempt = sender().send(WebhookTestSupport.row("${r.baseUrl}/hook"), null)

        assertEquals(302, attempt.statusCode)
        assertEquals("REDIRECT_NOT_FOLLOWED", attempt.error)
        assertTrue(r.requests.none { it.path == "/elsewhere" })
    }

    @Test
    fun `the answer is cut at 2 KiB`(): Unit = runBlocking {
        val r = receiver { ReceiverReply(500, "x".repeat(10_000)) }

        val attempt = sender().send(WebhookTestSupport.row("${r.baseUrl}/hook"), null)

        assertEquals(500, attempt.statusCode)
        assertEquals(2048, attempt.response!!.length)
    }

    @Test
    fun `a loopback target is refused before any request unless private targets are allowed`(): Unit = runBlocking {
        val r = receiver()

        val attempt = sender(allowPrivate = false).send(WebhookTestSupport.row("${r.baseUrl}/hook"), null)

        assertNull(attempt.statusCode)
        assertTrue(attempt.error!!.startsWith("URL_GUARD:"), attempt.error)
        assertTrue(r.requests.isEmpty())
    }
}
