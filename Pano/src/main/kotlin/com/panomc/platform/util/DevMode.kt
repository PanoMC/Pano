package com.panomc.platform.util

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig

/**
 * Whether Pano serves plugin sources the way a developer needs them: the dev environment of the
 * Pano checkout, or the Development Mode switch of the panel (`development-mode` in config.conf) on
 * a downloaded Pano. One test for plugin UI registration ([com.panomc.platform.PluginUiManager]),
 * the dev UI zip and package files, and the plugin source lookup ([PluginDevUtil]).
 */
object DevMode {
    fun isActive(
        config: PanoConfig?,
        environment: Main.Companion.EnvironmentType = Main.ENVIRONMENT
    ): Boolean = environment == Main.Companion.EnvironmentType.DEVELOPMENT || config?.developmentMode == true

    /** [isActive] for the running Pano (its config is read now, so the panel switch applies at once). */
    fun isActive(): Boolean = isActive(currentConfig())

    /** The running config, or null before Pano has one (early boot, unit tests). */
    fun currentConfig(): PanoConfig? = try {
        Main.applicationContext.getBean(ConfigManager::class.java).config
    } catch (e: Exception) {
        null
    }
}
