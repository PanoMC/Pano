package com.panomc.platform.route.api.server

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Server
import com.panomc.platform.model.*
import com.panomc.platform.util.PlatformCodeManager
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class ConnectNewAPI(
    private val platformCodeManager: PlatformCodeManager,
    private val databaseManager: DatabaseManager,
    private val setupManager: SetupManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/server/connectNew")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("platformCode", Schemas.stringSchema())
                        .property("serverName", Schemas.stringSchema())
                        .property("playerCount", Schemas.numberSchema())
                        .property("maxPlayerCount", Schemas.numberSchema())
                        .property("serverType", Schemas.stringSchema())
                        .property("serverVersion", Schemas.stringSchema())
                        .property("favicon", Schemas.stringSchema())
                        .property("status", Schemas.stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result? {
        if (!setupManager.isSetupDone()) {
            context.reroute("/")

            return null
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val sqlConnection = createConnection(databaseManager, context)

        if (data.getString("platformCode", "") != platformCodeManager.getPlatformKey().toString()) {
            throw Error(ErrorCode.CONNECT_NEW_SERVER_API_PLATFORM_CODE_WRONG)
        }

        val token = databaseManager.serverDao.add(
            Server(
                name = data.getString("serverName"),
                playerCount = data.getLong("playerCount"),
                maxPlayerCount = data.getLong("maxPlayerCount"),
                type = data.getString("serverType"),
                version = data.getString("serverVersion"),
                favicon = data.getString("favicon"),
                status = data.getString("status")
            ),
            sqlConnection
        )

        return Successful(
            mapOf(
                "token" to token
            )
        )
    }
}