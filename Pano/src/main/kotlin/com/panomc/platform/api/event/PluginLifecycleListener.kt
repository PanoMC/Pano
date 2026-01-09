package com.panomc.platform.api.event

import com.panomc.platform.api.PanoPlugin

interface PluginLifecycleListener {
    suspend fun onPluginLoad(plugin: PanoPlugin) {}
    suspend fun onPluginEnable(plugin: PanoPlugin) {}
    suspend fun onPluginDisable(plugin: PanoPlugin) {}
    suspend fun onPluginUnload(plugin: PanoPlugin) {}
    suspend fun onPluginUninstall(plugin: PanoPlugin) {}
}
