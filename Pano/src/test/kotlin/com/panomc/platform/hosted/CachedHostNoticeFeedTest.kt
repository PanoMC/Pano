package com.panomc.platform.hosted

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CachedHostNoticeFeedTest {
    private var now = 0L
    private var calls = 0
    private var next: () -> List<HostNotice>? = { emptyList() }
    private val errors = mutableListOf<Throwable>()

    private val feed = CachedHostNoticeFeed(
        fetch = { calls++; next() },
        ttlMs = 300_000, retryMs = 60_000, clock = { now }, onError = { errors += it }
    )

    private val disk = HostNotice("disk", "warning", "92%")

    @Test
    fun `caches for the ttl, keeps the last good list on errors and retries later`() = runBlocking {
        next = { listOf(disk) }
        assertEquals(listOf(disk), feed.notices())
        now += 299_000
        assertEquals(listOf(disk), feed.notices())
        assertEquals(1, calls)

        now += 1_000
        next = { throw PanoHostClient.HostApiException(503, "UNAVAILABLE") }
        assertEquals(listOf(disk), feed.notices())
        assertEquals(2, calls)
        assertEquals(1, errors.size)

        now += 59_000
        assertEquals(listOf(disk), feed.notices())
        assertEquals(2, calls, "no refetch inside the retry window")

        now += 1_000
        next = { emptyList() }
        assertEquals(emptyList<HostNotice>(), feed.notices())
        assertEquals(3, calls)
    }

    @Test
    fun `no client means no notices`() = runBlocking {
        next = { null }
        assertEquals(emptyList<HostNotice>(), feed.notices())
    }
}
