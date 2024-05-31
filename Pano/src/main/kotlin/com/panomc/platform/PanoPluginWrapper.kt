package com.panomc.platform

import com.panomc.platform.util.HashUtil.hash
import com.typesafe.config.ConfigFactory
import org.pf4j.PluginDescriptor
import org.pf4j.PluginWrapper
import java.nio.file.Path

class PanoPluginWrapper(
    pluginManager: PluginManager,
    descriptor: PluginDescriptor,
    pluginPath: Path,
    internal val pluginClassLoader: ClassLoader
) : PluginWrapper(pluginManager, descriptor, pluginPath, pluginClassLoader) {
    internal val config by lazy {
        val configResource = pluginClassLoader.getResourceAsStream("config.conf") ?: return@lazy null

        val rawConfig = configResource.bufferedReader().readText()

        return@lazy ConfigFactory.parseString(rawConfig)
    }

    internal val hash = try {
        pluginPath.toFile().inputStream().hash()
    } catch (_: Exception) {
        ""
    }
}