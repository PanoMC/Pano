package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.alert.AlertSettings
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * The alert switch grid, and whether the e-mail column means anything in this build.
 *
 * `emailAvailable` is false until a `serverAlert` mail template ships with the platform, and the
 * panel shows the e-mail switches disabled with a hint rather than offering a toggle that would
 * silently do nothing.
 */
@Endpoint
class PanelGetAlertSettingsAPI(
    private val authProvider: AuthProvider,
    private val alertManager: AlertManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/settings/alerts", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val settings = alertManager.settings(getSqlClient())

        return Successful(
            mapOf(
                "alerts" to AlertSettings.toJson(settings),
                "emailAvailable" to alertManager.isEmailAvailable
            )
        )
    }
}
