package com.panomc.platform.route.api.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PanelNotification
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.notification.Notifications
import com.panomc.platform.server.PlatformCodeManager
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class ServerConnectNewAPI(
    private val platformCodeManager: PlatformCodeManager,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val setupManager: SetupManager,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/server/connect", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("platformCode", stringSchema())
                        .optionalProperty("favicon", stringSchema())
                        .property("serverName", stringSchema())
                        .optionalProperty("motd", stringSchema())
                        .property("host", stringSchema())
                        .property("port", intSchema())
                        .property("playerCount", numberSchema())
                        .property("maxPlayerCount", numberSchema())
                        .property("serverType", enumSchema(*ServerType.values().map { it.toString() }.toTypedArray()))
                        .property("serverVersion", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        if (!setupManager.isSetupDone()) {
            throw Error(ErrorCode.INSTALLATION_REQUIRED)
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        if (data.getString("platformCode", "") != platformCodeManager.getPlatformKey().toString()) {
            throw Error(ErrorCode.INVALID_PLATFORM_CODE)
        }

        val server = Server(
            name = data.getString("serverName"),
            motd = data.getString("motd") ?: "",
            host = data.getString("host"),
            port = data.getInteger("port"),
            playerCount = data.getLong("playerCount"),
            maxPlayerCount = data.getLong("maxPlayerCount"),
            type = ServerType.valueOf(data.getString("serverType")),
            version = data.getString("serverVersion"),
            favicon = data.getString("favicon") ?: "",
            status = ServerStatus.OFFLINE
        )

        val sqlConnection = createConnection(databaseManager, context)

        val serverId = databaseManager.serverDao.add(server, sqlConnection)

        val (token, expireDate) = tokenProvider.generateToken(serverId.toString(), TokenType.SERVER_AUTHENTICATION)

        tokenProvider.saveToken(token, serverId.toString(), TokenType.SERVER_AUTHENTICATION, expireDate, sqlConnection)

        val adminList = authProvider.getAdminList(sqlConnection)

        val notifications = adminList.map { admin ->
            val adminId = databaseManager.userDao.getUserIdFromUsername(admin, sqlConnection)!!

            PanelNotification(
                userId = adminId,
                typeId = Notifications.PanelNotification.SERVER_CONNECT_REQUEST.typeId,
                action = Notifications.PanelNotification.SERVER_CONNECT_REQUEST.action,
                properties = JsonObject().put("id", serverId)
            )
        }

        databaseManager.panelNotificationDao.addAll(notifications, sqlConnection)

        return Successful(
            mapOf(
                "token" to token
            )
        )
    }
}