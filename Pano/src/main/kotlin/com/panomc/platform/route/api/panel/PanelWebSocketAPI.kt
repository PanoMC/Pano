package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.panel.PanelRealtimeHub
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelWebSocketAPI(
    private val authProvider: AuthProvider,
    private val panelRealtimeHub: PanelRealtimeHub
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/ws", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    /**
     * WebSocket upgrade requires the HTTP request to stay unread until [io.vertx.core.http.HttpServerRequest.toWebSocket].
     * [PanelApi.onBeforeHandle] does async SQL; we must [pause] before that work and [resume] only right before the upgrade
     * (see Vert.x docs; same pattern as [com.panomc.platform.route.api.server.ServerConnectAPI]).
     */
    override suspend fun onBeforeHandle(context: RoutingContext) {
        context.request().pause()
        try {
            super.onBeforeHandle(context)
        } catch (e: Throwable) {
            try {
                context.request().resume()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    override suspend fun handle(context: RoutingContext): Result? {
        val request = context.request()
        try {
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val canManageServers = authProvider.hasPermission(ManageServersPermission(), context)

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
                onWebSocketOpen(socket, userId, canManageServers)
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

    private fun onWebSocketOpen(socket: ServerWebSocket, userId: Long, canManageServers: Boolean) {
        panelRealtimeHub.register(socket, userId, canManageServers)
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
