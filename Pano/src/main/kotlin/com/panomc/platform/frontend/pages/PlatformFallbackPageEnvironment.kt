package com.panomc.platform.frontend.pages

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.frontend.FallbackPageEnvironment
import com.panomc.platform.frontend.FrontendUrlMap
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.setup.SetupManager
import io.vertx.ext.web.RoutingContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/** The platform side of [FallbackPageEnvironment]: config, setup state and maintenance mode. */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PlatformFallbackPageEnvironment(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val maintenanceModeManager: MaintenanceModeManager,
    private val frontendUrlMap: FrontendUrlMap
) : FallbackPageEnvironment {
    override fun ready() = setupManager.isSetupDone()

    // The same decision as Api.checkMaintenance: the permission decides, never the skip cookie.
    override suspend fun blockedByMaintenance(context: RoutingContext): Boolean {
        if (!maintenanceModeManager.isEnabled()) {
            return false
        }

        return !maintenanceModeManager.resolveAccess(context).canBypass
    }

    override fun siteName(): String = configManager.config.websiteName

    override fun locale(): String = configManager.config.locale

    override fun logoUrl(): String {
        val logo = configManager.config.filePaths.websiteLogoFile
        val path = ApiPaths.core("/website-logo")

        return if (logo == null) path else "$path?hash=${logo.hash}"
    }

    override fun faviconUrl(): String = ApiPaths.core("/favicon")

    override fun homeUrl(): String = frontendUrlMap.siteUrl().ifEmpty { "/" }
}
