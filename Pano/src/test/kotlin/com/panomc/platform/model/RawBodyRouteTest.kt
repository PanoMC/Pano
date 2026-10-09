package com.panomc.platform.model

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Raw-body inbound routes (webhooks): an [Api] without a validation handler receives the exact request
 * bytes, multipart is not buffered, and an over-limit body answers 413 PAYLOAD_TOO_LARGE.
 */
class RawBodyRouteTest {
    private lateinit var vertx: Vertx
    private val unhandled = CopyOnWriteArrayList<Throwable>()

    private class RawApi : Api() {
        override val paths = listOf(Path("/hook", RouteType.POST))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

        override fun bodyHandler(): Handler<RoutingContext> =
            BodyHandler.create(false).setBodyLimit(1_048_576)

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result {
            val buffer = context.body().buffer()

            return Successful(
                mapOf(
                    "hasBuffer" to (buffer != null),
                    "bytes" to buffer?.bytes?.let { Base64.getEncoder().encodeToString(it) },
                    "attributes" to context.request().formAttributes().names().sorted()
                )
            )
        }
    }

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx().also { it.exceptionHandler { e -> unhandled.add(e) } }
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun serve(): Int {
        val api = RawApi()
        val router = Router.router(vertx)

        router.route(HttpMethod.POST, "/hook")
            .handler(api.bodyHandler()!!)
            .handler(api.getHandler())
            .failureHandler(api.getFailureHandler())

        return vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun post(port: Int, contentType: String?, body: ByteArray): Pair<Int, JsonObject> {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        return com.panomc.platform.TestHttp.onLoop(vertx) { client.request(HttpMethod.POST, port, "127.0.0.1", "/hook").compose { request ->
            if (contentType != null) request.putHeader("content-type", contentType)

            request.send(Buffer.buffer(body))
        }.compose { response -> response.body().map { response.statusCode() to JsonObject(it.toString()) } } }.blockingGet()
    }

    private fun assertBytesIdentical(contentType: String?, body: ByteArray) {
        val (status, json) = post(serve(), contentType, body)

        assertEquals(200, status, json.encode())
        assertEquals(true, json.getBoolean("hasBuffer"))
        assertEquals(Base64.getEncoder().encodeToString(body), json.getString("bytes"))
    }

    @Test
    fun `json body bytes are identical`() =
        assertBytesIdentical("application/json", """{"a": 1,  "b":"x"}""".toByteArray())

    @Test
    fun `form body bytes are identical`() =
        assertBytesIdentical("application/x-www-form-urlencoded", "a=1&b=%C3%BC&c=".toByteArray())

    @Test
    fun `text xml body bytes are identical`() =
        assertBytesIdentical("text/xml", "<a>ü</a>".toByteArray())

    @Test
    fun `body without a content type bytes are identical`() =
        assertBytesIdentical(null, byteArrayOf(0, 1, 2, 127, -1, -128, 10, 13))

    @Test
    fun `invalid json body bytes are identical`() =
        assertBytesIdentical("application/json", """{"a": [1, 2""".toByteArray())

    @Test
    fun `utf-8 bom body bytes are identical`() =
        assertBytesIdentical(
            "application/json",
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + """{"a":1}""".toByteArray()
        )

    @Test
    fun `multipart body is not buffered and fields are form attributes`() {
        val boundary = "XBOUNDARYX"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"field1\"\r\n\r\nvalue1\r\n" +
                "--$boundary\r\nContent-Disposition: form-data; name=\"field2\"\r\n\r\nvalue2\r\n--$boundary--\r\n"

        val (status, json) = post(serve(), "multipart/form-data; boundary=$boundary", body.toByteArray())

        assertEquals(200, status, json.encode())
        assertEquals(false, json.getBoolean("hasBuffer"))
        assertNull(json.getString("bytes"))
        assertEquals(listOf("field1", "field2"), json.getJsonArray("attributes").list)
    }

    @Test
    fun `body over the 1 MB limit answers 413 PAYLOAD_TOO_LARGE without an unhandled error`() {
        val (status, json) = post(serve(), "application/json", ByteArray(1_048_576 + 1) { 'a'.code.toByte() })

        assertEquals(413, status)
        assertFalse(json.containsKey("result"))
        assertEquals("PAYLOAD_TOO_LARGE", json.getJsonObject("error").getString("code"))
        assertTrue(unhandled.isEmpty(), "unhandled: $unhandled")
    }

    @Test
    fun `body exactly at the limit is accepted`() =
        assertBytesIdentical("application/json", ByteArray(1_048_576) { 'a'.code.toByte() })
}
