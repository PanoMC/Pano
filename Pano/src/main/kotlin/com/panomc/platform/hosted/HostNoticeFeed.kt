package com.panomc.platform.hosted

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * A notice panomc.com shows on a hosted instance's panel (quota warnings, maintenance windows,
 * billing reminders), from `GET /host/instance/notices` (host-api.md §Instance routes). Informational only: nothing in the panel is locked by a notice.
 */
data class HostNotice(
    val id: String,
    /** `info`, `warning` or `critical`. */
    val level: String,
    val message: String,
    val title: String? = null,
    /** Optional http(s) link, e.g. the relevant page on panomc.com. */
    val url: String? = null,
    /** Epoch millis. */
    val createdAt: Long? = null,
    /** Control-plane type code (e.g. `HOST_DISK_QUOTA`) so the panel can localise the text. */
    val type: String? = null,
    /** Values for the localised text (numbers / short strings only), e.g. `{percent: 92}`. */
    val data: Map<String, Any?> = emptyMap()
) {
    companion object {
        val LEVELS = setOf("info", "warning", "critical")
    }

    fun toMap() = mapOf(
        "id" to id,
        "level" to level,
        "title" to title,
        "message" to message,
        "url" to url,
        "createdAt" to createdAt,
        "type" to type,
        "data" to data
    )
}

/** Source of [HostNotice]s for this instance; implementations should cache and fail soft. */
interface HostNoticeFeed {
    suspend fun notices(): List<HostNotice>
}

/**
 * The control plane's feed (`GET /host/instance/notices`, instance secret), cached for
 * [ttlMs] and fetched by one caller at a time. Fails soft: on an error the last good list is kept
 * (or `[]`) and the next fetch waits [retryMs], so a slow or down control plane never slows the panel.
 */
open class CachedHostNoticeFeed(
    private val fetch: suspend () -> List<HostNotice>?,
    private val ttlMs: Long = 5 * 60_000L,
    private val retryMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {}
) : HostNoticeFeed {
    private val mutex = Mutex()

    @Volatile
    private var cached: List<HostNotice> = emptyList()

    @Volatile
    private var nextFetchAt = Long.MIN_VALUE

    override suspend fun notices(): List<HostNotice> {
        if (clock() < nextFetchAt) return cached

        return mutex.withLock {
            if (clock() < nextFetchAt) return@withLock cached

            try {
                cached = fetch() ?: emptyList()
                nextFetchAt = clock() + ttlMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onError(e)
                nextFetchAt = clock() + retryMs
            }

            cached
        }
    }
}

/** The instance's feed: [CachedHostNoticeFeed] over [PanoHostClient.notices]; `[]` when not hosted. */
@Lazy
@Component
class ControlPlaneHostNoticeFeed(panoHostManager: PanoHostManager, logger: Logger) : CachedHostNoticeFeed(
    fetch = { panoHostManager.client?.notices() },
    onError = { logger.warn("Pano Host notice feed unavailable: {}", (it as? PanoHostClient.HostApiException)?.message ?: it.javaClass.simpleName) }
)
