package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.StoppedCurrentThemeLog
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import org.springframework.context.annotation.Lazy

@Endpoint
class PanelStopCurrentThemeAPI(
    private val uiManager: UIManager,
    private val authProvider: AuthProvider,
    @param:Lazy private val router: Router,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/themes", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        if (!uiManager.activatedUIList.containsKey(Type.THEME_UI)) {
            return Successful()
        }

        uiManager.stopUI(uiManager.activeTheme)
        uiManager.disableUIOnRoute(router, Type.THEME_UI)

        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            StoppedCurrentThemeLog(
                userId,
                username,
                uiManager.activeTheme
            ), sqlClient
        )

        return Successful()
    }
}