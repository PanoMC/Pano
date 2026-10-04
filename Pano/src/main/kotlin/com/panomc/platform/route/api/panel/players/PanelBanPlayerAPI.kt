package com.panomc.platform.route.api.panel.players

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PlayerBanService
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelBanPlayerAPI(
    private val authProvider: AuthProvider,
    private val playerBanService: PlayerBanService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/ban", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("sendNotification", booleanSchema())
                        .optionalProperty("banMessage", stringSchema())
                        .optionalProperty("duration", numberSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        playerBanService.ban(
            username = parameters.pathParameter("username").string,
            issuerId = authProvider.getUserIdFromRoutingContext(context),
            issuerIsAdmin = context.get<Boolean>("isAdmin") ?: false,
            reason = data.getString("banMessage"),
            bannedUntil = data.getLong("duration"),
            sendNotification = data.getBoolean("sendNotification") ?: false,
            sqlClient = getSqlClient()
        )

        return Successful()
    }
}
