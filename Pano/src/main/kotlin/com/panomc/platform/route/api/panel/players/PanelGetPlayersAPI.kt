package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.service.PlayerService
import com.panomc.platform.util.PlayerStatus
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelGetPlayersAPI(
    private val authProvider: AuthProvider,
    private val playerService: PlayerService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema()
                        .items(enumSchema(*PlayerStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("permissionGroup", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val playerStatus = PlayerStatus.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: PlayerStatus.ALL.name
        )

        val page = parameters.queryParameter("page")?.long ?: 1L
        val permissionGroupName = parameters.queryParameter("permissionGroup")?.string

        return playerService.getPlayers(playerStatus, page, permissionGroupName)
    }
}