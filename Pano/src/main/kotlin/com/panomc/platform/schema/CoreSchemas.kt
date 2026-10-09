package com.panomc.platform.schema

import com.panomc.platform.notification.NotificationStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.util.PostStatus
import com.panomc.platform.util.TicketStatus
import io.vertx.core.json.pointer.JsonPointer
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.GenericSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.anyOf
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.net.URI

/**
 * The shapes core endpoints share in their [EndpointDoc]s (doc 04 section 5). Each carries the `$id`
 * `pano:<Name>`; [OpenApiGenerator] moves every schema with such an id to `components.schemas.<Name>` and refers to it
 * with a `$ref`, so a client names the type once. A plugin keeps its own objects and may use these.
 *
 * The shapes describe what the endpoints answer today and stay open: no `additionalProperties: false`, because
 * adding a field to a response must never break a client (doc 04 section 6). A required property is one the
 * endpoint always writes.
 */
object CoreSchemas {
    /** The `$id` prefix that marks a schema as a shared component. */
    const val ID_PREFIX = "pano:"

    /** The component name of a schema `$id` (`pano:Post` or `pano:Post#` give `Post`), or `null` when it is not one. */
    fun componentName(id: String?): String? =
        id?.takeIf { it.startsWith(ID_PREFIX) }
            ?.removePrefix(ID_PREFIX)
            ?.removeSuffix("#")
            ?.takeIf { COMPONENT_NAME.matches(it) }

    private val COMPONENT_NAME = Regex("^[A-Za-z0-9._-]+$")

    /** An object schema that is emitted as the component [name]. */
    fun shape(name: String): ObjectSchemaBuilder =
        objectSchema().id(JsonPointer.fromURI(URI.create("$ID_PREFIX$name")))

    private fun names(values: Iterable<Enum<*>>): Array<Any> = values.map { it.name }.toTypedArray()

    /** A category as the lists and details write it: `{ id?, title, url? }`, `-` for none. */
    private fun category() = objectSchema()
        .optionalProperty("id", intSchema())
        .requiredProperty("title", stringSchema())
        .optionalProperty("url", stringSchema())
        .optionalProperty("description", stringSchema())
        .optionalProperty("color", stringSchema())

    private fun writer() = objectSchema().requiredProperty("username", stringSchema())

    /** One post in full, as `GET /posts/:url` answers under `post`. */
    val post: ObjectSchemaBuilder = shape("Post")
        .requiredProperty("id", intSchema())
        .requiredProperty("title", stringSchema())
        .requiredProperty("category", category())
        .requiredProperty("writer", writer())
        .requiredProperty("text", stringSchema())
        .requiredProperty("date", intSchema())
        .requiredProperty("status", enumSchema(*names(PostStatus.entries)))
        .requiredProperty("thumbnailUrl", stringSchema())
        .requiredProperty("views", intSchema())
        .requiredProperty("url", stringSchema())

    /** One post of a list, as `GET /posts` writes it in `items`: the text is a short HTML excerpt. */
    val postSummary: ObjectSchemaBuilder = shape("PostSummary")
        .requiredProperty("id", intSchema())
        .requiredProperty("title", stringSchema())
        .requiredProperty("category", category())
        .requiredProperty("writer", writer())
        .requiredProperty("text", stringSchema())
        .requiredProperty("date", intSchema())
        .requiredProperty("thumbnailUrl", stringSchema())
        .requiredProperty("views", intSchema())
        .requiredProperty("url", stringSchema())

    /** A user as the site shows one to others (`GET /profiles/:username`); `lastLoginDate` only on the own profile. */
    val user: ObjectSchemaBuilder = shape("User")
        .requiredProperty("registerDate", intSchema())
        .optionalProperty("lastLoginDate", intSchema())

    /** One ticket of the signed-in user, as `GET /tickets` writes it in `items`. */
    val ticket: ObjectSchemaBuilder = shape("Ticket")
        .requiredProperty("id", intSchema())
        .requiredProperty("title", stringSchema())
        .requiredProperty("category", category())
        .requiredProperty("writer", objectSchema().requiredProperty("username", stringSchema().nullable()))
        .requiredProperty("date", intSchema())
        .requiredProperty("lastUpdate", intSchema())
        .requiredProperty("status", enumSchema(*names(TicketStatus.entries)))

    /** One notification, as `GET /notifications` writes it in `items`. */
    val notification: ObjectSchemaBuilder = shape("Notification")
        .requiredProperty("id", intSchema())
        .requiredProperty("type", stringSchema())
        .requiredProperty("pluginId", stringSchema().nullable())
        .requiredProperty("details", objectSchema())
        .requiredProperty("status", enumSchema(*names(NotificationStatus.entries)))
        .requiredProperty("isPersonal", booleanSchema())
        .requiredProperty("createdAt", intSchema())
        .requiredProperty("updatedAt", intSchema())

    /** `page` of a cursor list: `{ size, nextCursor }`, the cursor being null on the last page. */
    val cursorPage: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("size", intSchema())
        .requiredProperty("nextCursor", stringSchema().nullable())

    /** One game server of the public list, as `GET /servers` writes it in `items`. Never a host, port or key. */
    val server: ObjectSchemaBuilder = shape("Server")
        .requiredProperty("id", intSchema())
        .requiredProperty("name", stringSchema())
        .requiredProperty("motd", stringSchema())
        .requiredProperty("online", booleanSchema())
        .requiredProperty("playerCount", intSchema())
        .requiredProperty("maxPlayerCount", intSchema())
        .requiredProperty("version", stringSchema())
        .requiredProperty("type", enumSchema(*names(ServerType.entries)))
        .requiredProperty("main", booleanSchema())
        .requiredProperty("iconUrl", stringSchema().nullable())

    /** The previous or next post of a post page: `{ id, title, url }`, or the text `-` when there is none. */
    private fun neighbour(): GenericSchemaBuilder = anyOf(
        stringSchema(),
        objectSchema()
            .requiredProperty("id", intSchema())
            .requiredProperty("title", stringSchema())
            .requiredProperty("url", stringSchema())
    )

    /** What `GET /posts/:url` and `GET /posts/previews/:id` answer: the post and its neighbours by date. */
    val postDetail: ObjectSchemaBuilder = shape("PostDetail")
        .requiredProperty("post", post)
        .requiredProperty("previousPost", neighbour())
        .requiredProperty("nextPost", neighbour())

    /** A language of the site, as `GET /locales` lists it and `GET /site-info` offers it. */
    val locale: ObjectSchemaBuilder = shape("Locale")
        .requiredProperty("id", intSchema())
        .requiredProperty("code", stringSchema())
        .requiredProperty("name", stringSchema())
        .requiredProperty("dateFnsCode", stringSchema())
        .requiredProperty("derivatives", arraySchema().items(stringSchema()))
        .optionalProperty("definedBy", enumSchema("SYSTEM", "USER"))
        .optionalProperty("createdAt", intSchema())
        .optionalProperty("updatedAt", intSchema())

    /**
     * A new session as the sign-in endpoints answer it. A cookie session answers `csrfToken` (the cookies are set);
     * a front-end with a stored key answers `sessionToken` and `expiresAt` and no cookies (doc 05 section 3.3).
     * Which of the two is present depends on the caller, so none is required.
     */
    val session: ObjectSchemaBuilder = sessionFields(shape("Session"))

    /** Adds the [session] properties to [builder], for an answer that carries more than the session. */
    fun sessionFields(builder: ObjectSchemaBuilder): ObjectSchemaBuilder = builder
        .optionalProperty("csrfToken", stringSchema())
        .optionalProperty("sessionToken", stringSchema())
        .optionalProperty("expiresAt", intSchema())

    /** A ticket category, as `GET /ticket-categories` lists it. */
    val ticketCategory: ObjectSchemaBuilder = shape("TicketCategory")
        .requiredProperty("id", intSchema())
        .requiredProperty("title", stringSchema())
        .requiredProperty("description", stringSchema())
        .requiredProperty("url", stringSchema())

    /** One message of a ticket; `panel` is 1 for a staff reply. */
    val ticketMessage: ObjectSchemaBuilder = shape("TicketMessage")
        .requiredProperty("id", intSchema())
        .requiredProperty("userId", intSchema())
        .requiredProperty("ticketId", intSchema())
        .requiredProperty("username", stringSchema())
        .requiredProperty("message", stringSchema())
        .requiredProperty("date", intSchema())
        .requiredProperty("panel", intSchema())

    /** One ticket with its messages, as `GET /tickets/:id` writes it under `ticket`. */
    val ticketDetail: ObjectSchemaBuilder = shape("TicketDetail")
        .requiredProperty("id", intSchema())
        .requiredProperty("username", stringSchema())
        .requiredProperty("title", stringSchema())
        .requiredProperty(
            "category",
            objectSchema().requiredProperty("title", stringSchema()).requiredProperty("url", stringSchema())
        )
        .requiredProperty("messages", arraySchema().items(ticketMessage))
        .requiredProperty("status", enumSchema(*names(TicketStatus.entries)))
        .requiredProperty("date", intSchema())
        .requiredProperty("messageCount", intSchema())

    /** `{ "items": [shape] }` for the lists that are not paged. */
    fun list(item: ObjectSchemaBuilder): ObjectSchemaBuilder = objectSchema().requiredProperty("items", arraySchema().items(item))
}
