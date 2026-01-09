package com.panomc.platform.model


import com.panomc.platform.Main.Companion.applicationContext
import com.panomc.platform.error.PlatformAlreadyInstalled
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext

abstract class SetupApi : Api() {
    val setupManager by lazy {
        applicationContext.getBean(SetupManager::class.java)
    }

    override suspend fun onBeforeHandle(context: RoutingContext) {
        if (setupManager.isSetupDone()) {
            throw PlatformAlreadyInstalled()
        }
    }
}