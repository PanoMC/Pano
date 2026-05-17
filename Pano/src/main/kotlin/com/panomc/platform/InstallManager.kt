package com.panomc.platform

import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.AppConstants.UPDATE_ICON_FOLDER
import com.panomc.platform.UIManager.Companion.InstalledBy
import com.panomc.platform.UIManager.Companion.InstalledTheme
import com.panomc.platform.UIManager.Companion.encode
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.InstalledResourceLog
import com.panomc.platform.auth.panel.log.UpdatedResourceLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.ResourceHash
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.error.FailedToInstallSystemResource
import com.panomc.platform.error.InvalidResourceFile
import com.panomc.platform.license.findLicenseRequiredInCauseChain
import com.panomc.platform.model.Error as DomainError
import com.panomc.platform.model.Result
import com.panomc.platform.model.Route
import com.panomc.platform.model.Successful
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.HashUtil.computeStableFileFingerprint
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.ResourceHashStatus
import com.panomc.platform.util.TimeUtil.getCurrentTimeStamp
import com.panomc.platform.util.VersionUtil
import io.vertx.core.Vertx
import org.pf4j.PluginState
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class InstallManager(
    private val vertx: Vertx,
    private val pluginManager: PluginManager,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    @param:Lazy private val router: Router,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val licenseManager: com.panomc.platform.license.LicenseManager
) {
    companion object {
        enum class ResourceType {
            PLUGIN, THEME
        }

        private const val ROLLBACK_SUFFIX = ".pano-backup"
    }

    private suspend fun addHash(hash: String, verified: Boolean) {
        val sqlClient = databaseManager.getSqlClient()
        val status = if (verified) ResourceHashStatus.VERIFIED else ResourceHashStatus.NOT_VERIFIED
        databaseManager.resourceHashDao.add(ResourceHash(hash = hash, status = status), sqlClient)
    }

    suspend fun installResource(
        userId: Long?,
        hash: String?,
        verified: Boolean?,
        resourceFile: File,
        type: ResourceType,
        progressHandler: (result: Result) -> Unit
    ) {
        try {
            // Validate files on a worker thread (blocking I/O: reading zip headers, computing hashes)
            vertx.executeBlocking<Unit> {
                if (type == ResourceType.PLUGIN && !isValidJarFile(resourceFile)) {
                    throw InvalidResourceFile()
                }

                if (type == ResourceType.THEME && !isValidZip(resourceFile)) {
                    throw InvalidResourceFile()
                }

                if (hash != null && !HashUtil.verifyFileHash(resourceFile, hash)) {
                    throw InvalidResourceFile()
                }
            }.coAwait()

            progressHandler.invoke(Successful()) // Preparing success

            if (type == ResourceType.PLUGIN) {
                val pluginMetadata: PanoPluginDescriptor

                try {
                    pluginMetadata = vertx.executeBlocking<PanoPluginDescriptor> { readPluginMetadata(resourceFile.toPath()) }.coAwait()
                } catch (e: Exception) {
                    throw InvalidResourceFile(extras = mapOf("message" to e.message))
                }

                val pluginId = pluginMetadata.pluginId
                val version = pluginMetadata.version

                var fromVersion: String? = null

                if (isInstalled(pluginId, type)) {
                    val existingPlugin = pluginManager.getPlugin(pluginId)
                    fromVersion = existingPlugin.descriptor.version
                    val originalPath = existingPlugin.pluginPath.toAbsolutePath().normalize()

                    unloadPluginForInstall(pluginId)

                    val backupPath = Paths.get(originalPath.toString() + ROLLBACK_SUFFIX)

                    vertx.executeBlocking<Unit> {
                        Files.deleteIfExists(backupPath)
                        if (!Files.exists(originalPath)) {
                            throw FailedToInstallResource(
                                extras = mapOf("message" to "Previous plugin artifact missing; cannot update safely.")
                            )
                        }
                        movePathReplacing(originalPath, backupPath)
                    }.coAwait()

                    val incoming = resourceFile.toPath().toAbsolutePath().normalize()
                    vertx.executeBlocking<Unit> {
                        when {
                            incoming == originalPath && Files.exists(originalPath) -> Unit
                            incoming != originalPath ->
                                Files.copy(incoming, originalPath, StandardCopyOption.REPLACE_EXISTING)
                            else -> throw FailedToInstallResource(
                                extras = mapOf("message" to "Plugin update artifact missing after backup.")
                            )
                        }
                    }.coAwait()

                    try {
                        val state = vertx.executeBlocking<PluginState?> {
                            pluginManager.loadPlugin(originalPath)
                            pluginManager.enablePlugin(pluginId)
                            pluginManager.startPlugin(pluginId)
                        }.coAwait()
                        if (state != PluginState.STARTED) {
                            throw pluginStartFailure(pluginId, "update")
                        }
                    } catch (e: Throwable) {
                        try {
                            rollbackPluginJar(pluginId, originalPath, backupPath)
                        } catch (rollbackEx: Throwable) {
                            throw FailedToInstallResource(
                                extras = mapOf(
                                    "message" to "Update failed and automatic rollback failed (${rollbackEx.message}). Manual restore may be needed from $backupPath"
                                )
                            )
                        }
                        if (e is DomainError) {
                            throw e
                        }
                        throw FailedToInstallResource(
                            extras = mapOf("message" to (e.message ?: e.javaClass.simpleName))
                        )
                    }

                    vertx.executeBlocking<Unit> {
                        Files.deleteIfExists(backupPath)
                        if (incoming != originalPath && Files.exists(incoming)) {
                            Files.delete(incoming)
                        }
                    }.coAwait()
                } else {
                    val state = vertx.executeBlocking<PluginState?> {
                        pluginManager.loadPlugin(resourceFile.toPath())
                        pluginManager.enablePlugin(pluginId)
                        pluginManager.startPlugin(pluginId)
                    }.coAwait()
                    if (state != PluginState.STARTED) {
                        val failure = pluginStartFailure(pluginId, "install")
                        try {
                            vertx.executeBlocking<Unit> {
                                pluginManager.unloadPlugin(pluginId)
                            }.coAwait()
                        } catch (_: Throwable) {
                        }
                        throw failure
                    }
                }

                val plugin = pluginManager.getPlugin(pluginId) as PanoPluginWrapper

                if (verified != null) {
                    addHash(plugin.hash, verified)
                }

                if (userId != null) {
                    if (fromVersion != null) {
                        val sqlClient = databaseManager.getSqlClient()
                        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                        databaseManager.panelActivityLogDao.add(
                            UpdatedResourceLog(
                                userId,
                                username,
                                pluginId,
                                fromVersion,
                                version,
                                type
                            ), sqlClient
                        )
                    } else {
                        val sqlClient = databaseManager.getSqlClient()
                        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                        databaseManager.panelActivityLogDao.add(
                            InstalledResourceLog(
                                userId,
                                username,
                                pluginId,
                                version,
                                type
                            ), sqlClient
                        )
                    }
                }

                removeUpdateInfoIfVersionMet(pluginId, version)

                progressHandler.invoke(Successful()) // Installing success

                return
            }

            if (type == ResourceType.THEME) {
                val tempThemeFolder = File(AppConstants.TEMP_FOLDER, "install-" + getCurrentTimeStamp())

                // Extract zip and compute hash on worker thread (heavy blocking I/O)
                val calculatedHash = vertx.executeBlocking<String> {
                    tempThemeFolder.mkdirs()
                    extractZipStreamed(resourceFile, tempThemeFolder)
                    val h = resourceFile.inputStream().hash()
                    resourceFile.delete()
                    h
                }.coAwait()

                val manifestFile = File(tempThemeFolder, uiManager.manifestFileName)

                if (!manifestFile.exists()) {
                    tempThemeFolder.deleteRecursively()

                    throw InvalidResourceFile()
                }

                // File-fingerprint cross-check: the theme's vite postbuild plugin stamps
                // the cumulative SHA-256 of every file (except manifest.json) into the
                // manifest. We re-compute it from the just-extracted folder and refuse to
                // install if it doesn't match — that either means the build pipeline was
                // skipped (no fingerprint), or someone repacked the zip after the
                // fingerprint was written. Free themes built without the postbuild plugin
                // are tolerated (fingerprint is optional).
                val claimedFingerprint = try {
                    val raw = uiManager.parseThemeManifest(manifestFile).fileFingerprint
                    raw?.trim().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (claimedFingerprint.isNotBlank()) {
                    val computed = vertx.executeBlocking<String> {
                        computeStableFileFingerprint(tempThemeFolder)
                    }.coAwait()
                    if (!computed.equals(claimedFingerprint, ignoreCase = true)) {
                        tempThemeFolder.deleteRecursively()
                        throw InvalidResourceFile(
                            extras = mapOf(
                                "message" to "Theme fileFingerprint mismatch (expected ${claimedFingerprint.take(12)}…, got ${computed.take(12)}…). The theme zip may be tampered or built with a stale toolchain.",
                                "expectedFingerprint" to claimedFingerprint,
                                "actualFingerprint" to computed
                            )
                        )
                    }
                }

                // Premium gate: if the manifest declares the theme premium, fetch a license
                // from panomc.com BEFORE writing anything to the themes/ folder. Installing
                // a theme the operator can't actually run wastes disk and confuses the panel
                // (theme card with permanent license-required badge). Refusing here surfaces
                // the failure to the install flow as a normal install error.
                run {
                    val previewManifest = try {
                        uiManager.parseThemeManifest(manifestFile)
                    } catch (_: Exception) {
                        null
                    }
                    if (previewManifest != null && previewManifest.premium) {
                        val normalizedVersion = previewManifest.version.removePrefix("v")
                        try {
                            licenseManager.requireThemeLicense(
                                previewManifest.id,
                                normalizedVersion,
                                calculatedHash.lowercase()
                            )
                        } catch (e: com.panomc.platform.license.LicenseRequiredException) {
                            tempThemeFolder.deleteRecursively()
                            // Re-throw verbatim; the outer catch-all on installResource
                            // converts LicenseRequiredException into FailedToInstallResource
                            // with a stable licenseDeniedReason via findLicenseRequiredInCauseChain.
                            throw e
                        }
                    }
                }

                val parsedInstalledTheme: InstalledTheme

                try {
                    val manifest = uiManager.parseThemeManifest(manifestFile)

                    val createdAt = if (isInstalled(manifest.id, type)) {
                        val existingTheme = uiManager.installedThemeList.find { it.id == manifest.id }!!

                        existingTheme.createdAt
                    } else {
                        System.currentTimeMillis()
                    }

                    val screenshots = vertx.executeBlocking<Map<String, String>> {
                        manifest.screenshots.map { it to File(tempThemeFolder, it) }.mapNotNull {
                            if (it.second.exists()) {
                                it.first to it.second.inputStream().hash()
                            } else {
                                null
                            }
                        }.toMap()
                    }.coAwait()

                    parsedInstalledTheme = InstalledTheme(
                        manifest.id,
                        manifest.title,
                        manifest.description,
                        manifest.version,
                        manifest.author,
                        manifest.license,
                        manifest.sourceUrl,
                        manifest.panoVersion,
                        screenshots,
                        calculatedHash,
                        createdAt,
                        System.currentTimeMillis(),
                        InstalledBy.USER,
                        manifest.premium,
                        manifest.fileFingerprint
                    )

                    manifestFile.writeText(parsedInstalledTheme.encode())
                } catch (e: Exception) {
                    tempThemeFolder.deleteRecursively()

                    throw InvalidResourceFile(extras = mapOf("message" to e.message))
                }

                val themeId = parsedInstalledTheme.id.lowercase()
                val version = parsedInstalledTheme.version

                if (isInstalled(themeId, version, type)) {
                    tempThemeFolder.deleteRecursively()

                    throw FailedToInstallResource(extras = mapOf("message" to "This version ($version) is already installed."))
                }

                val config = configManager.config

                var fromVersion: String? = null

                if (isInstalled(themeId, type)) {
                    val existingTheme = uiManager.installedThemeList.find { it.id == themeId }!!
                    fromVersion = existingTheme.version

                    if (existingTheme.installedBy == InstalledBy.SYSTEM) {
                        tempThemeFolder.deleteRecursively()

                        throw FailedToInstallSystemResource()
                    }

                    if (uiManager.activeTheme == themeId && config.initUi) {
                        uiManager.stopUI(themeId)
                        uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)
                    }
                }

                val actualThemeFolder = File(THEMES_FOLDER_PATH, parsedInstalledTheme.id)
                val themeBackupFolder =
                    File(actualThemeFolder.parentFile, actualThemeFolder.name + ROLLBACK_SUFFIX)

                if (fromVersion != null && actualThemeFolder.exists()) {
                    vertx.executeBlocking<Unit> {
                        if (themeBackupFolder.exists()) {
                            themeBackupFolder.deleteRecursively()
                        }
                        movePathReplacing(actualThemeFolder.toPath(), themeBackupFolder.toPath())
                    }.coAwait()
                }

                try {
                    vertx.executeBlocking<Unit> {
                        tempThemeFolder.copyRecursively(actualThemeFolder, true)
                        tempThemeFolder.deleteRecursively()
                    }.coAwait()

                    uiManager.reloadInstalledThemes()

                    if (uiManager.activeTheme == themeId && config.initUi) {
                        uiManager.startUI(themeId)
                        uiManager.activateThemeUI(router, uiManager.activeTheme)
                    }
                } catch (e: Throwable) {
                    if (fromVersion != null && themeBackupFolder.exists()) {
                        vertx.executeBlocking<Unit> {
                            if (actualThemeFolder.exists()) {
                                actualThemeFolder.deleteRecursively()
                            }
                            movePathReplacing(themeBackupFolder.toPath(), actualThemeFolder.toPath())
                        }.coAwait()
                        uiManager.reloadInstalledThemes()
                        if (uiManager.activeTheme == themeId && config.initUi) {
                            uiManager.startUI(themeId)
                            uiManager.activateThemeUI(router, uiManager.activeTheme)
                        }
                    }
                    throw e
                }

                if (themeBackupFolder.exists()) {
                    vertx.executeBlocking<Unit> {
                        themeBackupFolder.deleteRecursively()
                    }.coAwait()
                }

                if (verified != null) {
                    addHash(calculatedHash, verified)
                }

                if (userId != null) {
                    if (fromVersion != null) {
                        val sqlClient = databaseManager.getSqlClient()
                        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                        databaseManager.panelActivityLogDao.add(
                            UpdatedResourceLog(
                                userId,
                                username,
                                themeId,
                                fromVersion,
                                version,
                                type
                            ), sqlClient
                        )
                    } else {
                        val sqlClient = databaseManager.getSqlClient()
                        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                        databaseManager.panelActivityLogDao.add(
                            InstalledResourceLog(
                                userId,
                                username,
                                themeId,
                                version,
                                type
                            ), sqlClient
                        )
                    }
                }

                removeUpdateInfoIfVersionMet(themeId, version)

                progressHandler.invoke(Successful()) // Installing success
            }
        } catch (e: DomainError) {
            progressHandler.invoke(e)
        } catch (e: Exception) {
            val licenseException = e.findLicenseRequiredInCauseChain()
            val extras =
                if (licenseException != null) {
                    mapOf(
                        "message" to licenseException.message,
                        "licenseDeniedReason" to licenseException.reason.publicId,
                        "pluginId" to licenseException.pluginId
                    )
                } else {
                    mapOf("message" to e.message)
                }
            progressHandler.invoke(FailedToInstallResource(extras = extras))
        } catch (e: Throwable) {
            val licenseException = e.findLicenseRequiredInCauseChain()
            val extras =
                if (licenseException != null) {
                    mapOf(
                        "message" to licenseException.message,
                        "licenseDeniedReason" to licenseException.reason.publicId,
                        "pluginId" to licenseException.pluginId
                    )
                } else {
                    mapOf("message" to e.message)
                }
            progressHandler.invoke(FailedToInstallResource(extras = extras))
        }
    }

    suspend fun removeUpdateInfoIfVersionMet(resourceId: String, installedVersion: String) {
        val sqlClient = databaseManager.getSqlClient()
        val updateInfoOption =
            databaseManager.systemPropertyDao.getByOption(UpdateManager.RESOURCES_UPDATE_CHECK_INFO, sqlClient) ?: return
        val updateList = JsonArray(updateInfoOption.value)
        val updateArray = updateList.map { it as JsonObject }

        val filteredList = updateArray.filter { update ->
            if (update.getString("id") != resourceId) return@filter true

            val updateVersion = update.getString("version")
            // Keep if updateVersion is strictly higher than installedVersion
            VersionUtil.isVersionHigher(updateVersion, installedVersion)
        }

        if (filteredList.size != updateArray.size) {
            databaseManager.systemPropertyDao.update(
                UpdateManager.RESOURCES_UPDATE_CHECK_INFO,
                JsonArray(filteredList).encode(),
                sqlClient
            )

            // Also clean up icons if removed
            val removedItems = updateArray.filter { it !in filteredList }
            removedItems.forEach { removed ->
                val iconFileName = removed.getString("iconFileName")
                if (iconFileName != null) {
                    val updateIconFolder =
                        configManager.config.fileUploadsFolder + File.separator + UPDATE_ICON_FOLDER
                    val file = File(updateIconFolder + iconFileName)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun readPluginMetadata(pluginPath: Path): PanoPluginDescriptor {
        val pluginDescriptorFinder = PanoManifestPluginDescriptorFinder()

        return pluginDescriptorFinder.find(pluginPath) as PanoPluginDescriptor
    }

    /** Strips a leading `v` only; does not add or otherwise normalize the string. */
    private fun stripLeadingV(version: String) = version.removePrefix("v")

    fun isInstalled(resourceId: String, type: ResourceType): Boolean {
        if (type == ResourceType.PLUGIN) {
            return pluginManager.resolvedPlugins.any { it.pluginId == resourceId }
        }

        return uiManager.installedThemeList.any { it.id == resourceId }
    }

    fun isInstalled(resourceId: String, version: String, type: ResourceType): Boolean {
        if (type == ResourceType.PLUGIN) {
            return pluginManager.resolvedPlugins.any {
                it.pluginId == resourceId && stripLeadingV(it.descriptor.version) == stripLeadingV(version)
            }
        }

        return uiManager.installedThemeList.any {
            it.id == resourceId && stripLeadingV(it.version) == stripLeadingV(version)
        }
    }

    fun getResourceInfo(resourceId: String, type: ResourceType): Map<String, Any> {
        if (type == ResourceType.PLUGIN) {
            val plugin = pluginManager.resolvedPlugins.find { it.pluginId == resourceId } as PanoPluginWrapper
            val descriptor = plugin.descriptor as PanoPluginDescriptor

            return mapOf(
                "id" to plugin.pluginId,
                "version" to stripLeadingV(descriptor.version),
                "hash" to plugin.hash,
                "license" to descriptor.license
            )
        }

        val theme = uiManager.installedThemeList.find { it.id == resourceId }!!

        return mapOf(
            "id" to theme.id,
            "version" to stripLeadingV(theme.version),
            "hash" to theme.hash,
            "createdAt" to theme.createdAt,
            "installedBy" to theme.installedBy,
            "type" to ResourceType.THEME
        )
    }

    private fun movePathReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private suspend fun rollbackPluginJar(pluginId: String, loadPath: Path, backupPath: Path) {
        vertx.executeBlocking<Unit> {
            try {
                pluginManager.unloadPlugin(pluginId)
            } catch (_: Throwable) {
            }
            try {
                if (Files.exists(loadPath)) {
                    Files.delete(loadPath)
                }
            } catch (_: Throwable) {
            }
            if (!Files.exists(backupPath)) {
                throw IllegalStateException("Backup not found at $backupPath")
            }
            movePathReplacing(backupPath, loadPath)
            pluginManager.loadPlugin(loadPath)
            pluginManager.enablePlugin(pluginId)
            val state = pluginManager.startPlugin(pluginId)
            if (state != PluginState.STARTED) {
                throw IllegalStateException("Restored plugin did not reach STARTED (state=$state)")
            }
        }.coAwait()
    }

    private suspend fun unloadPluginForInstall(pluginId: String) {
        vertx.executeBlocking<Unit> {
            pluginManager.stopPlugin(pluginId)
            pluginManager.disablePlugin(pluginId)
            pluginManager.unloadPlugin(pluginId)
        }.coAwait()
    }

    private fun pluginStartFailure(pluginId: String, action: String): FailedToInstallResource {
        val licenseException = pluginManager.getPlugin(pluginId)
            ?.failedException
            .findLicenseRequiredInCauseChain()

        if (licenseException != null) {
            return FailedToInstallResource(
                extras = mapOf(
                    "message" to licenseException.message,
                    "licenseDeniedReason" to licenseException.reason.publicId,
                    "pluginId" to licenseException.pluginId
                )
            )
        }

        return FailedToInstallResource(
            extras = mapOf("message" to "Plugin failed to start after $action.")
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