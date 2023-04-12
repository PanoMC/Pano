package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import org.springframework.beans.factory.annotation.Autowired

abstract class LoggedInApi : Api() {
    @Autowired
    private lateinit var databaseManager: DatabaseManager

    @Autowired
    private lateinit var setupManager: SetupManager

    @Autowired
    private lateinit var authProvider: AuthProvider

    private fun checkSetup() {
        if (!setupManager.isSetupDone()) {
            throw Error(ErrorCode.INSTALLATION_REQUIRED)
        }
    }

    private suspend fun checkLoggedIn(context: RoutingContext, sqlClient: SqlClient) {
        val isLoggedIn = authProvider.isLoggedIn(context, sqlClient)

        if (!isLoggedIn) {
            throw Error(ErrorCode.NOT_LOGGED_IN)
        }
    }

    private suspend fun updateLastActivityTime(context: RoutingContext, sqlClient: SqlClient) {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        databaseManager.userDao.updateLastActivityTime(userId, sqlClient)
    }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        checkSetup()

        val sqlClient = getSqlClient()

        checkLoggedIn(context, sqlClient)

        updateLastActivityTime(context, sqlClient)
    }
}