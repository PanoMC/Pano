package com.panomc.platform.route.api.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.maintenance.MaintenanceModeManager.Companion.SKIP_PATH
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * Target of the "skip maintenance mode" button. The cookie it sets is an opaque flag, never a
 * token: the bypass permission is re-derived here and on every later request, so a forged
 * cross-site POST (which carries no auth cookie under `SameSite=Lax`) resolves to anonymous and
 * sets nothing.
 */
@Endpoint
class MaintenanceSkipAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : Api() {
    override val paths = listOf(Path(SKIP_PATH, RouteType.POST))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result? {
        if (maintenanceModeManager.isEnabled() && maintenanceModeManager.resolveAccess(context).canBypass) {
            authProvider.setMaintenanceSkipCookie(context)
        }

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
