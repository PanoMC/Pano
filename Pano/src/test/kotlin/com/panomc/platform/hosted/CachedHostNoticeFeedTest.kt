package com.panomc.platform.hosted

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CachedHostNoticeFeedTest {
    private var now = 0L
    private var calls = 0
    private var next: () -> List<HostNotice>? = { emptyList() }
    private var nextManageUrl: String? = null
    private val errors = mutableListOf<Throwable>()

    private val feed = CachedHostNoticeFeed(
        fetch = { calls++; next()?.let { HostNotices(it, nextManageUrl) } },
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
    fun `keeps the last manage url the control plane sent`() = runBlocking {
        assertNull(feed.manageUrl)

        nextManageUrl = "https://local.panomc.com:3003/host/manage/instances/p-aaaaaaaaaa"
        feed.notices()
        assertEquals(nextManageUrl, feed.manageUrl)

        now += 300_000
        nextManageUrl = null
        feed.notices()
        assertEquals("https://local.panomc.com:3003/host/manage/instances/p-aaaaaaaaaa", feed.manageUrl)
    }

    @Test
    fun `no client means no notices`() = runBlocking {
        next = { null }
        assertEquals(emptyList<HostNotice>(), feed.notices())
    }

    @Test
    fun `a control plane that hangs costs a request the fetch timeout, not its own timeouts`() = runBlocking {
        val slow = CachedHostNoticeFeed(
            fetch = { kotlinx.coroutines.delay(30_000); HostNotices(listOf(disk)) },
            fetchTimeoutMs = 50, clock = { now }, onError = { errors += it }
        )

        val started = System.currentTimeMillis()
        assertEquals(emptyList<HostNotice>(), slow.notices())
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertTrue(errors.single() is java.util.concurrent.TimeoutException)

        // inside the retry window nobody waits at all
        assertEquals(emptyList<HostNotice>(), slow.notices())
        assertEquals(1, errors.size)
    }

    @Test
    fun `a request that finds a fetch running takes the cached list`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blocked = CachedHostNoticeFeed(
            fetch = { gate.await(); HostNotices(listOf(disk)) },
            fetchTimeoutMs = 10_000, clock = { now }
        )

        val first = async { blocked.notices() }
        kotlinx.coroutines.yield()

        assertEquals(emptyList<HostNotice>(), blocked.notices())

        gate.complete(Unit)
        assertEquals(listOf(disk), first.await())
    }
}
