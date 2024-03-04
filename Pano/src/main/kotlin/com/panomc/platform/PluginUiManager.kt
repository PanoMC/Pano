package com.panomc.platform

import com.panomc.platform.AppConstants.pluginUiFolder
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.FileResourceUtil.getResource
import com.panomc.platform.util.HashUtil.hash


class PluginUiManager {
    private val pluginUiList = mutableMapOf<PanoPlugin, MutableMap<String, String>>()

    internal fun getRegisteredPlugins() = pluginUiList

    internal fun initializePlugin(plugin: PanoPlugin) {
        val pluginUIFolder = plugin.getResource(pluginUiFolder)
        val clientJsFile = plugin.getResource(pluginUiFolder + "client.mjs")
        val serverJsFile = plugin.getResource(pluginUiFolder + "server.mjs")

        if (pluginUIFolder == null || clientJsFile == null || serverJsFile == null) {
            return
        }

        if (pluginUiList[plugin] == null) {
            pluginUiList[plugin] = mutableMapOf(
                pluginUiFolder + "client.mjs" to clientJsFile.hash(),
                pluginUiFolder + "server.mjs" to serverJsFile.hash()
            )
        }

        pluginUIFolder.close()
        clientJsFile.close()
        serverJsFile.close()
    }

    internal fun unRegisterPlugin(plugin: PanoPlugin) {
        pluginUiList.remove(plugin)
    }

    fun getRegistered(plugin: PanoPlugin) = pluginUiList[plugin]?.toMap()

    fun register(plugin: PanoPlugin, resourceName: String) {
        if (!resourceName.startsWith(pluginUiFolder)) {
            throw IllegalArgumentException("Can't use this resource as UI resource! Must start with: $pluginUiFolder")
        }

        val resource = plugin.getResource(resourceName) ?: throw IllegalArgumentException("Resource does not exists!")

        pluginUiList[plugin]!![resourceName] = resource.hash()

        resource.close()
    }

    fun unRegister(plugin: PanoPlugin, resource: String) {
        pluginUiList[plugin]?.remove(resource)
    }
}