package com.panomc.node

import com.panomc.node.task.TaskProgressThrottle
import com.panomc.node.util.Downloader
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class DownloadProgressTest {
    @TempDir
    lateinit var directory: File

    private val body = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }

    /** Serves [body] in slices with a pause between them, so a download lasts long enough to be measured. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file") { exchange ->
            val sized = exchange.requestURI.query != "unsized"

            exchange.sendResponseHeaders(200, if (sized) body.size.toLong() else 0L)

            exchange.responseBody.use { out ->
                body.toList().chunked(256 * 1024).forEach { slice ->
                    out.write(slice.toByteArray())
                    out.flush()
                    Thread.sleep(60)
                }
            }
        }
        start()
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/file"

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `a download reports its bytes, size and rate, and ends on the whole size`() {
        val seen = CopyOnWriteArrayList<Downloader.Progress>()

        Downloader.download(url, File(directory, "a.jar"), onBytes = { seen.add(it) }) { }

        assertTrue(seen.size >= 2, "expected several readings, got ${seen.size}")
        assertEquals(body.size.toLong(), seen.last().done)
        assertEquals(body.size.toLong(), seen.last().total)
        assertEquals(1.0, seen.last().fraction)
        assertNotNull(seen.last().bytesPerSecond)
        assertTrue(seen.zipWithNext().all { (a, b) -> b.done >= a.done }, "bytes never go backwards")
    }

    @Test
    fun `without a length the bytes still arrive and there is no fraction`() {
        val seen = CopyOnWriteArrayList<Downloader.Progress>()

        Downloader.download("$url?unsized", File(directory, "b.jar"), onBytes = { seen.add(it) }) { }

        assertEquals(body.size.toLong(), seen.last().done)
        assertEquals(-1L, seen.last().total)
        assertNull(seen.last().fraction)
    }

    @Test
    fun `the throttle keeps a download's bytes with the frame it holds and sends`() {
        var now = 0L
        val throttle = TaskProgressThrottle { now }
        val progress = Downloader.Progress(100, 1000, 50)

        assertEquals(progress, throttle.offer("t", 10, "Downloading", progress)?.transfer)

        now += 10

        val later = Downloader.Progress(200, 1000, 60)

        assertNull(throttle.offer("t", 11, "Downloading", later))
        assertEquals(later, throttle.flush("t")?.transfer)
    }
}
