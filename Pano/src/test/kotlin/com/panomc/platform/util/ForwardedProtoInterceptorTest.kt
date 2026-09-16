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

/** Proxies to an upstream that echoes the X-Forwarded-Proto it received. */
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

    private fun startProxy(): Int {
        val upstreamPort = vertx.createHttpServer().requestHandler { req ->
            req.response().end(req.getHeader("X-Forwarded-Proto") ?: "<none>")
        }.listen(0, "127.0.0.1").blockingGet().actualPort()

        val proxy = HttpProxy.reverseProxy(ProxyOptions(), vertx.createHttpClient()).origin(upstreamPort, "127.0.0.1")
        proxy.addInterceptor(ForwardedProtoInterceptor())
        return vertx.createHttpServer().requestHandler(proxy).listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private fun fetch(port: Int, forwardedProto: String?): String {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        return client.request(HttpMethod.GET, port, "127.0.0.1", "/")
            .compose { req ->
                if (forwardedProto != null) req.putHeader("X-Forwarded-Proto", forwardedProto)
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
}
