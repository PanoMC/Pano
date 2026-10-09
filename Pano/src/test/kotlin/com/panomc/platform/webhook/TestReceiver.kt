package com.panomc.platform.webhook

import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** One request a [TestReceiver] saw. */
class ReceivedRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** What a [TestReceiver] answers. */
class ReceiverReply(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/** A local HTTP server for the sender and dispatcher tests; [handler] decides the answer per request. */
class TestReceiver(vertx: Vertx, handler: (ReceivedRequest) -> ReceiverReply = { ReceiverReply(200) }) {
    val requests = CopyOnWriteArrayList<ReceivedRequest>()

    @Volatile
    var handler: (ReceivedRequest) -> ReceiverReply = handler

    private val server: HttpServer = vertx.createHttpServer().requestHandler { req ->
        req.body().onSuccess { buffer ->
            val received = ReceivedRequest(
                req.method().name(), req.path(), req.headers().associate { it.key to it.value }, buffer.toString(Charsets.UTF_8)
            )

            requests += received

            val reply = this.handler(received)
            val response = req.response().setStatusCode(reply.status)

            reply.headers.forEach { (k, v) -> response.putHeader(k, v) }
            response.end(reply.body)
        }
    }

    init {
        server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.actualPort()}"

    fun close() {
        server.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }
}
