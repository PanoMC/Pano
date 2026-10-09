package com.panomc.platform.route.api.panel


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.route.Namespace
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelIsUserExistsAPI(
    private val databaseManager: DatabaseManager
) :
    PanelApi() {
    override val paths = listOf(Path("/players/:username/exists", RouteType.GET))

    override val namespace = Namespace.PANEL

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(username, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        return Successful()
    }
}