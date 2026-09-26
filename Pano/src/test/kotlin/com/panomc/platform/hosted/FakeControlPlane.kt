package com.panomc.platform.hosted

import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-in for W3's `HostHandoffAPI` on an 18xxx port: Parsek envelopes, bearer instance secret,
 * single-use tickets burnt on presentation, unknown body keys rejected.
 */
class FakeControlPlane(private val vertx: Vertx, val secret: String, private val prefix: String = "/api") {
    data class Seen(val path: String, val authorization: String?, val body: JsonObject?)

    val requests = CopyOnWriteArrayList<Seen>()
    val tickets = ConcurrentHashMap<String, JsonObject>()
    var ssoSupported: Boolean? = null

    /** Next N capability calls answer 503 (to exercise the retry loop). */
    val failCapabilities = AtomicInteger(0)

    private lateinit var server: HttpServer
    var port = 0
        private set

    val baseUrl get() = "http://127.0.0.1:$port$prefix"

    fun start(): FakeControlPlane {
        val handler = { req: io.vertx.core.http.HttpServerRequest ->
            req.body().onSuccess { buffer ->
                val body = runCatching { buffer.toJsonObject() }.getOrNull()
                val auth = req.getHeader("Authorization")
                requests += Seen(req.path(), auth, body)

                fun reply(status: Int, json: JsonObject) =
                    req.response().setStatusCode(status).putHeader("content-type", "application/json").end(json.encode())

                fun error(status: Int, code: String) = reply(status, JsonObject().put("result", "error").put("error", code))
                fun ok(data: JsonObject) = reply(200, JsonObject().put("result", "ok").put("data", data))

                when {
                    req.path() !in setOf("$prefix/host/sso/redeem", "$prefix/host/instance/capabilities") -> error(404, "NOT_EXISTS")
                    body == null -> error(400, "BAD_REQUEST")
                    auth != "Bearer $secret" -> error(401, "INVALID_TOKEN")
                    req.path().endsWith("/capabilities") -> {
                        if (body.fieldNames() != setOf("ssoSupported")) error(400, "BAD_REQUEST")
                        else if (failCapabilities.getAndDecrement() > 0) error(503, "UNAVAILABLE")
                        else {
                            ssoSupported = body.getBoolean("ssoSupported")
                            ok(JsonObject().put("ssoSupported", ssoSupported))
                        }
                    }
                    else -> {
                        if (body.fieldNames() != setOf("ticket")) error(400, "BAD_REQUEST")
                        else tickets.remove(body.getString("ticket"))?.let { ok(it) } ?: error(401, "INVALID_TOKEN")
                    }
                }
            }
            Unit
        }

        for (candidate in 18400..18499) {
            val result = runCatching {
                vertx.createHttpServer().requestHandler(handler).listen(candidate, "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            if (result.isSuccess) {
                server = result.getOrThrow()
                port = candidate
                return this
            }
        }

        error("no free 18xxx port")
    }

    fun issue(ticket: String, accountId: String, email: String, role: String = "owner", workloadId: String = "w1") {
        tickets[ticket] = JsonObject()
            .put("accountId", accountId)
            .put("email", email)
            .put("username", email.substringBefore('@'))
            .put("role", role)
            .put("workloadId", workloadId)
            .put("hostname", "shop.panomc.site")
    }

    fun stop() {
        server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
    }
}
