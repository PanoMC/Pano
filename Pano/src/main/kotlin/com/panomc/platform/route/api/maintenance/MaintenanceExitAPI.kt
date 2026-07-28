package com.panomc.platform.route.api.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.EXIT_PATH
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * Drops the caller's own skip cookie and sends them back to the maintenance page. Idempotent and
 * available over GET as well as POST, because the panel links to it as "preview the maintenance
 * page"; the worst a forged call can do is show someone that page.
 */
@Endpoint
class MaintenanceExitAPI(
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(
        Path(EXIT_PATH, RouteType.GET),
        Path(EXIT_PATH, RouteType.POST)
    )

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.clearMaintenanceSkipCookie(context)

        redirect(context, "/")

        return null
    }

    private fun redirect(context: RoutingContext, location: String) {
        val response = context.response()

        if (response.ended() || response.headWritten()) {
            return
        }

        response
            .setStatusCode(302)
            .setStatusMessage("Found")
            .putHeader("Location", location)
            .putHeader("Cache-Control", "no-store")
            .end()
    }
}
