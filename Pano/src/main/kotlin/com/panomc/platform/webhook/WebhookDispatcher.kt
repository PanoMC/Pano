package com.panomc.platform.webhook

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tick of the outbound webhook queue (doc 06 §4.1): every [intervalMs] (5 s) claim up to [batch] (20) due
 * rows, send at most [concurrency] (5) at a time, the rows of one endpoint one after the other in id order, store every
 * outcome. Once a day it purges finished rows older than 30 days. Safe to run twice and to be killed mid-row: the claim
 * expires after 60 s and the row is retried (receivers de-duplicate on `X-Pano-Event-Id`).
 *
 * One tick at a time: a call that arrives while another is running returns 0 at once. [tick] never throws (a
 * timer must not stop); `CancellationException` is rethrown.
 *
 * [start] arms the timer once; later calls do nothing. The publisher calls it on first use, so a site that never
 * publishes pays nothing; `Main` may call [start] at boot so rows left over from the last run go out at once.
 */
class WebhookDispatcher(
    private val vertx: Vertx,
    private val service: WebhookService,
    /** `false` while the platform is not installed: there is no database to read yet. */
    private val ready: () -> Boolean = { true },
    private val concurrency: Int = 5,
    private val batch: Int = WebhookService.CLAIM_BATCH,
    private val intervalMs: Long = 5_000L,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + vertx.dispatcher())

    @Volatile
    private var timerId: Long? = null

    @Volatile
    private var lastPurgeAt = 0L

    init {
        require(concurrency >= 1) { "concurrency must be at least 1" }
    }

    /** Arms the 5 s timer (once). */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        timerId = vertx.setPeriodic(intervalMs) {
            scope.launch { tick() }
        }
    }

    /** Cancels the timer; a tick in progress finishes. [start] can arm it again. */
    fun stop() {
        timerId?.let { vertx.cancelTimer(it) }
        timerId = null
        started.set(false)
    }

    val isStarted: Boolean get() = started.get()

    /** Rows claimed and handled by this call (0 when nothing was due or a tick was already running). */
    suspend fun tick(): Int {
        if (!ready()) return 0
        if (!running.compareAndSet(false, true)) return 0

        try {
            purgeIfDue()

            val claimed = service.claimDue(batch)

            if (claimed.isEmpty()) return 0

            val semaphore = Semaphore(concurrency)
            // Rows of one endpoint form one sequential chain; direct rows are independent.
            val chains = claimed.groupBy { if (it.endpointId != 0L) "e${it.endpointId}" else "r${it.id}" }.values

            coroutineScope {
                chains.map { chain ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
                            for (row in chain.sortedBy { it.id }) {
                                try {
                                    service.process(row)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    // The row stays SENDING; its claim expires and the next tick retries it.
                                    logger.error("Webhook delivery {} could not be processed: {}", row.id, t.toString())
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            return claimed.size
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Webhook dispatcher tick failed: {}", t.toString())

            return 0
        } finally {
            running.set(false)
        }
    }

    private suspend fun purgeIfDue() {
        val at = now()

        if (at - lastPurgeAt < PURGE_INTERVAL_MS) return

        lastPurgeAt = at

        try {
            val purged = service.purgeOld()

            if (purged > 0) logger.info("Purged {} finished webhook deliveries older than 30 days", purged)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("Webhook purge failed: {}", t.toString())
        }
    }

    private companion object {
        const val PURGE_INTERVAL_MS = 24L * 3_600_000L
        val logger = LoggerFactory.getLogger(WebhookDispatcher::class.java)
    }
}
