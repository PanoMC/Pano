package com.panomc.platform.route.api.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

@Endpoint
class LogoutAPI(
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/auth/logout", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Ends the current session and clears its cookies.",
        tag = "auth",
        response = objectSchema()
    )

    // Logging out must stay possible while the site is closed.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        authProvider.logout(context, sqlClient)

        val response = context.response()

        authProvider.clearCookies(context)

        return Successful()
    }
}