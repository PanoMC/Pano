package com.panomc.platform.route.api.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class LogoutAPI(
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/auth/logout", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        authProvider.logout(context, sqlClient)

        val response = context.response()

        authProvider.clearCookies(context)

        return Successful()
    }
}