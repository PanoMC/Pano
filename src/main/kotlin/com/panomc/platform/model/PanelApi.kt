package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class PanelApi(private val setupManager: SetupManager, private val authProvider: AuthProvider) :
    LoggedInApi(setupManager, authProvider) {

    override fun getHandler() = Handler<RoutingContext> { context ->
        checkSetup()

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            checkLoggedIn(context)

            if (!authProvider.hasAccessPanel(context)) {
                throw Error(ErrorCode.NO_PERMISSION)
            }

            callHandler(context)
        }
    }
}