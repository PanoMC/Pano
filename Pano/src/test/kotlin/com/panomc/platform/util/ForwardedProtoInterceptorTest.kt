package com.panomc.platform.util

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Proxies to an upstream that echoes the X-Forwarded-Proto (or X-Forwarded-For) it received. */
class ForwardedProtoInterceptorTest {
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

    private fun startProxy(echoedHeader: String = "X-Forwarded-Proto"): Int {
        val upstreamPort = vertx.createHttpServer().requestHandler { req ->
            req.response().end(req.getHeader(echoedHeader) ?: "<none>")
        }.listen(0, "127.0.0.1").blockingGet().actualPort()

        val proxy = HttpProxy.reverseProxy(ProxyOptions(), vertx.createHttpClient()).origin(upstreamPort, "127.0.0.1")
        proxy.addInterceptor(ForwardedProtoInterceptor())
        return vertx.createHttpServer().requestHandler(proxy).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun fetch(
        port: Int,
        forwardedProto: String?,
        forwardedFor: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        return client.request(HttpMethod.GET, port, "127.0.0.1", "/")
            .compose { req ->
                if (forwardedProto != null) req.putHeader("X-Forwarded-Proto", forwardedProto)
                if (forwardedFor != null) req.putHeader("X-Forwarded-For", forwardedFor)
                extraHeaders.forEach { (name, value) -> req.putHeader(name, value) }
                req.send()
            }
            .compose { it.body() }
            .blockingGet()
            .toString()
    }

    @Test
    fun `fills the scheme from the inbound connection when no proxy set it`() {
        assertEquals("http", fetch(startProxy(), null))
    }

    @Test
    fun `leaves a header set by the reverse proxy in front untouched`() {
        assertEquals("https", fetch(startProxy(), "https"))
    }

    @Test
    fun `fills the client address from the inbound connection when no proxy set it`() {
        assertEquals("127.0.0.1", fetch(startProxy("X-Forwarded-For"), null))
    }

    @Test
    fun `leaves a client address set by the reverse proxy in front untouched`() {
        assertEquals("203.0.113.7", fetch(startProxy("X-Forwarded-For"), null, "203.0.113.7"))
    }

    @Test
    fun `other headers that name the visitor are rewritten to the resolved address, not passed on`() {
        val forged = mapOf("CF-Connecting-IP" to "9.9.9.9", "True-Client-IP" to "9.9.9.9", "X-Real-IP" to "9.9.9.9")

        forged.keys.forEach { name ->
            assertEquals("203.0.113.7", fetch(startProxy(name), null, "203.0.113.7", forged), name)
        }
    }

    @Test
    fun `the Forwarded header is dropped and absent address headers are not invented`() {
        assertEquals("<none>", fetch(startProxy("Forwarded"), null, "203.0.113.7", mapOf("Forwarded" to "for=9.9.9.9")))
        assertEquals("<none>", fetch(startProxy("CF-Connecting-IP"), null, "203.0.113.7"))
    }
}
