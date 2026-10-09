package com.panomc.platform.util

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import io.vertx.core.json.JsonObject
import java.io.File

object PluginDevUtil {
    /**
     * The folder with a plugin's sources: `../plugins/<id>` or `plugins/<id>` while [devMode] is on
     * (dev environment or the panel's Development Mode), and the plugin roots of the running Pano
     * (`<pano>/plugins/<id>`) in every mode, which is where a developer works on a downloaded Pano.
     */
    fun getPluginSourceDir(
        pluginId: String,
        devMode: Boolean = DevMode.isActive(),
        pluginsRoots: () -> List<java.nio.file.Path>? = {
            try {
                Main.applicationContext.getBean(PluginManager::class.java).pluginsRoots
            } catch (e: Exception) {
                null
            }
        }
    ): File? {
        val potentialDirs = mutableListOf<File>()

        if (devMode) {
            potentialDirs.add(File("../plugins/$pluginId"))
            potentialDirs.add(File("plugins/$pluginId"))
        }

        pluginsRoots()?.forEach { potentialDirs.add(it.resolve(pluginId).toFile()) }

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
