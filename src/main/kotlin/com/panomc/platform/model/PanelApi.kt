package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class PanelApi(private val setupManager: SetupManager, private val authProvider: AuthProvider) : Api() {

    override fun getHandler() = Handler<RoutingContext> { context ->
        if (!setupManager.isSetupDone()) {
            sendResult(Error(ErrorCode.INSTALLATION_REQUIRED), context)

            return@Handler
        }

        val isLoggedIn = authProvider.isLoggedIn(context)

        if (!isLoggedIn) {
            throw Error(ErrorCode.NOT_LOGGED_IN)
        }

        CoroutineScope(context.vertx().dispatcher()).launch {
            if (authProvider.hasAccessPanel(context))
                handler(context) {
                    sendResult(it, context)
                }
            else
                sendResult(Error(ErrorCode.NO_PERMISSION), context)
        }
    }
}