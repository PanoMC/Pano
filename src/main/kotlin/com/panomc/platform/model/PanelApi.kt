package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.annotation.Autowired

abstract class PanelApi : LoggedInApi() {
    @Autowired
    private lateinit var authProvider: AuthProvider

    @Autowired
    private lateinit var databaseManager: DatabaseManager

    private suspend fun updateLastPanelActivityTime(context: RoutingContext) {
        val sqlConnection = createConnection(context)
        val userId = authProvider.getUserIdFromRoutingContext(context)

        databaseManager.userDao.updateLastPanelActivityTime(userId, sqlConnection)
    }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        super.onBeforeHandle(context)

        if (!authProvider.hasAccessPanel(context)) {
            throw Error(ErrorCode.NO_PERMISSION)
        }

        updateLastPanelActivityTime(context)
    }
}