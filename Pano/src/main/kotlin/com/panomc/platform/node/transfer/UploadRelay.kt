package com.panomc.platform.node.transfer

import com.panomc.platform.error.BadRequest
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerFileUpload
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.streams.ReadStream
import io.vertx.core.streams.WriteStream
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Where the browser's half and the node's half of one upload meet.
 *
 * Pano does not keep an upload: the browser's request and the node's `GET /api/v1/node/transfer/<ticket>`
 * are joined together and the bytes go straight from one to the other. The two requests arrive
 * separately and in either order (a file-manager upload: the browser first, then the node it was
 * pushed to; an import: the node first, parked since `servers/create`, then the browser), so each
 * side waits here for the other.
 */
class UploadRendezvous(
    /** What the browser announced, when it did; the node uses it for its disk check and progress. */
    @Volatile var declaredSize: Long? = null
) {
    /** Completed by the browser side once its file part is paused and ready to flow. */
    val source = CompletableDeferred<UploadSource>()

    /** Completed by the node side with the byte count once every byte reached the node. */
    val delivered = CompletableDeferred<Long>()

    /** Ends both halves with [reason]: the other side stops waiting and fails with it. */
    fun abandon(reason: Throwable) {
        if (!source.isCompleted) {
            source.completeExceptionally(reason)
        }

        delivered.completeExceptionally(reason)
    }
}

/** The browser's file, paused at its first byte until the node is there to take it. */
class UploadSource(
    val stream: ReadStream<Buffer>,
    val fileName: String,
    /** Sees every chunk before it is passed on; throwing stops the upload (a jar that is no zip). */
    val inspect: (Buffer) -> Unit = {}
)

/**
 * A running copy from one stream into another, with a watchdog for a side that stops moving.
 *
 * Hand-rolled rather than `pipeTo` for two reasons: every chunk passes [UploadSource.inspect] on
 * the way, and the copy has to fail by itself when no byte has moved for [idleTimeoutMs] — a
 * browser that went to sleep or a node that hung would otherwise hold both requests open forever,
 * now that a transfer has no total time limit.
 */
class StreamRelay private constructor(
    private val vertx: Vertx,
    private val source: ReadStream<Buffer>,
    private val sink: WriteStream<Buffer>,
    private val idleTimeoutMs: Long,
    private val onChunk: (Buffer) -> Unit
) {
    private val promise = Promise.promise<Long>()

    /** Completes with the byte count when the source ends, or fails with whatever stopped it. */
    val future: Future<Long> = promise.future()

    @Volatile
    var bytes = 0L
        private set

    @Volatile
    private var lastActivity = System.currentTimeMillis()

    private var watchdog = -1L

    /** Stops the copy with [reason]; harmless once it has ended. */
    fun fail(reason: Throwable) {
        if (!promise.tryFail(reason)) {
            return
        }

        stop()
    }

    private fun stop() {
        if (watchdog >= 0) {
            vertx.cancelTimer(watchdog)
        }

        runCatching { source.handler(null) }
        runCatching { source.endHandler(null) }
    }

    private fun start() {
        watchdog = vertx.setPeriodic(WATCH_INTERVAL_MS) {
            if (System.currentTimeMillis() - lastActivity > idleTimeoutMs) {
                fail(TimeoutException("No data moved for ${idleTimeoutMs / 1000} seconds."))
            }
        }

        sink.exceptionHandler { fail(it) }
        sink.drainHandler {
            lastActivity = System.currentTimeMillis()

            if (!future.isComplete) {
                source.resume()
            }
        }

        source.exceptionHandler { fail(it) }

        source.endHandler {
            if (promise.tryComplete(bytes)) {
                stop()
            }
        }

        source.handler { chunk ->
            if (future.isComplete) {
                return@handler
            }

            lastActivity = System.currentTimeMillis()

            try {
                onChunk(chunk)

                bytes += chunk.length()

                sink.write(chunk)
            } catch (exception: Throwable) {
                fail(exception)

                return@handler
            }

            if (sink.writeQueueFull()) {
                source.pause()
            }
        }

        source.resume()
    }

    companion object {
        /** No byte in either direction for this long ends a transfer (owner decision, 2026-11-25). */
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        private const val WATCH_INTERVAL_MS = 15 * 1000L

        /** Starts copying [source] into [sink]. The sink is not ended: that is the caller's to do. */
        fun start(
            vertx: Vertx,
            source: ReadStream<Buffer>,
            sink: WriteStream<Buffer>,
            idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
            onChunk: (Buffer) -> Unit = {}
        ): StreamRelay = StreamRelay(vertx, source, sink, idleTimeoutMs, onChunk).also { it.start() }
    }
}

/** The browser closed the connection before its upload was finished. */
class UploadAborted(message: String = "The upload was interrupted.") : IOException(message)

/**
 * Reading the browser's side of an upload without Vert.x storing it.
 *
 * The route has no body handler; its request arrives paused (see `Api.authorizedBodyHandler`).
 * This switches it to multipart, waits for the first file part and pauses that part again, so not a
 * byte flows until the node is there to receive it.
 */
object BrowserUpload {
    /** The multipart field every panel upload uses. */
    const val PART_NAME = "file"

    /**
     * The first file part of [request], paused. Any other part is drained and ignored; a request that
     * ends without a file is a [BadRequest].
     */
    suspend fun firstFilePart(request: HttpServerRequest): HttpServerFileUpload {
        val part = CompletableDeferred<HttpServerFileUpload>()

        request.setExpectMultipart(true)

        request.uploadHandler { upload ->
            if (part.isCompleted) {
                // Only one file per request: whatever else arrives is read and dropped, so the
                // request can still end.
                upload.handler { }

                return@uploadHandler
            }

            upload.pause()

            part.complete(upload)
        }

        request.exceptionHandler { failure ->
            if (!part.isCompleted) {
                part.completeExceptionally(UploadAborted(failure.message ?: "The upload was interrupted."))
            }
        }

        request.endHandler {
            if (!part.isCompleted) {
                part.completeExceptionally(BadRequest())
            }
        }

        request.connection()?.closeHandler {
            if (!part.isCompleted) {
                part.completeExceptionally(UploadAborted())
            }
        }

        request.resume()

        return part.await()
    }

    /** What the browser announced: the request's length, which is the file plus a little multipart framing. */
    fun declaredSize(request: HttpServerRequest): Long? =
        request.getHeader("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

    /**
     * Lets the browser read an answer that comes before its upload ended, then closes the connection.
     *
     * Without the close the rest of the body would still be sent, possibly gigabytes, only to be
     * thrown away.
     */
    fun closeAfterResponse(request: HttpServerRequest) {
        if (request.isEnded) {
            return
        }

        val response = request.response()

        response.putHeader("Connection", "close")

        response.endHandler { runCatching { request.connection()?.close() } }
    }
}
