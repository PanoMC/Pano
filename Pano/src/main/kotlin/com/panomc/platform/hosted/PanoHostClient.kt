package com.panomc.platform.hosted

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.delay
import java.net.URI

/**
 * Server-to-server client for the Pano Host control plane (host-api.md §SSO): announces the
 * instance's capabilities and redeems SSO tickets, authenticated by `PANO_HOST_INSTANCE_SECRET`.
 *
 * URLs are `PANO_HOST_API_URL` + `/host/...` (the dev URL carries an `/api` prefix, the live one
 * none). Responses are Parsek envelopes: `{result: "ok", data}` or `{result: "error", error}`.
 *
 * The secret only ever goes into the `Authorization` header; it is never part of a URL, a log
 * line or an exception message.
 */
class PanoHostClient(
    vertx: Vertx,
    baseUrl: String,
    private val instanceSecret: String,
    private val log: (String) -> Unit = {},
    timeoutMs: Long = 10_000
) {
    companion object {
        /** Backoff between capability announce attempts: 5 s doubling up to 10 min. */
        fun backoff(attempt: Int): Long = (5_000L shl (attempt - 1).coerceIn(0, 7)).coerceAtMost(600_000L)

        /** A client for the hosted env, or null when not on Pano Host or the env is incomplete. */
        fun fromEnv(vertx: Vertx, env: HostedEnvConfig, log: (String) -> Unit): PanoHostClient? {
            if (!env.isHosted) return null
            val url = env.hostApiUrl ?: return null
            val secret = env.instanceSecret ?: return null

            return runCatching { PanoHostClient(vertx, url, secret, log) }.getOrNull()
        }
    }

    /** Non-2xx or `result != ok`; [code] is the control plane's error code (e.g. `INVALID_TOKEN`). */
    class HostApiException(val status: Int, val code: String?) : RuntimeException("Pano Host API error $status ${code ?: ""}".trim()) {
        /** Network failures and 5xx/429 are worth retrying; other 4xx are final. */
        val retryable get() = status == 0 || status >= 500 || status == 429
    }

    data class SsoIdentity(
        val accountId: String,
        val email: String,
        val username: String,
        val role: String,
        val workloadId: String,
        val hostname: String
    ) {
        val isSupport get() = role == "support"
    }

    val baseUrl: String = baseUrl.trim().trimEnd('/').also {
        val uri = URI(it)
        require(uri.scheme == "https" || uri.scheme == "http") { "PANO_HOST_API_URL must be http(s)" }
        require(!uri.host.isNullOrEmpty()) { "PANO_HOST_API_URL has no host" }
    }

    private val client = WebClient.create(
        vertx,
        WebClientOptions()
            .setFollowRedirects(false)
            .setConnectTimeout(timeoutMs.toInt())
            .setIdleTimeout(timeoutMs.toInt())
            .setIdleTimeoutUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
            .setUserAgent("Pano-Host-Instance")
    )

    fun url(path: String): String = baseUrl + path

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val response: HttpResponse<Buffer> = try {
            client.requestAbs(HttpMethod.POST, url(path))
                .putHeader("Authorization", "Bearer $instanceSecret")
                .putHeader("Accept", "application/json")
                .timeout(15_000)
                .sendJsonObject(body)
                .coAwait()
        } catch (e: Throwable) {
            throw HostApiException(0, e.javaClass.simpleName)
        }

        val json = runCatching { response.bodyAsJsonObject() }.getOrNull()

        if (response.statusCode() !in 200..299 || json?.getString("result") != "ok") {
            throw HostApiException(response.statusCode(), json?.getString("error"))
        }

        return json.getJsonObject("data") ?: JsonObject()
    }

    /** `POST /host/instance/capabilities {ssoSupported}`. */
    suspend fun announceCapabilities(ssoSupported: Boolean = true): Boolean {
        val data = post("/host/instance/capabilities", JsonObject().put("ssoSupported", ssoSupported))
        return data.getBoolean("ssoSupported", ssoSupported)
    }

    /**
     * Announces with retry: transient failures back off ([backoff]) until [maxAttempts]; a final
     * 4xx (bad secret, rejected body) stops at once. Returns whether the announce landed.
     */
    suspend fun announceWithRetry(
        ssoSupported: Boolean = true,
        maxAttempts: Int = 50,
        wait: suspend (Long) -> Unit = { delay(it) }
    ): Boolean {
        for (attempt in 1..maxAttempts) {
            try {
                announceCapabilities(ssoSupported)
                log("Announced Pano Host capabilities (ssoSupported=$ssoSupported)")
                return true
            } catch (e: HostApiException) {
                if (!e.retryable || attempt == maxAttempts) {
                    log("Could not announce Pano Host capabilities: ${e.message}")
                    return false
                }

                val pause = backoff(attempt)
                log("Pano Host capabilities announce failed (${e.message}), retrying in ${pause / 1000}s")
                wait(pause)
            }
        }

        return false
    }

    /** `POST /host/sso/redeem {ticket}` → the panomc.com identity the ticket was issued for. */
    suspend fun redeemSso(ticket: String): SsoIdentity {
        val data = post("/host/sso/redeem", JsonObject().put("ticket", ticket))

        fun field(key: String) = data.getValue(key)?.toString()?.takeIf { it.isNotBlank() }
            ?: throw HostApiException(502, "MALFORMED_RESPONSE")

        return SsoIdentity(
            accountId = field("accountId"),
            email = field("email"),
            username = field("username"),
            role = field("role"),
            workloadId = field("workloadId"),
            hostname = field("hostname")
        )
    }

    fun close() = client.close()
}
