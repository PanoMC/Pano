package com.panomc.platform.model


import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NoPermission
import com.panomc.platform.route.Namespace
import io.vertx.ext.web.RoutingContext

abstract class PanelApi : LoggedInApi() {
    override val namespace = Namespace.PANEL

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
        // A front-end's site session token is never valid on the panel API (doc 05 §3.3); answered
        // first so it is 403 SITE_TOKEN_NOT_ALLOWED and not a login or permission error.
        authProvider.requireNoSiteToken(context)

        super.onBeforeHandle(context)

        // The CSRF check is no longer here: Api.getHandler / authorizedBodyHandler run it for every endpoint
        // right after this method returns (doc 05 §4).

        if (!authProvider.hasAccessPanel(context)) {
            throw NoPermission()
        }

        updateLastPanelActivityTime(context)
    }
}