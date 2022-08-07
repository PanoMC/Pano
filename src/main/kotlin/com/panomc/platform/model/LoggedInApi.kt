package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class LoggedInApi(private val setupManager: SetupManager, private val authProvider: AuthProvider) : Api() {
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

    override fun getHandler() = Handler<RoutingContext> { context ->
        checkSetup()

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            checkLoggedIn(context)

            callHandler(context)
        }
    }
}