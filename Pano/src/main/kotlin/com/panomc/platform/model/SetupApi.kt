package com.panomc.platform.model


import com.panomc.platform.error.PlatformAlreadyInstalled
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext

abstract class SetupApi(private val setupManager: SetupManager) : Api() {
    override suspend fun onBeforeHandle(context: RoutingContext) {
        if (setupManager.isSetupDone()) {
            throw PlatformAlreadyInstalled()
        }
    }
}