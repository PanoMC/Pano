package com.panomc.platform.route.api.auth

import com.panomc.platform.access.WsTicketStore
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema

/**
 * `POST /auth/ws-ticket` (doc 05 §6): a one-time ticket for the website WebSocket, `{"ticket", "expiresIn": 30}`.
 * A browser cannot send a Bearer header on a WebSocket, and a front-end on another origin must not lean on the
 * ambient cookie; a server-side front-end fetches the ticket for its visitor and hands it to the page, which opens
 * `/ws?ticket=...` once. A mutation like any other: a cookie session repeats `X-CSRF-Token`, a Bearer needs nothing.
 */
@Endpoint
class CreateWsTicketAPI(
    private val authProvider: AuthProvider,
    private val wsTicketStore: WsTicketStore
) : LoggedInApi() {
    override val paths = listOf(Path("/auth/ws-ticket", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "A one-time ticket for opening the website WebSocket.",
        tag = "auth",
        response = objectSchema()
            .requiredProperty("ticket", stringSchema())
            .requiredProperty("expiresIn", intSchema())
    )

    // A ticket opens nothing a session could not already open; the demo site's visitors need realtime too.
    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        val userId = authProvider.getUserIdFromRoutingContext(context)

        return Successful(
            mapOf(
                "ticket" to wsTicketStore.issue(userId),
                "expiresIn" to WsTicketStore.TTL_SECONDS
            )
        )
    }
}
