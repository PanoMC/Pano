package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.setup.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired

abstract class LoggedInApi : Api() {
    @Autowired
    private lateinit var databaseManager: DatabaseManager

    @Autowired
    private lateinit var setupManager: SetupManager

    @Autowired
    private lateinit var authProvider: AuthProvider

    fun checkSetup() {
        if (!setupManager.isSetupDone()) {
            throw Error(ErrorCode.INSTALLATION_REQUIRED)
        }
    }

    suspend fun checkLoggedIn(context: RoutingContext) {
        val isLoggedIn = authProvider.isLoggedIn(context)

        if (!isLoggedIn) {
            throw Error(ErrorCode.NOT_LOGGED_IN)
        }
    }

    suspend fun updateLastActivityTime(context: RoutingContext) {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        databaseManager.userDao.updateLastActivityTime(userId, sqlConnection)
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        checkSetup()

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            checkLoggedIn(context)

            updateLastActivityTime(context)

            callHandler(context)
        }
    }
}