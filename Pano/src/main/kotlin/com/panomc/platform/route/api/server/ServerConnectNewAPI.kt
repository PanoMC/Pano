package com.panomc.platform.route.api.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.InvalidPlatformCode
import com.panomc.platform.model.*
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.Notifications
import com.panomc.platform.server.PlatformCodeManager
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
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
    private val notificationManager: NotificationManager
) : Api() {
    override val paths = listOf(Path("/api/server/connect", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("platformCode", stringSchema())
                        .optionalProperty("favicon", stringSchema())
                        .requiredProperty("serverName", stringSchema())
                        .optionalProperty("motd", stringSchema())
                        .requiredProperty("host", stringSchema())
                        .requiredProperty("port", intSchema())
                        .requiredProperty("playerCount", numberSchema())
                        .requiredProperty("maxPlayerCount", numberSchema())
                        .requiredProperty(
                            "serverType",
                            enumSchema(*ServerType.values().map { it.toString() }.toTypedArray())
                        )
                        .requiredProperty("serverVersion", stringSchema())
                        .requiredProperty("startTime", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!setupManager.isSetupDone()) {
            throw InstallationRequired()
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        if (data.getString("platformCode", "") != platformCodeManager.getPlatformKey().toString()) {
            throw InvalidPlatformCode()
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
            status = ServerStatus.OFFLINE,
            startTime = data.getLong("startTime")
        )

        val sqlClient = getSqlClient()

        val serverId = databaseManager.serverDao.add(server, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(serverId.toString(), TokenType.SERVER_AUTHENTICATION)

        tokenProvider.saveToken(token, serverId.toString(), TokenType.SERVER_AUTHENTICATION, expireDate, sqlClient)

        val notificationProperties = JsonObject().put("id", serverId)

        notificationManager.sendNotificationToAllWithPermission(
            Notifications.PanelNotificationType.SERVER_CONNECT_REQUEST,
            notificationProperties,
            ManageServersPermission(),
            sqlClient
        )

        return Successful(
            mapOf(
                "token" to token
            )
        )
    }
}