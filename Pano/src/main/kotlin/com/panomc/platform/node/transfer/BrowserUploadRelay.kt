package com.panomc.platform.node.transfer

import com.panomc.platform.error.TransferFailed
import com.panomc.platform.model.Error
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerFileUpload
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * The browser side of every relayed upload: file manager, plugin jars and the create wizard's archive.
 *
 * The endpoint's request arrives paused and without a body handler. This takes its file part, lets
 * the endpoint decide where it goes ([Prepared]: the ticket, and for a file pushed into a server the
 * node's answer), hands the part to the node's request through the ticket and waits for the outcome.
 * Nothing of the file is ever on Pano's disk or held in its heap beyond a write queue.
 *
 * Every failure is also recorded under the panel's `uploadId` (see [TransferTicketStore.failure]):
 * an answer sent before the browser finished sending is followed by closing the connection, and the
 * browser then usually reports a network error instead of reading it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class BrowserUploadRelay(
    private val transferTicketStore: TransferTicketStore,
    private val logger: Logger
) {
    /** Where an upload goes, decided once its file name is known. */
    class Prepared(
        /** An UPLOAD ticket carrying an [UploadRendezvous]. */
        val ticket: TransferTicket,
        /**
         * The node's answer to the push of a file into a server, which comes once the file is written.
         * Null for an import: there the node's import task carries the outcome, and the upload is done
         * once every byte reached the node.
         */
        val reply: Deferred<JsonObject>? = null
    )

    /** What a finished upload hands back: the node's answer, and the bytes that crossed. */
    class Outcome(val reply: JsonObject?, val bytes: Long?)

    /**
     * Receives the upload on [context] and relays it as [prepare] decides.
     *
     * [prepare] gets the paused file part and the size the browser announced, and may throw (a
     * forbidden name, a file that does not fit): the upload then never starts.
     */
    suspend fun relay(
        context: RoutingContext,
        uploadId: String?,
        userId: Long,
        inspect: (Buffer) -> Unit = {},
        prepare: suspend (part: HttpServerFileUpload, declaredSize: Long?) -> Prepared
    ): Outcome {
        val request = context.request()

        val part = try {
            BrowserUpload.firstFilePart(request)
        } catch (exception: UploadAborted) {
            transferTicketStore.recordFailure(uploadId, userId, ERROR_ABORTED)

            throw TransferFailed()
        }

        val declared = BrowserUpload.declaredSize(request)

        val prepared = try {
            prepare(part, declared)
        } catch (exception: Throwable) {
            record(uploadId, userId, exception)

            BrowserUpload.closeAfterResponse(request)

            throw exception
        }

        val upload = prepared.ticket.upload
            ?: throw IllegalStateException("A relayed upload needs a ticket with a rendezvous.")

        upload.declaredSize = declared

        request.connection()?.closeHandler {
            if (!upload.delivered.isCompleted) {
                upload.abandon(UploadAborted())
            }
        }

        upload.source.complete(UploadSource(part, part.filename().orEmpty(), inspect))

        try {
            val reply = prepared.reply

            if (reply == null) {
                return Outcome(null, upload.delivered.await())
            }

            return coroutineScope {
                // A node that died mid-transfer never answers the push; once its request broke, the
                // answer gets a short grace period to arrive with the reason, then the wait ends.
                val watcher = launch {
                    try {
                        upload.delivered.await()
                    } catch (exception: Exception) {
                        delay(REPLY_GRACE_MS)

                        reply.cancel(kotlinx.coroutines.CancellationException("The transfer broke off.", exception))
                    }
                }

                try {
                    Outcome(
                        reply.await(),
                        if (upload.delivered.isCompleted) runCatching { upload.delivered.await() }.getOrNull() else null
                    )
                } finally {
                    watcher.cancel()
                }
            }
        } catch (caught: Throwable) {
            // When Pano itself stopped the bytes (a jar that is no zip), that reason is the one to give,
            // not the node's later "the upload broke off".
            val exception = deliveryError(upload) ?: caught

            if (!upload.delivered.isCompleted) {
                upload.abandon(exception)
            }

            transferTicketStore.consume(prepared.ticket.id)

            record(uploadId, userId, exception)

            BrowserUpload.closeAfterResponse(request)

            if (exception is Error) {
                throw exception
            }

            logger.warn("Upload ${prepared.ticket.fileName} failed: ${exception.message}")

            throw TransferFailed()
        }
    }

    /** The API error the node side's copy failed with, if it failed with one. */
    private suspend fun deliveryError(upload: UploadRendezvous): Error? {
        if (!upload.delivered.isCompleted) {
            return null
        }

        return try {
            upload.delivered.await()

            null
        } catch (exception: Throwable) {
            exception as? Error
        }
    }

    private fun record(uploadId: String?, userId: Long, exception: Throwable) {
        if (exception is Error) {
            val details = runCatching {
                JsonObject(exception.encode()).getJsonObject("error")?.getJsonObject("details")?.map
            }.getOrNull()

            transferTicketStore.recordFailure(uploadId, userId, exception.code, details.orEmpty())

            return
        }

        transferTicketStore.recordFailure(uploadId, userId, ERROR_TRANSFER_FAILED)
    }

    companion object {
        /** The browser stopped sending. */
        const val ERROR_ABORTED = "UPLOAD_ABORTED"

        const val ERROR_TRANSFER_FAILED = "TRANSFER_FAILED"

        private const val REPLY_GRACE_MS = 30_000L
    }
}
