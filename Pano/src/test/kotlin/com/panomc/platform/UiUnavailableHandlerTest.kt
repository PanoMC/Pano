package com.panomc.platform

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class UiUnavailableHandlerTest {
    private lateinit var vertx: Vertx

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T =
        toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    @Test
    fun `answers 503 with no-store while no UI route owns the path`() {
        val router = Router.router(vertx)
        router.route("/*").order(6).handler(UIManager.uiUnavailableHandler())
        val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()

        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        val response = client.request(HttpMethod.GET, port, "127.0.0.1", "/_app/immutable/chunks/x.js")
            .compose { it.send() }
            .blockingGet()

        assertEquals(503, response.statusCode())
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("2", response.getHeader("Retry-After"))
    }

    @Test
    fun `a UI route in front of it wins`() {
        val router = Router.router(vertx)
        router.route("/*").order(5).handler { it.response().setStatusCode(200).end("ui") }
        router.route("/*").order(6).handler(UIManager.uiUnavailableHandler())
        val port = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").blockingGet().actualPort()

        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        val status = client.request(HttpMethod.GET, port, "127.0.0.1", "/").compose { it.send() }.blockingGet().statusCode()

        assertEquals(200, status)
    }
}
