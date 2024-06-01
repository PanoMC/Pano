package com.panomc.platform

import com.panomc.platform.SpringConfig.Companion.vertx
import com.panomc.platform.api.PanoPlugin
import kotlinx.coroutines.runBlocking
import org.pf4j.DefaultPluginFactory
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory

class PluginFactory(private val pluginEventManager: PluginEventManager, private val pluginUiManager: PluginUiManager) :
    DefaultPluginFactory() {
    companion object {
        private val logger = LoggerFactory.getLogger(PluginFactory::class.java)
    }

    override fun createInstance(pluginClass: Class<*>, pluginWrapper: PluginWrapper): Plugin? {
        try {
            val constructor = pluginClass.getConstructor()

            val plugin = constructor.newInstance() as PanoPlugin

            plugin.pluginId = pluginWrapper.pluginId
            plugin.vertx = vertx
            plugin.pluginEventManager = pluginEventManager
            plugin.pluginUiManager = pluginUiManager
            plugin.environmentType = Main.ENVIRONMENT
            plugin.releaseStage = Main.STAGE
            plugin.pluginGlobalBeanContext = PluginManager.pluginGlobalBeanContext
            plugin.applicationContext = Main.applicationContext

            runBlocking {
                plugin.load()
                plugin.onCreate()
                plugin.onStart()
            }

            return plugin
        } catch (e: Exception) {
            logger.error(e.message, e)
        }

        return null
    }
}