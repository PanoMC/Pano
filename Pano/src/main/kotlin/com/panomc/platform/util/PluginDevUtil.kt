package com.panomc.platform.util

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import io.vertx.core.json.JsonObject
import java.io.File

object PluginDevUtil {
    fun getPluginSourceDir(pluginId: String): File? {
        val potentialDirs = mutableListOf<File>()
        if (Main.ENVIRONMENT == Main.Companion.EnvironmentType.DEVELOPMENT) {
            potentialDirs.add(File("../plugins/$pluginId"))
            potentialDirs.add(File("plugins/$pluginId"))
        } else {
            val pluginManager = try {
                Main.applicationContext.getBean(PluginManager::class.java)
            } catch (e: Exception) {
                null
            } ?: return null
            pluginManager.pluginsRoots.forEach { potentialDirs.add(it.resolve(pluginId).toFile()) }
        }

        for (potentialPluginDir in potentialDirs) {
            if (potentialPluginDir.exists() && potentialPluginDir.isDirectory) {
                return potentialPluginDir
            }
        }
        return null
    }

    fun getPluginResourceDir(pluginId: String, resourcePath: String): File? {
        val sourceDir = getPluginSourceDir(pluginId) ?: return null
        val resourceDir = File(sourceDir, "src/main/resources/$resourcePath")
        return if (resourceDir.exists() && resourceDir.isDirectory) resourceDir else null
    }

    fun getPluginLocalesFromDir(localesDir: File): Map<String, JsonObject> {
        val locales = mutableMapOf<String, JsonObject>()
        if (localesDir.exists() && localesDir.isDirectory) {
            localesDir.listFiles { _, name -> name.endsWith(".json") }?.forEach {
                try {
                    locales[it.name.split(".json")[0]] = JsonObject(it.readText())
                } catch (e: Exception) {
                    // Ignore invalid JSON
                }
            }
        }
        return locales
    }
}
