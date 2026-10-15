package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.alert.AlertSettings
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * The alert switch grid, and whether the e-mail column means anything in this build.
 *
 * `emailAvailable` is always true now that the `server-alert` mail template ships with the core
 * mail templates; it stays in the answer because the panel reads it.
 */
@Endpoint
class PanelGetAlertSettingsAPI(
    private val authProvider: AuthProvider,
    private val alertManager: AlertManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/settings/alerts", RouteType.GET))

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
