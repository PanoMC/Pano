package com.panomc.platform.backup.remote

import com.panomc.platform.archive.instance.InstanceLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TransferEstimateTest {
    @TempDir
    lateinit var root: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000L

    private fun file(path: String, size: Int) = File(root, path).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(size)) }

    private fun layout(): InstanceLayout {
        file("config.conf", 10)
        file("plugins/a.jar", 100)
        file("themes/t/x.css", 20)
        file("themes/t/.pano-license.jwt", 999)
        file("file-uploads/img.png", 300)
        file("file-uploads/cache/c.bin", 999)
        file("file-uploads/temp/t.bin", 999)
        file("maintenance/m.html", 5)

        return InstanceLayout(File(root, "config.conf"), File(root, "plugins"), File(root, "themes"), File(root, "file-uploads"), File(root, "maintenance"), File(root, ".temp"))
    }

    private suspend fun settled(estimate: TransferEstimate): io.vertx.core.json.JsonObject {
        repeat(200) {
            val answer = estimate.get()

            if (answer.getString("status") != TransferEstimate.CALCULATING) return answer

            delay(10)
        }

        return estimate.get()
    }

    @Test
    fun `counts what an archive carries, answers at once, and caches within the ttl`(): Unit = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val estimate = TransferEstimate(scope, ::layout, { calls.incrementAndGet(); gate.await(); 1_000L }, { now }, ttlMs = 100)

        // The first answer does not wait for the calculation; asking again meanwhile starts nothing new (single flight).
        assertEquals("CALCULATING", estimate.get().getString("status"))
        assertEquals("CALCULATING", estimate.get().getString("status"))

        gate.complete(Unit)

        val done = settled(estimate)

        // config 10 + plugins 100 + themes 20 (license token left out) + uploads 300 (cache, temp left out) + maintenance 5 + database 1000.
        assertEquals("DONE", done.getString("status"))
        assertEquals(1_435L, done.getLong("bytes"))
        assertEquals(5L, done.getLong("files"))
        assertEquals(now, done.getLong("calculatedAt"))
        assertEquals(1, calls.get())

        // Within the ttl the cached figure is served without calculating again.
        now += 50
        assertEquals(done.getLong("bytes"), estimate.get().getLong("bytes"))
        assertEquals(1, calls.get())

        // After it a new calculation starts, and the old figure is served until it is ready.
        now += 100
        file("plugins/b.jar", 1000)
        assertEquals("DONE", estimate.get().getString("status"))
        delay(300)
        assertEquals(2_435L, estimate.get().getLong("bytes"))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a failed calculation reports FAILED and is not retried within the ttl`(): Unit = runBlocking {
        val calls = AtomicInteger()
        val estimate = TransferEstimate(scope, { calls.incrementAndGet(); throw IllegalStateException("no layout") }, { 0L }, { now }, ttlMs = 100)

        assertEquals("CALCULATING", estimate.get().getString("status"))
        assertEquals("FAILED", settled(estimate).getString("status"))
        assertNull(estimate.get().getLong("bytes"))
        assertEquals(1, calls.get())

        now += 200
        estimate.get()
        delay(100)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a database that cannot answer still gives the files`(): Unit = runBlocking {
        val estimate = TransferEstimate(scope, ::layout, { throw IllegalStateException("down") }, { now })

        assertEquals(435L, settled(estimate).getLong("bytes"))
    }
}
