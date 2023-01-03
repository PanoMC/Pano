package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.auth.AuthProvider
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired

abstract class PanelApi : LoggedInApi() {

    @Autowired
    private lateinit var authProvider: AuthProvider

    override fun getHandler() = Handler<RoutingContext> { context ->
        checkSetup()

        CoroutineScope(context.vertx().dispatcher()).launch(getExceptionHandler(context)) {
            checkLoggedIn(context)

            if (!authProvider.hasAccessPanel(context)) {
                throw Error(ErrorCode.NO_PERMISSION)
            }

            updateLastActivityTime(context)

            callHandler(context)
        }
    }
}