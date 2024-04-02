package com.panomc.platform

import com.panomc.platform.SpringConfig.Companion.vertx
import com.panomc.platform.api.PanoPlugin
import kotlinx.coroutines.runBlocking
import org.pf4j.DefaultPluginFactory
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class PluginFactory(private val pluginEventManager: PluginEventManager, private val pluginUiManager: PluginUiManager) :
    DefaultPluginFactory() {
    companion object {
        private val logger = LoggerFactory.getLogger(PluginFactory::class.java)
    }

    override fun createInstance(pluginClass: Class<*>, pluginWrapper: PluginWrapper): Plugin? {
        try {
            val constructor = pluginClass.getConstructor()

            val plugin = constructor.newInstance() as PanoPlugin

            pluginUiManager.initializePlugin(plugin)

            plugin.pluginId = pluginWrapper.pluginId
            plugin.vertx = vertx
            plugin.pluginEventManager = pluginEventManager
            plugin.pluginUiManager = pluginUiManager
            plugin.environmentType = Main.ENVIRONMENT
            plugin.releaseStage = Main.STAGE
            plugin.pluginGlobalBeanContext = PluginManager.pluginGlobalBeanContext
            plugin.applicationContext = Main.applicationContext

            val pluginBeanContext by lazy {
                val pluginBeanContext = AnnotationConfigApplicationContext()

                pluginBeanContext.setAllowBeanDefinitionOverriding(true)

                pluginBeanContext.parent = PluginManager.pluginGlobalBeanContext
                pluginBeanContext.classLoader = pluginClass.classLoader
                pluginBeanContext.scan(pluginClass.`package`.name)

                pluginBeanContext.beanFactory.registerSingleton(plugin.logger.javaClass.name, plugin.logger)
                pluginBeanContext.beanFactory.registerSingleton(pluginEventManager.javaClass.name, pluginEventManager)
                pluginBeanContext.beanFactory.registerSingleton(plugin.javaClass.name, plugin)

                pluginBeanContext.refresh()

                pluginBeanContext
            }

            plugin.pluginBeanContext = pluginBeanContext

            pluginEventManager.initializePlugin(plugin, pluginBeanContext)

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