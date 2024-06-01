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
        return CompoundPluginDescriptorFinder()
            .add(PanoManifestPluginDescriptorFinder())
    }

    override fun createPluginFactory(): PluginFactory {
        return PluginFactory(pluginEventManager, pluginUiManager)
    }

    override fun createPluginLoader(): PluginLoader {
        return CompoundPluginLoader()
            .add(PanoPluginLoader(this)) { this.isNotDevelopment }
    }

    fun getActivePanoPlugins(): List<PanoPlugin> = getPlugins(PluginState.STARTED).mapNotNull { plugin ->
        runCatching {
            val pluginWrapper = plugin as PanoPluginWrapper
            pluginWrapper.plugin as PanoPlugin
        }.getOrNull()
    }

    fun getPluginWrappers() = plugins.values.map { it as PanoPluginWrapper }

    override fun createPluginWrapper(
        pluginDescriptor: PluginDescriptor,
        pluginPath: Path,
        pluginClassLoader: ClassLoader
    ): PluginWrapper {
        val pluginWrapper = PanoPluginWrapper(this, pluginDescriptor, pluginPath, pluginClassLoader)

        pluginWrapper.setPluginFactory(getPluginFactory())

        return pluginWrapper
    }
}