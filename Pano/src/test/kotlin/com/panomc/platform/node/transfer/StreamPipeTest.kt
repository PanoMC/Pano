package com.panomc.platform.node.transfer

import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.WriteStream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamPipeTest {
    private val vertx = Vertx.vertx()

    @AfterAll
    fun close() {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
    }

    /** A sink that reports full until [drain] is called, like a slow node. */
    private class SlowSink(private val limit: Int) : WriteStream<Buffer> {
        val received = Buffer.buffer()
        private var queued = 0
        private var drainHandler: Handler<Void>? = null

        override fun exceptionHandler(handler: Handler<Throwable>?) = this
        override fun write(data: Buffer): Future<Void> {
            received.appendBuffer(data)
            queued += data.length()

            return Future.succeededFuture()
        }

        override fun end(): Future<Void> = Future.succeededFuture()
        override fun setWriteQueueMaxSize(maxSize: Int) = this
        override fun writeQueueFull() = queued >= limit
        override fun drainHandler(handler: Handler<Void>?) = apply { drainHandler = handler }

        fun drain() {
            queued = 0
            drainHandler?.handle(null)
        }
    }

    @Test
    fun `bytes a blocking writer produces reach the sink in order, held back by a slow reader`() {
        val data = Random(1).nextBytes(3 * 1024 * 1024)
        val pipe = StreamPipe(vertx, maxQueuedBytes = 64 * 1024)
        val sink = SlowSink(256 * 1024)
        val relay = vertx.orCreateContext.let { context ->
            val started = java.util.concurrent.CompletableFuture<StreamRelay>()

            context.runOnContext { started.complete(StreamRelay.start(vertx, pipe.stream, sink)) }

            started.get(5, TimeUnit.SECONDS)
        }
        val drainer = vertx.setPeriodic(5) { sink.drain() }

        val writer = Thread {
            data.asList().chunked(10_000).forEach { pipe.output.write(it.toByteArray()) }
            pipe.output.close()
        }.apply { start() }

        relay.future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS)
        writer.join(5_000)
        vertx.cancelTimer(drainer)

        assertArrayEquals(data, sink.received.bytes)
    }

    @Test
    fun `a reader that goes away makes the writer's next write throw, a writer's failure fails the relay`() {
        val pipe = StreamPipe(vertx, maxQueuedBytes = 1024)
        val sink = SlowSink(Int.MAX_VALUE)
        val started = java.util.concurrent.CompletableFuture<StreamRelay>()

        vertx.orCreateContext.runOnContext { started.complete(StreamRelay.start(vertx, pipe.stream, sink)) }

        val relay = started.get(5, TimeUnit.SECONDS)

        relay.fail(IOException("The browser left."))

        assertThrows<IOException> {
            repeat(100) { pipe.output.write(ByteArray(1024)) }
        }

        val second = StreamPipe(vertx)
        val startedSecond = java.util.concurrent.CompletableFuture<StreamRelay>()

        vertx.orCreateContext.runOnContext { startedSecond.complete(StreamRelay.start(vertx, second.stream, SlowSink(Int.MAX_VALUE))) }

        val failing = startedSecond.get(5, TimeUnit.SECONDS)

        second.output.write(ByteArray(10))
        second.fail(IllegalStateException("The archive did not verify."))

        val error = runCatching { failing.future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS) }.exceptionOrNull()

        assertTrue(error?.cause is IllegalStateException, "the relay fails with the writer's reason: $error")
    }
}
