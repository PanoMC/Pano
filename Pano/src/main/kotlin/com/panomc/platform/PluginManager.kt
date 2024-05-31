package com.panomc.platform

import com.panomc.platform.SpringConfig.Companion.pluginEventManager
import com.panomc.platform.SpringConfig.Companion.pluginUiManager
import com.panomc.platform.api.PanoPlugin
import org.pf4j.*
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Paths

@Component
class PluginManager(importPaths: List<Path> = listOf(Paths.get(System.getProperty("pf4j.pluginsDir", "./plugins")))) :
    DefaultPluginManager(importPaths) {
    companion object {
        internal val pluginGlobalBeanContext by lazy {
            val pluginGlobalBeanContext = AnnotationConfigApplicationContext()

            pluginGlobalBeanContext.setAllowBeanDefinitionOverriding(true)

            pluginGlobalBeanContext.beanFactory.registerSingleton(SpringConfig.vertx.javaClass.name, SpringConfig.vertx)

            pluginGlobalBeanContext.refresh()

            pluginGlobalBeanContext
        }
    }

    override fun createPluginDescriptorFinder(): CompoundPluginDescriptorFinder {
        return CompoundPluginDescriptorFinder() // Demo is using the Manifest file
            // PropertiesPluginDescriptorFinder is commented out just to avoid error log
            //.add(PropertiesPluginDescriptorFinder())
            .add(ManifestPluginDescriptorFinder())
    }

    override fun createPluginFactory(): PluginFactory {
        return PluginFactory(pluginEventManager, pluginUiManager)
    }

    override fun createPluginLoader(): PluginLoader {
        return CompoundPluginLoader()
            .add(PanoPluginLoader(this)) { this.isNotDevelopment }
    }

    fun getPanoPlugins(): List<PanoPlugin> = plugins.mapNotNull { plugin ->
        runCatching {
            val pluginWrapper = plugin.value as PluginWrapper
            pluginWrapper.plugin as PanoPlugin
        }.getOrNull()
    }
}