package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema

@Endpoint
class ProfileSidebarAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager
) :
    LoggedInApi() {
    override val paths = listOf(Path("/sidebars/profile", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The signed-in user's own profile sidebar.",
        tag = "sidebars",
        response = objectSchema()
            .requiredProperty("lastActivityTime", intSchema())
            .requiredProperty("inGame", booleanSchema())
            .requiredProperty("permissionGroupName", stringSchema().nullable())
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        response["lastActivityTime"] = user.lastActivityTime

        response["inGame"] = databaseManager.serverPlayerDao.existsByUsername(user.username, sqlClient)

        response["permissionGroupName"] = permissionManager.getPermissionGroup(userId)?.displayName

        return Successful(response)
    }
}