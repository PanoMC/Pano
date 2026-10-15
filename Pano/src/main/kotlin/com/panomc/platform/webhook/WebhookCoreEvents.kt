package com.panomc.platform.webhook

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.model.Post
import com.panomc.platform.db.model.User
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

/**
 * The one helper every emit site of the core webhook events uses (doc 06 section 4.2). It builds the fixed payload of
 * each event (no e-mail, no IP, no message text) and hands it to `WebhookService.publishCore`.
 *
 * - A failed publish never fails the business call it follows: the error is logged and swallowed (cancellation is
 *   rethrown). The rows are idempotent (`eventId` is deterministic), so a retried call inserts nothing twice.
 * - [sqlClient] is the caller's client; `null` writes in a transaction of its own.
 * - After at least one row was written the delivery timer is armed ([arm]).
 *
 * The emit sites reach it through [current], which reads the bean lazily at call time: a constructor dependency on the
 * webhook beans would add the webhook wiring to every one of those classes.
 */
class WebhookCoreEvents(
    private val publishCore: suspend (event: String, subjectKey: String, data: JsonObject, sqlClient: SqlClient?, subjectRef: String?) -> Int,
    private val arm: () -> Unit = {},
    /** The public site URL (`website-url`), used for the `url` of `core.post.published`. */
    private val siteUrl: () -> String = { "" }
) {
    /** `core.user.registered`: `{ id, username, registeredAt }`, subject = the user id. */
    suspend fun userRegistered(user: User, sqlClient: SqlClient?) {
        emit(
            WebhookEvents.USER_REGISTERED, user.id.toString(), "user:${user.id}", sqlClient,
            JsonObject().put("id", user.id).put("username", user.username).put("registeredAt", user.registerDate)
        )
    }

    /** `core.user.deleted`: `{ id, username }`, subject = the user id. */
    suspend fun userDeleted(userId: Long, username: String, sqlClient: SqlClient?) {
        emit(
            WebhookEvents.USER_DELETED, userId.toString(), "user:$userId", sqlClient,
            JsonObject().put("id", userId).put("username", username)
        )
    }

    /** `core.ticket.created`: `{ id, title, categoryId, userId, username }`, subject = the ticket id. */
    suspend fun ticketCreated(
        ticketId: Long, title: String, categoryId: Long, userId: Long, username: String, sqlClient: SqlClient?
    ) {
        emit(
            WebhookEvents.TICKET_CREATED, ticketId.toString(), "ticket:$ticketId", sqlClient,
            JsonObject().put("id", ticketId).put("title", title).put("categoryId", categoryId).put("userId", userId)
                .put("username", username)
        )
    }

    /** `core.ticket.replied`: `{ ticketId, messageId, userId, staff }`, subject = the message id. */
    suspend fun ticketReplied(ticketId: Long, messageId: Long, userId: Long, staff: Boolean, sqlClient: SqlClient?) {
        emit(
            WebhookEvents.TICKET_REPLIED, messageId.toString(), "ticket:$ticketId", sqlClient,
            JsonObject().put("ticketId", ticketId).put("messageId", messageId).put("userId", userId).put("staff", staff)
        )
    }

    /**
     * `core.post.published`: `{ id, title, url, categoryId, publishedAt }`, subject = `<id>:<publish time>`, so
     * publishing the same post again later is a new event and a replay of one transition is not.
     */
    suspend fun postPublished(post: Post, sqlClient: SqlClient?) {
        emit(
            WebhookEvents.POST_PUBLISHED, "${post.id}:${post.date}", "post:${post.id}", sqlClient,
            JsonObject().put("id", post.id).put("title", post.title).put("url", postUrl(post.url))
                .put("categoryId", post.categoryId).put("publishedAt", post.date)
        )
    }

    /** The public page of a post: `<website-url>/post/<slug>`. */
    private fun postUrl(slug: String): String = siteUrl().trimEnd('/') + "/post/" + slug

    private suspend fun emit(event: String, subjectKey: String, subjectRef: String, sqlClient: SqlClient?, data: JsonObject) {
        try {
            val inserted = publishCore(event, subjectKey, data, sqlClient, subjectRef)

            if (inserted > 0) arm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not queue the webhook event {}: {}", event, e.toString())
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WebhookCoreEvents::class.java)

        /** The bean of the running application. */
        fun current(): WebhookCoreEvents = Main.applicationContext.getBean(WebhookCoreEvents::class.java)

        /**
         * What every emit site calls: `WebhookCoreEvents.fire { userDeleted(id, name, sqlClient) }`. A missing bean or a
         * failing publish is logged and never reaches the business code that follows.
         */
        suspend fun fire(block: suspend WebhookCoreEvents.() -> Unit) {
            val events = try {
                current()
            } catch (e: Exception) {
                logger.warn("Core webhook events are not available: {}", e.toString())

                return
            }

            events.block()
        }
    }
}

/** Wires [WebhookCoreEvents]; lazy like the rest of the webhook beans. */
@Configuration
open class WebhookCoreEventsBeans {
    @Bean
    @Lazy
    open fun webhookCoreEvents(
        service: WebhookService,
        dispatcher: WebhookDispatcher,
        configManager: ConfigManager
    ): WebhookCoreEvents = WebhookCoreEvents(
        publishCore = { event, subjectKey, data, sqlClient, subjectRef ->
            service.publishCore(event, subjectKey, data, sqlClient, subjectRef)
        },
        arm = { dispatcher.start() },
        siteUrl = { configManager.config.websiteUrl }
    )
}
