package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class LogoutAPI(
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/auth/logout", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlConnection = createConnection(context)

        authProvider.logout(context, sqlConnection)

        return Successful()
    }
}