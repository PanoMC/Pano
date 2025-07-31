package com.panomc.platform

import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.UIManager.Companion.InstalledBy
import com.panomc.platform.UIManager.Companion.InstalledTheme
import com.panomc.platform.UIManager.Companion.encode
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ResourceHash
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.error.FailedToInstallSystemResource
import com.panomc.platform.error.InvalidResourceFile
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Route
import com.panomc.platform.model.Successful
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.ResourceHashStatus
import com.panomc.platform.util.TimeUtil.getCurrentTimeStamp
import io.vertx.ext.web.Router
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class InstallManager(
    private val pluginManager: PluginManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    @param:Lazy private val router: Router,
    private val databaseManager: DatabaseManager
) {
    companion object {
        enum class ResourceType {
            PLUGIN, THEME
        }
    }

    private suspend fun addHash(hash: String, verified: Boolean) {
        val sqlClient = databaseManager.getSqlClient()
        val status = if (verified) ResourceHashStatus.VERIFIED else ResourceHashStatus.NOT_VERIFIED
        databaseManager.resourceHashDao.add(ResourceHash(hash = hash, status = status), sqlClient)
    }

    suspend fun installResource(
        hash: String?,
        verified: Boolean?,
        resourceFile: File,
        type: ResourceType,
        progressHandler: (result: Result) -> Unit
    ) {
        try {
            if (type == ResourceType.PLUGIN && !isValidJarFile(resourceFile)) {
                throw InvalidResourceFile()
            }

            if (type == ResourceType.THEME && !isValidZip(resourceFile)) {
                throw InvalidResourceFile()
            }

            if (hash != null && !HashUtil.verifyFileHash(resourceFile, hash)) {
                throw InvalidResourceFile()
            }

            progressHandler.invoke(Successful()) // Preparing success

            if (type == ResourceType.PLUGIN) {
                val pluginMetadata: PanoPluginDescriptor

                try {
                    pluginMetadata = readPluginMetadata(resourceFile.toPath())
                } catch (e: Exception) {
                    throw InvalidResourceFile(extras = mapOf("message" to e.message))
                }

                val pluginId = pluginMetadata.pluginId
                val version = pluginMetadata.version

                if (isInstalled(pluginId, version, type)) {
                    throw FailedToInstallResource(extras = mapOf("message" to "This version ($version) is already installed."))
                }

                if (isInstalled(pluginId, type)) {
                    val existingPlugin = pluginManager.getPlugin(pluginId)

                    pluginManager.stopPlugin(pluginId)
                    pluginManager.disablePlugin(pluginId)
                    pluginManager.unloadPlugin(pluginId)

                    existingPlugin.pluginPath.toFile().delete()
                }

                pluginManager.loadPlugin(resourceFile.toPath())
                pluginManager.enablePlugin(pluginId)
                pluginManager.startPlugin(pluginId)

                val plugin = pluginManager.getPlugin(pluginId) as PanoPluginWrapper

                if (verified != null) {
                    addHash(plugin.hash, verified)
                }

                progressHandler.invoke(Successful()) // Installing success

                return
            }

            if (type == ResourceType.THEME) {
                val tempThemeFolder = File(AppConstants.TEMP_FOLDER, "install-" + getCurrentTimeStamp())

                tempThemeFolder.mkdirs()

                extractZipStreamed(resourceFile, tempThemeFolder)

                val calculatedHash = resourceFile.inputStream().hash()
                resourceFile.delete()

                val manifestFile = File(tempThemeFolder, uiManager.manifestFileName)

                if (!manifestFile.exists()) {
                    tempThemeFolder.deleteRecursively()

                    throw InvalidResourceFile()
                }

                val parsedInstalledTheme: InstalledTheme

                try {
                    val manifest = uiManager.parseThemeManifest(manifestFile)

                    parsedInstalledTheme = InstalledTheme(
                        manifest.id,
                        manifest.version,
                        manifest.author,
                        manifest.license,
                        manifest.sourceUrl,
                        manifest.panoVersion,
                        manifest.screenshots,
                        calculatedHash,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        InstalledBy.USER
                    )

                    manifestFile.writeText(parsedInstalledTheme.encode())
                } catch (e: Exception) {
                    tempThemeFolder.deleteRecursively()

                    throw InvalidResourceFile(extras = mapOf("message" to e.message))
                }

                val id = parsedInstalledTheme.id.lowercase()
                val version = parsedInstalledTheme.version

                if (isInstalled(id, version, type)) {
                    tempThemeFolder.deleteRecursively()

                    throw FailedToInstallResource(extras = mapOf("message" to "This version ($version) is already installed."))
                }

                val config = configManager.config

                if (isInstalled(id, type)) {
                    val existingTheme = uiManager.installedThemeList.find { it.id == id }!!

                    if (existingTheme.installedBy == InstalledBy.SYSTEM) {
                        tempThemeFolder.deleteRecursively()

                        throw FailedToInstallSystemResource()
                    }

                    if (config.currentTheme == id) {
                        uiManager.stopUI(id)
                        uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)
                    }
                }

                val actualThemeFolder = File(THEMES_FOLDER_PATH, parsedInstalledTheme.id)

                tempThemeFolder.copyRecursively(actualThemeFolder, true)
                tempThemeFolder.deleteRecursively()

                uiManager.reloadInstalledThemes()

                if (config.currentTheme == id) {
                    uiManager.startUI(id)
                    uiManager.activateThemeUI(router)
                } else {
                    config.currentTheme = id
                }

                if (verified != null) {
                    addHash(calculatedHash, verified)
                }

                progressHandler.invoke(Successful()) // Installing success
            }
        } catch (e: Error) {
            progressHandler.invoke(e)
        } catch (e: Exception) {
            progressHandler.invoke(FailedToInstallResource(extras = mapOf("message" to e.message)))
        }
    }

    fun readPluginMetadata(pluginPath: Path): PanoPluginDescriptor {
        val pluginDescriptorFinder = PanoManifestPluginDescriptorFinder()

        return pluginDescriptorFinder.find(pluginPath) as PanoPluginDescriptor
    }

    fun fixVersion(version: String) = (if (!version.startsWith("v")) "v" else "") + version

    fun isInstalled(resourceId: String, type: ResourceType): Boolean {
        if (type == ResourceType.PLUGIN) {
            return pluginManager.resolvedPlugins.any { it.pluginId == resourceId }
        }

        return uiManager.installedThemeList.any { it.id == resourceId }
    }

    fun isInstalled(resourceId: String, version: String, type: ResourceType): Boolean {
        if (type == ResourceType.PLUGIN) {
            return pluginManager.resolvedPlugins.any {
                it.pluginId == resourceId && fixVersion(it.descriptor.version) == fixVersion(version)
            }
        }

        return uiManager.installedThemeList.any { it.id == resourceId && fixVersion(it.version) == fixVersion(version) }
    }

    fun getResourceInfo(resourceId: String, type: ResourceType): Map<String, Any> {
        if (type == ResourceType.PLUGIN) {
            val plugin = pluginManager.resolvedPlugins.find { it.pluginId == resourceId } as PanoPluginWrapper
            val descriptor = plugin.descriptor as PanoPluginDescriptor

            return mapOf(
                "id" to plugin.pluginId,
                "version" to fixVersion(descriptor.version),
                "hash" to plugin.hash,
                "license" to descriptor.license
            )
        }

        val theme = uiManager.installedThemeList.find { it.id == resourceId }!!

        return mapOf(
            "id" to theme.id,
            "version" to theme.version,
            "hash" to theme.hash,
            "createdAt" to theme.createdAt,
            "installedBy" to theme.installedBy,
            "type" to ResourceType.THEME
        )
    }

    private fun isValidZip(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (!file.name.lowercase().endsWith(".zip")) return false

        return try {
            FileInputStream(file).use { fis ->
                ZipInputStream(fis).use { zip ->
                    zip.nextEntry != null
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractZipStreamed(zipFile: File, outputDir: File): Boolean {
        if (!zipFile.exists() || !zipFile.isFile) return false

        return try {
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(BufferedInputStream(fis)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(outputDir, entry.name)

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            // Create sub directories
                            outFile.parentFile?.mkdirs()

                            // Write content
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos)
                            }
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isValidJarFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (!file.name.lowercase().endsWith(".jar")) return false

        return try {
            FileInputStream(file).use { fis ->
                ZipInputStream(fis).use { zipStream ->
                    var hasManifest = false
                    var hasClass = false

                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (entry.name == "META-INF/MANIFEST.MF") hasManifest = true
                        if (entry.name.endsWith(".class")) hasClass = true

                        if (hasManifest && hasClass) return true
                        entry = zipStream.nextEntry
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}