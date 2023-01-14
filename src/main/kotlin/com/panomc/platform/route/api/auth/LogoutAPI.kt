package com.panomc.platform.route.api.auth

import com.panomc.platform.AppConstants
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

        val response = context.response()

        response.putHeader(
            "Set-Cookie",
            "${AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME}=deleted; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT"
        )
        response.putHeader(
            "Set-Cookie",
            "${AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME}=deleted; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT"
        )

        return Successful()
    }
}