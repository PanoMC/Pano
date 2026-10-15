package com.panomc.platform.route.api.profile


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetPlayerProfileAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/profiles/:username", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The public profile of a user.",
        tag = "profile",
        response = CoreSchemas.user,
        errors = listOf(NotExists::class)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getByUsername(username, sqlClient) ?: throw NotExists()

        val response = mutableMapOf<String, Any?>()

        response["registerDate"] = user.registerDate

        return Successful(response)
    }
}