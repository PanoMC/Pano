package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import io.vertx.ext.web.RoutingContext

@Endpoint
class LoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/login")

    override suspend fun handler(context: RoutingContext): Result {
        val data = context.bodyAsJson

        val usernameOrEmail = data.getString("usernameOrEmail")
        val password = data.getString("password")
        val rememberMe = data.getBoolean("rememberMe")
        val recaptcha = data.getString("recaptcha")

        authProvider.inputValidator(usernameOrEmail, password, recaptcha)

        val sqlConnection = createConnection(databaseManager, context)

        authProvider.authenticate(usernameOrEmail, password, sqlConnection)

        return Successful()
    }
}