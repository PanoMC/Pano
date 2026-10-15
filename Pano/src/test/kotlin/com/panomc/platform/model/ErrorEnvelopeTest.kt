package com.panomc.platform.model

import com.panomc.platform.Main
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.SMTPException
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.HttpException
import io.vertx.ext.web.validation.RequestPredicateException
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The error envelope (doc 04 section 3): `{ "error": { code, message?, details?, fields? } }`, success bodies
 * without `result`, and the failure cases of [Api.getFailureHandler].
 */
class ErrorEnvelopeTest {
    private class Plain : Error("PLAIN_ERROR", 418)

    private class WithExtras(extras: Map<String, Any?>) : Error("WITH_EXTRAS", 409, "Conflict", extras)

    private fun envelope(body: String): JsonObject = JsonObject(body).getJsonObject("error")

    @Test
    fun `an error without extras is only a code`() {
        val body = JsonObject(Plain().encode())

        assertEquals(setOf("error"), body.fieldNames())
        assertEquals(setOf("code"), body.getJsonObject("error").fieldNames())
        assertEquals("PLAIN_ERROR", body.getJsonObject("error").getString("code"))
        assertEquals(418, Plain().getStatusCode())
    }

    @Test
    fun `no old result key and no flat error code`() {
        val body = JsonObject(NotExists().encode())

        assertFalse(body.containsKey("result"))
        assertTrue(body.getValue("error") is JsonObject)
        assertEquals("NOT_EXISTS", body.getJsonObject("error").getString("code"))
    }

    @Test
    fun `message extra becomes error message and is not repeated in details`() {
        val error = WithExtras(mapOf("message" to "English text", "reason" to "NOT_SUPPORTED"))
        val body = envelope(error.encode())

        assertEquals("English text", body.getString("message"))
        assertEquals(mapOf("reason" to "NOT_SUPPORTED"), body.getJsonObject("details").map)
        assertEquals("English text", error.message)
    }

    @Test
    fun `other extras become details`() {
        val body = envelope(WithExtras(mapOf("themeId" to "blaze", "licenseDeniedReason" to "EXPIRED")).encode())

        assertNull(body.getString("message"))
        assertEquals("blaze", body.getJsonObject("details").getString("themeId"))
        assertEquals("EXPIRED", body.getJsonObject("details").getString("licenseDeniedReason"))
        assertFalse(body.containsKey("fields"))
    }

    @Test
    fun `call site extras are merged into details and win over the error's own`() {
        val body = envelope(WithExtras(mapOf("a" to 1, "b" to 2)).encode(mapOf("b" to 3, "bodyValidationError" to "bad")))

        assertEquals(1, body.getJsonObject("details").getInteger("a"))
        assertEquals(3, body.getJsonObject("details").getInteger("b"))
        assertEquals("bad", body.getJsonObject("details").getString("bodyValidationError"))
    }

    @Test
    fun `only a message leaves details out`() {
        val body = envelope(WithExtras(mapOf("message" to "just text")).encode())

        assertEquals("just text", body.getString("message"))
        assertFalse(body.containsKey("details"))
    }

    @Test
    fun `an empty message is left out`() {
        assertFalse(envelope(WithExtras(mapOf("message" to "")).encode()).containsKey("message"))
    }

    @Test
    fun `invalid fields answer 400 with fields`() {
        val error = InvalidFields(mapOf("email" to "EXISTS", "name" to true))
        val body = envelope(error.encode())

        assertEquals(400, error.getStatusCode())
        assertEquals("INVALID_FIELDS", body.getString("code"))
        assertEquals("EXISTS", body.getJsonObject("fields").getString("email"))
        assertEquals(true, body.getJsonObject("fields").getBoolean("name"))
        assertFalse(body.containsKey("details"))
        assertFalse(JsonObject(error.encode()).containsKey("errors"))
    }

    @Test
    fun `successful bodies carry no result key`() {
        val body = JsonObject(Successful(mapOf("a" to 1)).encode())

        assertEquals(mapOf("a" to 1), body.map)
        assertEquals("{}", Successful().encode())
        assertEquals(200, Successful().getStatusCode())
        assertEquals(mapOf("status" to "progress", "progress" to 0.5), JsonObject(Progress(0.5).encode()).map)
    }

    // ---- the failure cases of Api.getFailureHandler ------------------------------------------------------

    private lateinit var vertx: Vertx
    private var previousContext: AnnotationConfigApplicationContext? = null

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()

        previousContext = runCatching { Main.applicationContext }.getOrNull()

        Main.applicationContext = AnnotationConfigApplicationContext().apply {
            registerBean(Logger::class.java, java.util.function.Supplier { LoggerFactory.getLogger("ErrorEnvelopeTest") })
            refresh()
        }
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

        previousContext?.let { Main.applicationContext = it }
    }

    private class FailingApi(private val failure: () -> Throwable) : Api() {
        override val paths = listOf(Path("/fail", RouteType.GET))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result {
            throw failure()
        }
    }

    private class StatusApi : Api() {
        override val paths = listOf(Path("/fail", RouteType.GET))

        override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

        override suspend fun onBeforeHandle(context: RoutingContext) = Unit

        override suspend fun handle(context: RoutingContext): Result? = null
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun get(api: Api, preHandler: Handler<RoutingContext>? = null): Pair<Int, JsonObject> {
        val router = Router.router(vertx)
        val route = router.route(HttpMethod.GET, "/fail")

        if (preHandler != null) route.handler(preHandler)

        route.handler(api.getHandler()).failureHandler(api.getFailureHandler())

        val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))

        return com.panomc.platform.TestHttp.onLoop(vertx) { client.request(HttpMethod.GET, port, "127.0.0.1", "/fail")
            .compose { it.send() }
            .compose { response ->
                response.body().map { response.statusCode() to JsonObject(it.toString(Charsets.UTF_8)) }
            } }.blockingGet()
    }

    @Test
    fun `a thrown error answers its status and the envelope`() {
        val (status, json) = get(FailingApi { NoPermission() })

        assertEquals(403, status)
        assertEquals(setOf("error"), json.fieldNames())
        assertEquals("NO_PERMISSION", json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a thrown InvalidFields answers 400 with fields`() {
        val (status, json) = get(FailingApi { InvalidFields(mapOf("username" to "EXISTS")) })

        assertEquals(400, status)
        assertEquals("INVALID_FIELDS", json.getJsonObject("error").getString("code"))
        assertEquals("EXISTS", json.getJsonObject("error").getJsonObject("fields").getString("username"))
    }

    @Test
    fun `a validation failure is BAD_REQUEST with bodyValidationError in details`() {
        val (status, json) = get(FailingApi { RequestPredicateException("body is not valid") })

        assertEquals(400, status)
        assertEquals("BAD_REQUEST", json.getJsonObject("error").getString("code"))
        assertTrue(json.getJsonObject("error").getJsonObject("details").getString("bodyValidationError").endsWith("body is not valid"))
    }

    @Test
    fun `an IOException is BAD_REQUEST with inputError in details`() {
        val (status, json) = get(FailingApi { IOException("broken pipe") })

        assertEquals(400, status)
        assertEquals("BAD_REQUEST", json.getJsonObject("error").getString("code"))
        assertEquals("broken pipe", json.getJsonObject("error").getJsonObject("details").getString("inputError"))
    }

    @Test
    fun `a mail failure is INTERNAL_SERVER_ERROR`() {
        val (status, json) = get(FailingApi { SMTPException("smtp down", 550, listOf("smtp down"), false) })

        assertEquals(500, status)
        assertEquals("INTERNAL_SERVER_ERROR", json.getJsonObject("error").getString("code"))
        assertFalse(json.getJsonObject("error").containsKey("details"))
    }

    @Test
    fun `an unexpected exception is INTERNAL_SERVER_ERROR and leaks nothing`() {
        val (status, json) = get(FailingApi { IllegalStateException("secret detail") })

        assertEquals(500, status)
        assertEquals("INTERNAL_SERVER_ERROR", json.getJsonObject("error").getString("code"))
        assertFalse(json.toString().contains("secret detail"))
    }

    @Test
    fun `an http exception 413 is PAYLOAD_TOO_LARGE`() {
        val (status, json) = get(StatusApi(), Handler { it.fail(HttpException(413)) })

        assertEquals(413, status)
        assertEquals("PAYLOAD_TOO_LARGE", json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a bare fail with a 4xx status is BAD_REQUEST`() {
        val (status, json) = get(StatusApi(), Handler { it.fail(404) })

        assertEquals(400, status)
        assertEquals(BadRequest().getErrorCode(), json.getJsonObject("error").getString("code"))
    }

    @Test
    fun `a bare fail with a 5xx status is INTERNAL_SERVER_ERROR`() {
        val (status, json) = get(StatusApi(), Handler { it.fail(503) })

        assertEquals(500, status)
        assertEquals(InternalServerError().getErrorCode(), json.getJsonObject("error").getString("code"))
    }

}
