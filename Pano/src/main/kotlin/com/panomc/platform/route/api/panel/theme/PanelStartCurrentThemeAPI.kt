package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.StartedCurrentThemeLog
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.ui.ThemeUiController
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Starts the configured theme and binds its proxy route.
 *
 * The work itself lives in [ThemeUiController], which the usage-mode switch uses as well: a
 * servers-only install stops the theme and an install that stops being servers-only starts it
 * again, and that must be the same start — licence fallback and all — as this button.
 */
@Endpoint
class PanelStartCurrentThemeAPI(
    private val uiManager: UIManager,
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val themeUiController: ThemeUiController
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val result = themeUiController.start()

        if (result.alreadyRunning) {
            return Successful()
        }

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            StartedCurrentThemeLog(userId, username, result.themeId), sqlClient
        )

        if (result.fellBackFrom == null) {
            return Successful()
        }

        // Surface the underlying licence reason in the success payload so the panel can toast the
        // operator ("falling back to vanilla — premium licence blocked").
        return Successful(
            mapOf(
                "fellBackTo" to result.themeId,
                "originalTheme" to result.fellBackFrom,
                "licenseDeniedReason" to result.licenseDeniedReason,
                "message" to result.message,
            )
        )
    }
}
