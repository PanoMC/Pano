package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class CredentialsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager
) : LoggedInApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/auth/credentials")

    override suspend fun handler(context: RoutingContext): Result {
        val userID = authProvider.getUserIDFromRoutingContext(context)

        val sqlConnection = createConnection(databaseManager, context)

        val user = databaseManager.userDao.getByID(userID, sqlConnection) ?: throw Error(ErrorCode.UNKNOWN)

        return Successful(
            mapOf(
                "username" to user.username,
                "email" to user.email,
                "panelAccess" to (user.permissionGroupID != -1)
            )
        )
    }
}