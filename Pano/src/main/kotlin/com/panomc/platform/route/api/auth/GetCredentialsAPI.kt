package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.arraySchema

@Endpoint
class GetCredentialsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : LoggedInApi() {
    override val paths = listOf(Path("/auth/credentials", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "Who the signed-in user is: name, e-mail, panel access and permissions.",
        tag = "auth",
        response = objectSchema()
            .requiredProperty("username", stringSchema())
            .requiredProperty("email", stringSchema().nullable())
            .requiredProperty("panelAccess", booleanSchema())
            .requiredProperty("permissions", arraySchema().items(stringSchema()))
            .requiredProperty("admin", booleanSchema())
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        val isAdmin = context.get<Boolean>("isAdmin") ?: false
        val permissions = context.get<List<String>>("permissions") ?: listOf()

        return Successful(
            mapOf(
                "username" to user.username,
                "email" to user.email,
                "panelAccess" to authProvider.hasPermission(AccessPanelPermission(), context),
                "permissions" to permissions,
                "admin" to isAdmin
            )
        )
    }
}