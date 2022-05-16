package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.util.SetupManager
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext

abstract class SetupApi(private val setupManager: SetupManager) : Api() {
    override fun getHandler() = Handler<RoutingContext> { context ->
        if (setupManager.isSetupDone()) {
            sendResult(Error(ErrorCode.PLATFORM_ALREADY_INSTALLED), context)

            return@Handler
        }

        handler(context) {
            sendResult(it, context)
        }
    }
}