package com.panomc.platform.node.transfer

import io.vertx.core.Context
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.ReadStream
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bytes a blocking writer produces (a Pano Backup archive being downloaded and decrypted on an IO thread), read as a
 * Vert.x [ReadStream] (the node's or the browser's response, through [StreamRelay]).
 *
 * Nothing is kept beyond [maxQueuedBytes]: the writer blocks while the reading side is paused or behind, so a slow
 * node or browser slows the download from storage instead of filling Pano's memory or disk. The writer ends the
 * stream by closing [output] or fails it with [fail]; when the reading side went away, the writer's next write
 * throws, which is how it learns to stop.
 */
class StreamPipe(vertx: Vertx, private val maxQueuedBytes: Int = DEFAULT_MAX_QUEUED_BYTES) {
    private val context: Context = vertx.orCreateContext
    private val lock = ReentrantLock()
    private val roomLeft = lock.newCondition()
    private val queue = ArrayDeque<Buffer>()
    private var queuedBytes = 0
    private var ended = false
    private var failure: Throwable? = null
    private var cancelled: Throwable? = null

    // Only touched on [context].
    private var handler: Handler<Buffer>? = null
    private var endHandler: Handler<Void>? = null
    private var exceptionHandler: Handler<Throwable>? = null
    private var paused = true
    private var finished = false

    /** Where the writer writes. Closing it ends the stream; the reader sees its end after the last byte. */
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) {
                return
            }

            val chunk = Buffer.buffer(b.copyOfRange(off, off + len))

            lock.withLock {
                while (true) {
                    cancelled?.let { throw IOException(it.message ?: "The transfer was stopped.", it) }

                    if (ended) {
                        throw IOException("The stream was already closed.")
                    }

                    if (queuedBytes < maxQueuedBytes) {
                        break
                    }

                    roomLeft.await()
                }

                queue.addLast(chunk)
                queuedBytes += len
            }

            schedule()
        }

        override fun close() {
            lock.withLock {
                if (ended) {
                    return
                }

                ended = true
            }

            schedule()
        }
    }

    /** The reading side. Starts paused, like a request's file part, until somebody resumes it. */
    val stream: ReadStream<Buffer> = object : ReadStream<Buffer> {
        override fun exceptionHandler(handler: Handler<Throwable>?): ReadStream<Buffer> = apply { exceptionHandler = handler }

        override fun handler(handler: Handler<Buffer>?): ReadStream<Buffer> = apply {
            this@StreamPipe.handler = handler

            if (handler == null) {
                // The relay let go of the stream: nobody will read the rest.
                cancel(IOException("The reading side stopped."))
            }
        }

        override fun pause(): ReadStream<Buffer> = apply { paused = true }

        override fun resume(): ReadStream<Buffer> = apply {
            paused = false

            schedule()
        }

        override fun fetch(amount: Long): ReadStream<Buffer> = resume()

        override fun endHandler(endHandler: Handler<Void>?): ReadStream<Buffer> = apply { this@StreamPipe.endHandler = endHandler }
    }

    /** Fails the stream with [reason] (the reader's exception handler gets it), e.g. an archive that did not verify. */
    fun fail(reason: Throwable) {
        lock.withLock {
            if (ended || failure != null) {
                return
            }

            failure = reason
            ended = true
        }

        schedule()
    }

    /** The reading side is gone: the writer's next (or current, blocked) write throws [reason]. */
    fun cancel(reason: Throwable) {
        lock.withLock {
            if (cancelled == null) {
                cancelled = reason
            }

            queue.clear()
            queuedBytes = 0
            roomLeft.signalAll()
        }
    }

    private fun schedule() {
        context.runOnContext { deliver() }
    }

    private fun deliver() {
        if (finished) {
            return
        }

        while (!paused) {
            val chunk = lock.withLock {
                queue.pollFirst()?.also {
                    queuedBytes -= it.length()
                    roomLeft.signalAll()
                }
            } ?: break

            handler?.handle(chunk)
        }

        val (done, error) = lock.withLock { (ended && queue.isEmpty()) to failure }

        if (!done || (paused && error == null)) {
            return
        }

        finished = true

        if (error != null) {
            exceptionHandler?.handle(error)
        } else {
            endHandler?.handle(null)
        }
    }

    companion object {
        /** Enough to keep a fast link busy; the writer blocks above it. */
        const val DEFAULT_MAX_QUEUED_BYTES = 4 * 1024 * 1024
    }
}
