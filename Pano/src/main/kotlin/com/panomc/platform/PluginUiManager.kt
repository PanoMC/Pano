package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.FileResourceUtil.getResource
import com.panomc.platform.util.HashUtil.hash
import org.pf4j.PluginState

class PluginUiManager {
    private val pluginUiRegisterList = mutableMapOf<PanoPlugin, String>()

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
        calculatePluginUiHash(plugin)
    }

    internal fun unRegisterPlugin(plugin: PanoPlugin) {
        pluginUiRegisterList.remove(plugin)
    }

    private fun calculatePluginUiHash(plugin: PanoPlugin) {
        val pluginUiZipFile = plugin.getResource("plugin-ui.zip")

        if (pluginUiZipFile == null) {
            if (Main.ENVIRONMENT == Main.Companion.EnvironmentType.DEVELOPMENT) {
                pluginUiRegisterList[plugin] = "dev-build"
            }
            return
        }

        pluginUiRegisterList[plugin] = pluginUiZipFile.hash()

        pluginUiZipFile.close()
    }
}