package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired

abstract class PanelApi : LoggedInApi() {
    @Autowired
    private lateinit var authProvider: AuthProvider

    @Autowired
    private lateinit var databaseManager: DatabaseManager

    private suspend fun updateLastPanelActivityTime(context: RoutingContext) {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        databaseManager.userDao.updateLastPanelActivityTime(userId, sqlConnection)
    }

    override fun getHandler() = Handler<RoutingContext> { context ->
        checkSetup()

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            checkLoggedIn(context)

            if (!authProvider.hasAccessPanel(context)) {
                throw Error(ErrorCode.NO_PERMISSION)
            }

            updateLastPanelActivityTime(context)

            callHandler(context)
        }
    }
}