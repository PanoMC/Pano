package com.panomc.platform.util

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.core.http.PoolOptions
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives a real Vert.x reverse proxy against a fake upstream that mimics Bun 1.3.x: for
 * selected requests it closes the connection without writing a response.
 */
class UpstreamRetryInterceptorTest {
    private lateinit var vertx: Vertx
    private val upstreamRequests = AtomicInteger()
    private val servers = mutableListOf<HttpServer>()

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
        upstreamRequests.set(0)
    }

    @AfterEach
    fun tearDown() {
        vertx.close().blockingGet()
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    /** Upstream that drops (closes without responding) every request for which [drop] is true. */
    private fun startUpstream(dropDelayMs: Long = 0, drop: (requestNo: Int) -> Boolean): Int {
        val server = vertx.createHttpServer().requestHandler { req ->
            val n = upstreamRequests.incrementAndGet()
            if (drop(n)) {
                if (dropDelayMs > 0) {
                    vertx.setTimer(dropDelayMs) { req.connection().close() }
                } else {
                    req.connection().close()
                }
            } else {
                req.response().putHeader("content-type", "text/javascript").end("export const n = $n;")
            }
        }
        servers += server
        return server.listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun startProxy(upstreamPort: Int, withRetry: Boolean, retryBudgetMs: Long = 2_000): Int {
        val client = vertx.createHttpClient(
            HttpClientOptions().setKeepAlive(true).setKeepAliveTimeout(2),
            PoolOptions().setHttp1MaxSize(4)
        )
        val proxy = HttpProxy.reverseProxy(ProxyOptions(), client).origin(upstreamPort, "127.0.0.1")
        if (withRetry) {
            proxy.addInterceptor(
                UpstreamRetryInterceptor(LoggerFactory.getLogger("test-proxy"), "theme", 2, retryBudgetMs)
            )
        }
        val server = vertx.createHttpServer().requestHandler(proxy)
        servers += server
        return server.listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun send(proxyPort: Int, method: HttpMethod, count: Int): List<Int> {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        return (1..count).map {
            client.request(method, proxyPort, "127.0.0.1", "/_app/immutable/chunks/x.js")
                .compose { it.send() }
                .compose { resp -> resp.body().map { resp.statusCode() } }
                .blockingGet()
        }
    }

    @Test
    fun `retries GETs that the upstream dropped before responding`() {
        val upstream = startUpstream { it % 3 == 0 }
        val proxy = startProxy(upstream, withRetry = true)

        val statuses = send(proxy, HttpMethod.GET, 30)

        assertEquals(List(30) { 200 }, statuses)
        assertTrue(upstreamRequests.get() > 30, "dropped requests must have been re-sent")
    }

    @Test
    fun `without the interceptor the same drops surface as 502`() {
        val upstream = startUpstream { it % 3 == 0 }
        val proxy = startProxy(upstream, withRetry = false)

        val statuses = send(proxy, HttpMethod.GET, 30)

        assertEquals(10, statuses.count { it == 502 })
        assertEquals(20, statuses.count { it == 200 })
    }

    @Test
    fun `gives up with 502 after the retry budget`() {
        val upstream = startUpstream { true }
        val proxy = startProxy(upstream, withRetry = true)

        val statuses = send(proxy, HttpMethod.GET, 1)

        assertEquals(listOf(502), statuses)
        assertEquals(3, upstreamRequests.get(), "one attempt plus two retries")
    }

    @Test
    fun `does not retry a drop that took longer than the budget`() {
        val upstream = startUpstream(dropDelayMs = 300) { true }
        val proxy = startProxy(upstream, withRetry = true, retryBudgetMs = 100)

        val statuses = send(proxy, HttpMethod.GET, 1)

        assertEquals(listOf(502), statuses)
        assertEquals(1, upstreamRequests.get(), "a slow failure looks like a hung upstream; never re-sent")
    }

    @Test
    fun `does not retry non-idempotent methods`() {
        val upstream = startUpstream { it == 1 }
        val proxy = startProxy(upstream, withRetry = true)

        val statuses = send(proxy, HttpMethod.POST, 1)

        assertEquals(listOf(502), statuses)
        assertEquals(1, upstreamRequests.get())
    }
}
