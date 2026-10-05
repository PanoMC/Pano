package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import com.panomc.platform.util.PluginDevUtil
import com.panomc.platform.util.HashUtil.hash
import org.pf4j.PluginState
import java.util.concurrent.ConcurrentHashMap

class PluginUiManager {
    // ConcurrentHashMap: mutated on Vert.x worker threads (plugin load/unload) while HTTP handlers
    // iterate it on the event loop. Iteration is weakly-consistent and won't throw a CME.
    private val pluginUiRegisterList = ConcurrentHashMap<PanoPlugin, String>()

    internal fun getRegisteredPlugins() = pluginUiRegisterList.toList()

    /**
     * UI hashes advertised to themes/panel ([GetSiteInfoAPI]) and authorized for zip download.
     *
     * [PanoPlugin.load] registers UI before start succeeds; failed/disabled plugins must not be
     * exposed or clients will try to fetch `/api/plugins/:id/resources/plugin-ui.zip` and error.
     */
    internal fun getActiveRegisteredPlugins(pluginManager: PluginManager): List<Pair<PanoPlugin, String>> =
        pluginUiRegisterList.toList().filter { (plugin, _) ->
            pluginManager.getPlugin(plugin.pluginId)?.pluginState == PluginState.STARTED
        }

    internal fun getRegisteredPlugin(plugin: PanoPlugin) = pluginUiRegisterList[plugin]

    internal fun initializePlugin(plugin: PanoPlugin) {
        calculatePluginUiHash(plugin, plugin.javaClass.classLoader)
    }

    internal fun unRegisterPlugin(plugin: PanoPlugin) {
        pluginUiRegisterList.remove(plugin)
    }

    internal fun calculatePluginUiHash(
        plugin: PanoPlugin,
        classLoader: ClassLoader,
        isDevelopment: Boolean = Main.ENVIRONMENT == Main.Companion.EnvironmentType.DEVELOPMENT,
        hasUiSourceDir: (String) -> Boolean = { PluginDevUtil.getPluginResourceDir(it, "plugin-ui") != null }
    ) {
        val pluginUiZipFile = classLoader.getOwnResourceStream("plugin-ui.zip")

        if (pluginUiZipFile == null) {
            // The dev hash is only for plugins that really have a UI source dir (a Kotlin-only plugin
            // must not advertise a UI it does not have).
            if (isDevelopment && hasUiSourceDir(plugin.pluginId)) {
                pluginUiRegisterList[plugin] = "dev-build"
            }
            return
        }

        pluginUiRegisterList[plugin] = pluginUiZipFile.hash()

        pluginUiZipFile.close()
    }
}