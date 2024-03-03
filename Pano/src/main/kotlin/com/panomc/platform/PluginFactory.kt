package com.panomc.platform

import com.panomc.platform.PluginManager.Companion.pluginEventManager
import com.panomc.platform.SpringConfig.Companion.vertx
import com.panomc.platform.api.PanoPlugin
import kotlinx.coroutines.runBlocking
import org.pf4j.DefaultPluginFactory
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class PluginFactory : DefaultPluginFactory() {
    companion object {
        private val logger = LoggerFactory.getLogger(PluginFactory::class.java)
    }

    override fun createInstance(pluginClass: Class<*>, pluginWrapper: PluginWrapper): Plugin? {
        val pluginBeanContext by lazy {
            val pluginBeanContext = AnnotationConfigApplicationContext()

            pluginBeanContext.parent = Main.applicationContext
            pluginBeanContext.classLoader = pluginClass.classLoader
            pluginBeanContext.scan(pluginClass.`package`.name)
            pluginBeanContext.refresh()

            pluginBeanContext
        }

        try {
            val constructor = pluginClass.getConstructor()

            val plugin = constructor.newInstance() as PanoPlugin

            pluginEventManager.initializePlugin(plugin, pluginBeanContext)

            plugin.pluginId = pluginWrapper.pluginId
            plugin.vertx = vertx
            plugin.pluginEventManager = pluginEventManager
            plugin.environmentType = Main.ENVIRONMENT
            plugin.releaseStage = Main.STAGE
            plugin.pluginBeanContext = pluginBeanContext
            plugin.applicationContext = Main.applicationContext

            runBlocking {
                plugin.onLoad()
            }

            return plugin
        } catch (e: Exception) {
            logger.error(e.message, e)
        }

        return null
    }
}