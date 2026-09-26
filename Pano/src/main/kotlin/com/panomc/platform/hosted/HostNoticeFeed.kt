package com.panomc.platform.hosted

import org.springframework.stereotype.Component

/**
 * A notice panomc.com shows on a hosted instance's panel (quota warnings, maintenance windows,
 * billing reminders). Informational only: nothing in the panel is locked by a notice.
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
    val createdAt: Long? = null
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
        "createdAt" to createdAt
    )
}

/** Source of [HostNotice]s for this instance; implementations should cache and fail soft. */
interface HostNoticeFeed {
    suspend fun notices(): List<HostNotice>
}

/**
 * Stub feed: the control plane has no instance notice route yet.
 * TODO(W3): add an instance-secret-authenticated notice feed route (e.g. `GET /host/instance/notices`)
 *  and back this with [PanoHostClient] (cache ~5 min, empty list on failure).
 */
@Component
class StubHostNoticeFeed : HostNoticeFeed {
    override suspend fun notices(): List<HostNotice> = emptyList()
}
