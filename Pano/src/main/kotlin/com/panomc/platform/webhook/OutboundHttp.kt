package com.panomc.platform.webhook

import com.panomc.platform.webhook.guard.UrlGuard

import com.panomc.platform.webhook.guard.TargetPolicy
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.core.net.SocketAddress
import io.vertx.core.streams.WriteStream
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeoutException

/** Resolves a host name to every address it has (`InetAddress.getAllByName`); throws when it cannot. */
fun interface HostResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/** The production resolver: the blocking lookup runs inside `vertx.executeBlocking`, limited to [timeoutMs]. */
class VertxHostResolver(private val vertx: Vertx, private val timeoutMs: Long = 5_000L) : HostResolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        withTimeout(timeoutMs) {
            vertx.executeBlocking<List<InetAddress>> { InetAddress.getAllByName(host).toList() }.coAwait()
        }
}

/**
 * The only client for admin-configured URLs (11 section 7.3): webhooks (endpoints, plugin direct deliveries), test pings. It owns
 * a dedicated [WebClient] (the host bean follows redirects and must not be used here).
 *
 * - **Guard before every request**: the URL is validated and the host resolved *now* ([TargetPolicy]); a refusal never
 *   reaches the network and is returned as an [Attempt] with `error = URL_GUARD:<reason>`.
 * - **DNS pinning**: the connection goes to the validated address (`requestAbs(method, SocketAddress(ip), url)`); `Host`,
 *   SNI and certificate verification keep the original host name. No redirect is followed: a 3xx answer is a failed
 *   attempt (`REDIRECT_NOT_FOLLOWED`, status kept). TLS verification is always on.
 * - **Bounded**: connect timeout 5 s and a total [totalTimeoutMs] (10 s) per request; at most [maxResponseBytes] of the
 *   response body are kept, the rest is discarded as it streams in without being buffered.
 */
class OutboundHttp(
    private val client: WebClient,
    private val resolver: HostResolver,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES
) {
    /** The outcome of [check]: the validated target and the address the connection will use, or the refusal. */
    class Checked(val result: TargetPolicy.Result) {
        val pinned: InetAddress? get() = result.allowed?.addresses?.firstOrNull()
        val refusal: UrlGuard.Reason? get() = (result as? TargetPolicy.Result.Refused)?.reason
    }

    /** Validates [url] and resolves its host (steps 1 to 9 of 11 section 7.2). Never throws except for cancellation. */
    suspend fun check(url: String, allowPrivate: Boolean, discord: Boolean = false): Checked {
        val verdict = UrlGuard.validate(url, allowPrivate, discord)
        val target = verdict.target ?: return Checked(TargetPolicy.Result.Refused(verdict.reason!!))

        val resolved: List<InetAddress>? = if (target.literal != null) {
            null
        } else {
            try {
                resolver.resolve(target.host)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) null else throw e
            } catch (e: Exception) {
                null
            }
        }

        return Checked(TargetPolicy.check(url, resolved, allowPrivate, discord))
    }

    /**
     * POST [body] to [url] with [headers] (already complete: the caller adds `Content-Type`, `X-Pano-*`, the signature).
     * Always returns; transport problems come back as `error` (`TIMEOUT`, `IO:<class>`).
     */
    suspend fun post(url: String, headers: List<Pair<String, String>>, body: ByteArray, allowPrivate: Boolean, discord: Boolean = false): Attempt {
        val checked = check(url, allowPrivate, discord)
        val allowed = checked.result.allowed
        if (allowed == null) {
            val reason = checked.refusal!!
            return Attempt(error = TargetPolicy.lastErrorOf(reason), retryable = reason.retryable)
        }

        val target = allowed.target
        val ip = allowed.addresses.first()
        val started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000L).toInt()

        val sink = LimitedSink(maxResponseBytes)

        return try {
            withTimeout(totalTimeoutMs) {
                val request = client.requestAbs(HttpMethod.POST, SocketAddress.inetSocketAddress(target.port, ip.hostAddress), target.url)
                    .`as`(BodyCodec.pipe(sink, false))
                    .timeout(totalTimeoutMs)
                for ((name, value) in headers) request.putHeader(name, value)
                val response = request.sendBuffer(Buffer.buffer(body)).coAwait()
                val code = response.statusCode()
                Attempt(
                    statusCode = code,
                    error = if (code in 300..399) "REDIRECT_NOT_FOLLOWED" else null,
                    response = sink.text(),
                    durationMs = elapsed(),
                    retryAfter = response.getHeader("Retry-After")
                )
            }
        } catch (e: TimeoutCancellationException) {
            Attempt(error = "TIMEOUT", durationMs = elapsed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val timeout = e is TimeoutException || e.cause is TimeoutException || e.javaClass.simpleName.contains("Timeout", ignoreCase = true)
            Attempt(error = if (timeout) "TIMEOUT" else "IO:${e.javaClass.simpleName}", durationMs = elapsed())
        }
    }

    /** Keeps the first [limit] bytes written to it and drops the rest; never signals back pressure. */
    private class LimitedSink(private val limit: Int) : WriteStream<Buffer> {
        private val kept = ByteArrayOutputStream()

        @Synchronized
        fun text(): String = String(kept.toByteArray(), Charsets.UTF_8).take(limit)

        @Synchronized
        override fun write(data: Buffer): Future<Void> {
            val room = limit - kept.size()
            if (room > 0) kept.write(data.bytes, 0, minOf(room, data.length()))
            return Future.succeededFuture()
        }

        override fun end(): Future<Void> = Future.succeededFuture()
        override fun exceptionHandler(handler: Handler<Throwable>?): WriteStream<Buffer> = this
        override fun setWriteQueueMaxSize(maxSize: Int): WriteStream<Buffer> = this
        override fun writeQueueFull(): Boolean = false
        override fun drainHandler(handler: Handler<Void>?): WriteStream<Buffer> = this
    }

    companion object {
        const val DEFAULT_TOTAL_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_RESPONSE_BYTES = 2_048

        /**
         * A production-shaped client: no redirects, 5 s connect timeout, `User-Agent: Pano/<version>`, no
         * compression, TLS verification on. [customize] is for tests (a trust store for a throw-away certificate); it
         * must not weaken verification in production code.
         */
        fun create(
            vertx: Vertx,
            version: String,
            resolver: HostResolver = VertxHostResolver(vertx),
            totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
            customize: (WebClientOptions) -> Unit = {}
        ): OutboundHttp {
            val options = WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setUserAgent("Pano/$version")
                .setDecompressionSupported(false)
                .setVerifyHost(true)
            customize(options)
            return OutboundHttp(WebClient.create(vertx, options), resolver, totalTimeoutMs)
        }
    }
}
