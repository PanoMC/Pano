package com.panomc.platform.route.api.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.RateLimited
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.webhook.WebhookNotFound
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/** `POST /webhooks/:id/test`: sends `core.test.ping` now and answers `{ statusCode, durationMs, error }`. */
@Endpoint
class PanelTestWebhookAPI : WebhookPanelApi() {
    override val paths = listOf(Path("/webhooks/:id/test", RouteType.POST))

    override val doc = EndpointDoc(summary = "Sends a test ping to a webhook endpoint now.", tag = "webhooks", errors = listOf(WebhookNotFound::class, RateLimited::class))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = idValidation(schemaRepository)

    override suspend fun execute(context: RoutingContext): Result {
        if (!limiter.tryAcquire("user:${userIdOf(context)}")) throw RateLimited(extras = mapOf("retryAfter" to TEST_WINDOW_MS / 1000))

        return Successful(service.test(idOf(context)))
    }

    /** The test button is limited to 10 a minute per panel user. */
    private val limiter = testLimiter()

    internal companion object {
        const val TEST_PER_MINUTE = 10
        const val TEST_WINDOW_MS = 60_000L

        fun testLimiter(clock: () -> Long = System::currentTimeMillis) = SlidingWindowLimiter(TEST_PER_MINUTE, TEST_WINDOW_MS, clock)
    }
}

/** At most [max] calls per key in any [windowMs]; a refused call does not count. */
internal class SlidingWindowLimiter(private val max: Int, private val windowMs: Long, private val clock: () -> Long) {
    private val calls = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        val queue = calls.computeIfAbsent(key) { java.util.ArrayDeque() }

        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.peekFirst() >= windowMs) queue.pollFirst()

            if (queue.size >= max) return false

            queue.addLast(now)

            return true
        }
    }
}
