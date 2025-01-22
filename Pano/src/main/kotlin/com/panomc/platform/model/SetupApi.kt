package com.panomc.platform.model


import com.panomc.platform.error.PlatformAlreadyInstalled
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.annotation.Autowired

abstract class SetupApi : Api() {
    @Autowired
    lateinit var setupManager: SetupManager

    override suspend fun onBeforeHandle(context: RoutingContext) {
        if (setupManager.isSetupDone()) {
            throw PlatformAlreadyInstalled()
        }
    }
}