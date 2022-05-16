package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.SetupApi
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext

@Endpoint
class FinishAPI(
    private val setupManager: SetupManager,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : SetupApi(setupManager) {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/setup/finish")

    override suspend fun handler(context: RoutingContext): Result {
        if (setupManager.getStep() != 3) {
            return Successful(setupManager.getCurrentStepData().map)
        }

        val data = context.bodyAsJson

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")

        val remoteIP = context.request().remoteAddress().host()

        RegisterUtil.validateForm(
            username,
            email,
            password,
            password,
            true,
            "",
            null
        )

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.initDatabase(sqlConnection)

        RegisterUtil.register(
            databaseManager,
            sqlConnection,
            username,
            email,
            password,
            remoteIP,
            isAdmin = true,
            isSetup = true
        )

        val token = authProvider.login(username, sqlConnection)

        setupManager.finishSetup()

        return Successful(
            mapOf(
                "jwt" to token
            )
        )
    }
}