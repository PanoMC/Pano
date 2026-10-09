package com.panomc.platform.route.api

import com.panomc.platform.access.AccessContext
import com.panomc.platform.access.OriginClass
import com.panomc.platform.access.WsAuth
import com.panomc.platform.access.WsTicketStore
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.LoggedInApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.access.InvalidWsTicket
import com.panomc.platform.access.OriginNotAllowed

/**
 * Logged-in website users (no panel access required). Same realtime hub as the panel WebSocket
 * for [PanelRealtimeHub.notifyPanelNotificationRefresh] nudges (message type [notificationRefresh]).
 */
@Endpoint
class WebsiteWebSocketAPI(
    private val authProvider: AuthProvider,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val wsTicketStore: WsTicketStore
) : LoggedInApi() {
    override val paths = listOf(Path("/ws", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Opens the realtime WebSocket of the signed-in user (notification refresh nudges). " +
                "Not a JSON answer: a successful call upgrades the connection (101) and then sends text frames, " +
                "the first being {\"type\":\"ready\"}. Sign in with the one-time `ticket` from `POST /auth/ws-ticket`, " +
                "an `Authorization: Bearer` header, or the session cookie (same or allowed origin only).",
        tag = "realtime",
        binary = true,
        errors = listOf(InvalidWsTicket::class, OriginNotAllowed::class)
    )

    /** `ticket` (optional): the one-time ticket of `POST /auth/ws-ticket`, for a client that cannot send headers. */
    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("ticket", stringSchema()))
            .build()

    /**
     * Who is on the other end is resolved by [WsAuth.resolveUser] in this order (doc 05 §6): a one-time `?ticket=`
     * (`401 INVALID_WS_TICKET` when it is not good), an `Authorization: Bearer`, the session cookie -- the last only
     * from the site's own origin or an allowed one (`403 ORIGIN_NOT_ALLOWED`). The user id is left in the context
     * under [WsTicketStore.USER_ID_KEY].
     */
    override suspend fun onBeforeHandle(context: RoutingContext) {
        context.request().pause()
        try {
            val userId = WsAuth.resolveUser(
                ticket = context.request().getParam("ticket"),
                source = authProvider.credentialSource(context),
                origin = AccessContext.of(context)?.origin ?: OriginClass.NONE,
                originHeader = context.request().getHeader("Origin"),
                store = wsTicketStore,
                beforeTicket = {
                    checkSetup()
                    checkDemoMode(context)
                    checkMaintenance(context)
                },
                session = { sessionUser(context) }
            )

            context.put(WsTicketStore.USER_ID_KEY, userId)
        } catch (e: Throwable) {
            try {
                context.request().resume()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private suspend fun sessionUser(context: RoutingContext): Long {
        super.onBeforeHandle(context)

        return authProvider.getUserIdFromRoutingContext(context)
    }

    override suspend fun handle(context: RoutingContext): Result? {
        val request = context.request()
        try {
            val userId = context.get<Long>(WsTicketStore.USER_ID_KEY)

            if (request.getHeader("Upgrade")?.equals("websocket", ignoreCase = true) != true) {
                try {
                    request.resume()
                } catch (_: Exception) {
                }
                return BadRequest()
            }

            try {
                request.resume()
            } catch (_: Exception) {
            }

            val future = request.toWebSocket()
            future.onSuccess { socket: ServerWebSocket ->
                onWebSocketOpen(socket, userId)
            }
            future.onFailure {
                if (!context.response().ended()) {
                    context.response().end()
                }
            }
        } catch (e: Throwable) {
            try {
                request.resume()
            } catch (_: Exception) {
            }
            throw e
        }

        return null
    }

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return method == HttpMethod.GET
    }

    private fun onWebSocketOpen(socket: ServerWebSocket, userId: Long) {
        panelRealtimeHub.register(socket, userId, canManageServers = false, canManageNodes = false)
        try {
            socket.writeTextMessage(
                JsonObject()
                    .put("type", "ready")
                    .encode()
            )
        } catch (_: Exception) {
            panelRealtimeHub.unregister(socket)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }

        socket.textMessageHandler { text ->
            try {
                panelRealtimeHub.applyClientConfig(socket, text)
            } catch (_: Exception) {
            }
        }

        socket.closeHandler {
            panelRealtimeHub.unregister(socket)
        }
    }
}
