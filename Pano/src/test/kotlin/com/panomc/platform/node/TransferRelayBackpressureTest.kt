package com.panomc.platform.node

import com.panomc.platform.util.PlainHttpServerOptions
import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerResponse
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A node streaming a download into a browser that reads slowly must be held back, not buffered.
 *
 * The node PUTs with `java.net.http` on its default version, which on an `http://` Pano asks for an
 * h2c upgrade on every request. With h2c on, Vert.x collected the whole body before the handler ran:
 * a 2 GB backup took Pano's 1 GB heap down (koyluler.tr, 2026-11-21). [PlainHttpServerOptions]
 * turns h2c off; this relays like `NodeTransferUploadAPI` does and checks that Pano holds next to
 * nothing the browser has not taken yet.
 */
class TransferRelayBackpressureTest {
    private val vertx = Vertx.vertx()

    @AfterEach
    fun close() {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `a slow browser holds back a node that asks for h2c`() {
        val parked = CompletableFuture<HttpServerResponse>()
        val router = Router.router(vertx)

        router.get("/down").handler { context -> parked.complete(context.response()) }
        router.put("/up").handler { context ->
            CoroutineScope(context.vertx().dispatcher()).launch {
                val request = context.request()
                val response = parked.getNow(null) ?: return@launch context.fail(500)

                val length: String? = request.getHeader("Content-Length")

                if (length != null) response.putHeader("Content-Length", length) else response.isChunked = true

                runCatching { request.pipeTo(response).coAwait() }

                context.response().end()
            }
        }

        // Two listeners on one port: connections are handed out round robin, so the browser and the
        // node sit on different event loops, as they do in Pano.
        val port = ServerSocket(0).use { it.localPort }

        repeat(2) {
            vertx.deployVerticle(object : AbstractVerticle() {
                override fun start(startPromise: Promise<Void>) {
                    vertx.createHttpServer(PlainHttpServerOptions.create()).requestHandler(router).listen(port, "127.0.0.1")
                        .onComplete { if (it.succeeded()) startPromise.complete() else startPromise.fail(it.cause()) }
                }
            }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
        }

        val browser = Socket("127.0.0.1", port)
        browser.receiveBufferSize = 64 * 1024
        browser.getOutputStream().write("GET /down HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        browser.getOutputStream().flush()
        parked.get(10, TimeUnit.SECONDS)

        // Small enough that a regression fails the assertions below instead of the test JVM.
        val total = 256L * 1024 * 1024
        val sent = AtomicLong()
        val source = object : InputStream() {
            override fun read(): Int = if (sent.get() >= total) -1 else { sent.incrementAndGet(); 0 }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent.get() >= total) return -1

                val n = minOf(len.toLong(), total - sent.get()).toInt()
                sent.addAndGet(n.toLong())

                return n
            }
        }

        // What the node does: default version (so an h2c upgrade is asked for), a streamed body.
        val client = HttpClient.newBuilder().build()

        Thread {
            runCatching {
                client.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port/up"))
                        .PUT(HttpRequest.BodyPublishers.ofInputStream { source })
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                )
            }
        }.apply { isDaemon = true; start() }

        Thread.sleep(2000)

        // A slow reader, the way a home connection behind a proxy pulls a big file.
        val input = browser.getInputStream()
        val buffer = ByteArray(16 * 1024)
        var received = 0L
        val until = System.currentTimeMillis() + 3000

        while (System.currentTimeMillis() < until) {
            val n = input.read(buffer)

            if (n < 0) break

            received += n
            Thread.sleep(8)
        }

        Thread.sleep(1000)

        val backlog = sent.get() - received

        browser.close()

        assertTrue(received > 1024 * 1024) { "nothing reached the browser (${sent.get() / 1024 / 1024} MB sent)" }
        assertTrue(backlog < 32L * 1024 * 1024) { "Pano holds ${backlog / 1024 / 1024} MB the browser has not taken yet" }
    }
}
