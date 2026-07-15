package com.panomc.platform.util

import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.api.PanoPlugin
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerResponse
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.io.InputStream
import kotlin.coroutines.resume

object FileResourceUtil {
    private val logger = LoggerFactory.getLogger(FileResourceUtil::class.java)

    fun PanoPlugin.getResource(resourceName: String): InputStream? =
        this.javaClass.classLoader.getResourceAsStream(resourceName)

    fun PanoPluginWrapper.getResource(resourceName: String): InputStream? =
        this.pluginClassLoader.getResourceAsStream(resourceName)

    /**
     * Streams the input stream into the response without blocking the event loop and while
     * honoring back-pressure. Reads are performed on a worker thread (the blocking
     * [InputStream.read] used to stall the event loop) and writes pause whenever the response
     * write queue is full, resuming on drain. On a mid-stream failure the response is reset so
     * we don't leave a dangling/corrupt chunked body for the client to misinterpret.
     */
    suspend fun InputStream.writeToResponse(vertx: Vertx, response: HttpServerResponse) {
        val bufferSize = 8 * 1024
        try {
            this.use { stream ->
                while (true) {
                    val chunk = vertx.executeBlocking<ByteArray?> {
                        val buffer = ByteArray(bufferSize)
                        val bytesRead = stream.read(buffer)
                        if (bytesRead == -1) null else buffer.copyOfRange(0, bytesRead)
                    }.coAwait() ?: break

                    response.write(Buffer.buffer(chunk))

                    if (response.writeQueueFull()) {
                        awaitDrain(response)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed while streaming resource to response", e)
            if (!response.ended()) {
                try {
                    response.reset()
                } catch (_: Exception) {
                    // Response already closed; nothing more to clean up.
                }
            }
            throw e
        }
    }

    private suspend fun awaitDrain(response: HttpServerResponse) = suspendCancellableCoroutine { cont ->
        response.drainHandler {
            response.drainHandler(null)
            if (cont.isActive) cont.resume(Unit)
        }
    }
}
