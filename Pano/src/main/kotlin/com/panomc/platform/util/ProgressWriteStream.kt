package com.panomc.platform.util

import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.buffer.Buffer
import io.vertx.core.streams.WriteStream

class ProgressWriteStream(
    private val delegate: WriteStream<Buffer>,
    private var totalSize: Long = -1,
    private val onProgress: (Double) -> Unit
) : WriteStream<Buffer> {

    private var writtenBytes = 0L
    private var lastReportedProgress = -1.0

    fun setTotalSize(size: Long) {
        this.totalSize = size
        reportProgress()
    }

    override fun exceptionHandler(handler: Handler<Throwable>?): WriteStream<Buffer> {
        delegate.exceptionHandler(handler)
        return this
    }

    override fun write(data: Buffer): Future<Void> {
        updateProgress(data.length())
        return delegate.write(data)
    }

    override fun end(): Future<Void> {
        return delegate.end()
    }

    override fun setWriteQueueMaxSize(maxSize: Int): WriteStream<Buffer> {
        delegate.setWriteQueueMaxSize(maxSize)
        return this
    }

    override fun writeQueueFull(): Boolean {
        return delegate.writeQueueFull()
    }

    override fun drainHandler(handler: Handler<Void>?): WriteStream<Buffer> {
        delegate.drainHandler(handler)
        return this
    }

    private fun updateProgress(length: Int) {
        writtenBytes += length
        reportProgress()
    }

    private fun reportProgress() {
        if (totalSize > 0) {
            val progress = (writtenBytes.toDouble() / totalSize).coerceIn(0.0, 1.0)
            // Report progress every 1% to avoid flooding
            if (progress - lastReportedProgress >= 0.01 || progress >= 1.0) {
                lastReportedProgress = progress
                onProgress(progress)
            }
        }
    }
}
