package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.FileResourceUtil.getResource
import com.panomc.platform.util.HashUtil.hash

class PluginUiManager {
    private val pluginUiRegisterList = mutableMapOf<PanoPlugin, String>()

    internal fun getRegisteredPlugins() = pluginUiRegisterList.toList()

    internal fun initializePlugin(plugin: PanoPlugin) {
        val pluginUiZipFile = plugin.getResource("plugin-ui.zip") ?: return

        pluginUiRegisterList[plugin] = pluginUiZipFile.hash()

        pluginUiZipFile.close()
    }

    internal fun unRegisterPlugin(plugin: PanoPlugin) {
        pluginUiRegisterList.remove(plugin)
    }
}