package com.panomc.platform

import com.panomc.platform.util.HashUtil.hash
import com.typesafe.config.ConfigFactory
import io.vertx.core.json.JsonObject
import org.pf4j.PluginDescriptor
import org.pf4j.PluginWrapper
import java.nio.file.*
import kotlin.io.path.name
import kotlin.io.path.readText

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

    internal val pluginLocales: Map<String, JsonObject> by lazy {
        val locales = mutableMapOf<String, JsonObject>()

        val resourceDirUri = pluginClassLoader.getResource("locales")?.toURI()

        if (resourceDirUri != null) {
            val dirPath = try {
                Paths.get(resourceDirUri)
            } catch (e: FileSystemNotFoundException) {
                // If this is thrown, then it means that we are running the JAR directly (example: not from an IDE)
                val env = mutableMapOf<String, String>()
                FileSystems.newFileSystem(resourceDirUri, env).getPath("locales")
            }

            Files
                .list(dirPath)
                .filter { it.name.endsWith(".json") }
                .forEach { locales[it.name.split(".json")[0]] = JsonObject(it.readText()) }
        }

        locales
    }
}