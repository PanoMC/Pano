package com.panomc.platform.model

import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InstallationRequired
import com.panomc.platform.error.NotLoggedIn
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext

abstract class LoggedInApi : Api() {
    private val databaseManager by lazy {
        applicationContext.getBean(DatabaseManager::class.java)
    }

    private val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    private val authProvider by lazy {
        applicationContext.getBean(AuthProvider::class.java)
    }

    private fun checkSetup() {
        if (!setupManager.isSetupDone()) {
            throw InstallationRequired()
        }
    }

    private suspend fun checkLoggedIn(context: RoutingContext) {
        val isLoggedIn = authProvider.isLoggedIn(context)

        if (!isLoggedIn) {
            throw NotLoggedIn()
        }
    }

    private suspend fun updateLastActivityTime(context: RoutingContext) {
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.userDao.updateLastActivityTime(userId, sqlClient)
    }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        checkSetup()

        checkLoggedIn(context)

        updateLastActivityTime(context)

        authProvider.applyPermissionsTo(context)
    }
}