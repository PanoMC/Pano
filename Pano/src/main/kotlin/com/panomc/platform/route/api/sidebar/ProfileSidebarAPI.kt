package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class ProfileSidebarAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager
) :
    LoggedInApi() {
    override val paths = listOf(Path("/api/sidebars/profile", RouteType.GET))

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