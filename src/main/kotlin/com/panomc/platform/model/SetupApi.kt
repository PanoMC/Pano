package com.panomc.platform.model

import com.panomc.platform.ErrorCode
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext

abstract class SetupApi(private val setupManager: SetupManager) : Api() {
    override suspend fun onBeforeHandle(context: RoutingContext) {
        if (setupManager.isSetupDone()) {
            throw Error(ErrorCode.PLATFORM_ALREADY_INSTALLED)
        }
    }
}