package com.panomc.platform.model


import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import io.vertx.ext.web.RoutingContext

abstract class PanelApi : LoggedInApi() {
    // Already demands login + pano.panel.access.panel, which is the default bypass permission, so
    // the panel API self-authorises and must never be gated.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    private val authProvider by lazy {
        applicationContext.getBean(AuthProvider::class.java)
    }

    private val databaseManager  by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    private suspend fun updateLastPanelActivityTime(context: RoutingContext) {
        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        databaseManager.userDao.updateLastPanelActivityTime(userId, sqlClient)
    }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        if (!authProvider.hasAccessPanel(context)) {
            throw NoPermission()
        }

        updateLastPanelActivityTime(context)
    }
}