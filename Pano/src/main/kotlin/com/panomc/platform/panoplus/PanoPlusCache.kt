package com.panomc.platform.panoplus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/**
 * Caches the Pano+ answer of panomc.com. There is no polling loop: a read fetches only when the
 * cached answer is older than [ttlMs] (or when the panel asks for a fresh one and the last fetch
 * is older than [minRefreshMs]). The cache is bound to [key] (the account token): another account
 * or a disconnect never sees the previous answer. A failed fetch keeps the last good answer for the
 * same key, or [PanoPlusInfo.DISABLED], and waits [retryMs] before trying again.
 */
open class PanoPlusCache(
    private val fetch: suspend () -> PanoPlusInfo,
    private val ttlMs: Long = 5 * 60_000L,
    private val retryMs: Long = 60_000L,
    private val minRefreshMs: Long = 15_000L,
    private val fetchTimeoutMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {}
) {
    private val mutex = Mutex()

    private var cachedKey: String? = null
    private var cached: PanoPlusInfo = PanoPlusInfo.DISABLED
    private var fetchedAt = Long.MIN_VALUE
    private var nextFetchAt = Long.MIN_VALUE

    /** [key] null = not connected: [PanoPlusInfo.DISABLED] without asking panomc.com. */
    suspend fun get(key: String?, refresh: Boolean = false): PanoPlusInfo = mutex.withLock {
        if (key == null) {
            reset()
            return PanoPlusInfo.DISABLED
        }

        if (key != cachedKey) {
            reset()
            cachedKey = key
        }

        val now = clock()
        val due = now >= nextFetchAt || (refresh && fetchedAt != Long.MIN_VALUE && now - fetchedAt >= minRefreshMs)

        if (!due) return cached

        try {
            val answer = withTimeoutOrNull(fetchTimeoutMs) { fetch() }
                ?: throw TimeoutException("panomc.com did not answer the Pano+ request within $fetchTimeoutMs ms")

            cached = answer
            fetchedAt = clock()
            nextFetchAt = fetchedAt + ttlMs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e)
            fetchedAt = clock()
            nextFetchAt = fetchedAt + retryMs
        }

        cached
    }

    /** Drops the cached answer; the next [get] asks panomc.com again. */
    suspend fun invalidate() = mutex.withLock { reset() }

    private fun reset() {
        cachedKey = null
        cached = PanoPlusInfo.DISABLED
        fetchedAt = Long.MIN_VALUE
        nextFetchAt = Long.MIN_VALUE
    }
}
