package com.panomc.platform.util

import io.vertx.core.Future
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod
import io.vertx.httpproxy.Body
import io.vertx.httpproxy.ProxyContext
import io.vertx.httpproxy.ProxyInterceptor
import io.vertx.httpproxy.ProxyResponse
import org.slf4j.Logger

/**
 * Retries an idempotent upstream request when the UI upstream drops the pooled keep-alive
 * connection before answering.
 *
 * Bun 1.3.x closed a kept-alive socket without responding when the next request landed within
 * ~2 ms of the previous response. The Vert.x pool reuses a connection the instant it frees up,
 * so bursts of uncached asset requests turned into silent 502s; Cloudflare then serves its HTML
 * error page for a JS module and hydration dies. [ProxyContext.sendRequest] only fails BEFORE a
 * response head reached us, so nothing has been written downstream yet and a fresh attempt is
 * safe for GET/HEAD/OPTIONS.
 *
 * Only FAST failures are retried: a dropped connection fails in well under a millisecond and a
 * refused connection in a few, whereas an upstream that accepted the request and then hung only
 * fails at the client's idle timeout. Without [retryBudgetMs] such a hang would be waited out
 * once per attempt; with it the worst case stays exactly what it is without this interceptor.
 *
 * The inbound (empty) body stream was already consumed by the first attempt, so it is swapped for
 * an empty buffer before retrying; idempotent methods carry no body anyway. It must stay a real
 * [Body] (never null): the proxy's own failure path calls `release()`, which touches the body
 * stream, and a null there would leave the downstream request unanswered.
 *
 * Both outcomes are logged at DEBUG on purpose (operator preference): a retried drop heals
 * itself, and the proxy still answers 502 when the budget is exhausted.
 */
class UpstreamRetryInterceptor(
    private val logger: Logger,
    private val uiId: String,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryBudgetMs: Long = DEFAULT_RETRY_BUDGET_MS,
) : ProxyInterceptor {
    override fun handleProxyRequest(context: ProxyContext): Future<ProxyResponse> {
        val request = context.request()
        val method = request.method
        val path = request.proxiedRequest().path() ?: ""
        val retryable = isIdempotent(method)

        fun attempt(attemptNo: Int): Future<ProxyResponse> {
            val startedAt = System.nanoTime()

            return context.sendRequest().recover { error ->
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                if (retryable && attemptNo < maxRetries && elapsedMs < retryBudgetMs) {
                    logger.debug(
                        "\"{}\" upstream dropped {} {} before responding after {}ms ({}); retrying {}/{}",
                        uiId, method, path, elapsedMs, error.message, attemptNo + 1, maxRetries
                    )
                    request.setBody(Body.body(Buffer.buffer()))
                    attempt(attemptNo + 1)
                } else {
                    logger.debug(
                        "\"{}\" upstream failed for {} {} after {}ms ({}); answering 502",
                        uiId, method, path, elapsedMs, error.message
                    )
                    Future.failedFuture(error)
                }
            }
        }

        return attempt(0)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_RETRY_BUDGET_MS = 2_000L

        fun isIdempotent(method: HttpMethod): Boolean =
            method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS
    }
}
