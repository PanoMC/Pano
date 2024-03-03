package com.panomc.platform

import org.pf4j.JarPluginLoader
import org.pf4j.PluginClassLoader
import org.pf4j.PluginDescriptor
import org.pf4j.PluginManager
import java.nio.file.Path

class PanoPluginLoader(pluginManager: PluginManager) : JarPluginLoader(pluginManager) {
    override fun loadPlugin(pluginPath: Path, pluginDescriptor: PluginDescriptor): ClassLoader {
        val pluginClassLoader = PluginClassLoader(pluginManager, pluginDescriptor, javaClass.classLoader)

        pluginClassLoader.addFile(pluginPath.toFile())

        return pluginClassLoader
    }
}