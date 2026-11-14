package com.panomc.platform.util

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** A real Vert.x reverse proxy in front of a port where nothing listens, as with `init-ui = false` and no dev server. */
class DevUiDownInterceptorTest {
    private lateinit var vertx: Vertx
    private val servers = mutableListOf<HttpServer>()

    @BeforeEach
    fun setUp() {
        vertx = Vertx.vertx()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().blockingGet()
    }

    private fun <T> io.vertx.core.Future<T>.blockingGet(): T = toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private val info = DevUiDownInterceptor.Info("The panel is not running", "http://127.0.0.1:3001", "panel-ui")

    private fun startProxy(active: Boolean): Int {
        val proxy = HttpProxy.reverseProxy(ProxyOptions(), vertx.createHttpClient()).origin(freePort(), "127.0.0.1")
        proxy.addInterceptor(DevUiDownInterceptor(active = { active }, info = { info }))
        val server = vertx.createHttpServer().requestHandler(proxy)
        servers += server
        return server.listen(0, "127.0.0.1").blockingGet().actualPort()
    }

    private data class Answer(val status: Int, val marker: String?, val body: String, val contentType: String? = null)

    private fun get(port: Int, accept: String?): Answer {
        val client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        return com.panomc.platform.TestHttp.onLoop(vertx) {
            client.request(HttpMethod.GET, port, "127.0.0.1", "/panel/login")
                .compose { request ->
                    if (accept != null) request.putHeader("Accept", accept)
                    request.send()
                }
                .compose { resp ->
                    resp.body().map {
                        Answer(resp.statusCode(), resp.getHeader(DevUiDownInterceptor.HEADER), it.toString(), resp.getHeader("Content-Type"))
                    }
                }
        }.blockingGet()
    }

    @Test
    fun `a page navigation gets the page that says how to start the UI`() {
        val answer = get(startProxy(active = true), "text/html,application/xhtml+xml")

        assertEquals(503, answer.status)
        assertEquals("1", answer.marker)
        assertTrue(answer.contentType?.startsWith("text/html") == true, "content type was ${answer.contentType}")
        assertTrue(answer.body.contains("The panel is not running"))
        assertTrue(answer.body.contains("cd panel-ui"))
        assertTrue(answer.body.contains("http://127.0.0.1:3001"))
    }

    @Test
    fun `an asset or API request keeps the plain 502`() {
        val answer = get(startProxy(active = true), "application/json")

        assertEquals(502, answer.status)
        assertNull(answer.marker)
    }

    @Test
    fun `outside development mode the page is never shown`() {
        val answer = get(startProxy(active = false), "text/html")

        assertEquals(502, answer.status)
        assertNull(answer.marker)
    }

    @Test
    fun `only a plain GET for HTML counts as a page navigation`() {
        assertTrue(DevUiDownInterceptor.isPageNavigation(HttpMethod.GET, "text/html", null))
        assertFalse(DevUiDownInterceptor.isPageNavigation(HttpMethod.POST, "text/html", null))
        assertFalse(DevUiDownInterceptor.isPageNavigation(HttpMethod.GET, "text/html", "websocket"))
        assertFalse(DevUiDownInterceptor.isPageNavigation(HttpMethod.GET, null, null))
    }

    @Test
    fun `the page escapes what it shows`() {
        val html = DevUiDownInterceptor.render(DevUiDownInterceptor.Info("<b>x</b>", "http://h:1", "themes/<x>"))

        assertFalse(html.contains("<b>x</b>"))
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"))
        assertTrue(html.contains("themes/&lt;x&gt;"))
    }
}
