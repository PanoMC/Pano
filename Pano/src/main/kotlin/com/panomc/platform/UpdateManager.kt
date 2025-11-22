package com.panomc.platform

import com.panomc.platform.AppConstants.UPDATER_JAR
import com.panomc.platform.AppConstants.UPDATE_ICON_FOLDER
import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.Main.Companion.IS_GUI
import com.panomc.platform.Main.Companion.STAGE
import com.panomc.platform.auth.panel.log.UpdatedPlatformLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.*
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.notification.NotificationManager
import com.panomc.platform.notification.type.panel.PanoUpdateFoundNotification
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.UpdatePeriod
import com.panomc.platform.util.VersionUtil
import io.vertx.core.Vertx
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UpdateManager(
    private val vertx: Vertx,
    private val webClient: WebClient,
    private val databaseManager: DatabaseManager,
    private val panoApiManager: PanoApiManager,
    private val configManager: ConfigManager,
    private val installManager: InstallManager,
    private val setupManager: SetupManager,
    private val notificationManager: NotificationManager
) {
    companion object {
        const val PLATFORM_UPDATE_CHECK_INFO = "platform_update_check_info"
        const val RESOURCES_UPDATE_CHECK_INFO = "resources_update_check_info"
        const val UPDATE_LAST_CHECK = "update_last_checked_at"
    }

    @Autowired
    private lateinit var logger: Logger

    suspend fun checkPlatformUpdate(background: Boolean) {
        try {
            val latestRelease = if (STAGE == ReleaseStage.RELEASE) {
                val getLatestReleaseResponse = webClient
                    .getAbs("https://api.github.com/repos/${AppConstants.REPO}/releases/latest")
                    .send()
                    .coAwait()

                if (getLatestReleaseResponse.statusCode() == 404) {
                    deletePlatformUpdateInfo(background)

                    return
                }

                if (getLatestReleaseResponse.statusCode() != 200) {
                    throw InternalServerError()
                }

                getLatestReleaseResponse.bodyAsJsonObject()
            } else {
                val getReleasesResponse = webClient
                    .getAbs("https://api.github.com/repos/${AppConstants.REPO}/releases")
                    .send()
                    .coAwait()

                if (getReleasesResponse.statusCode() != 200) {
                    throw InternalServerError()
                }

                val releases = getReleasesResponse.bodyAsJsonArray().map { it as JsonObject }

                if (releases.isEmpty()) {
                    deletePlatformUpdateInfo(background)

                    return
                }

                releases.first()
            }

            val changelog = latestRelease.getString("body")
            val version = latestRelease.getString("tag_name")
            val assets = latestRelease.getJsonArray("assets").map { it as JsonObject }
            val releaseDate = latestRelease.getString("published_at")

            if (!VersionUtil.isVersionHigher(version, Main.VERSION)) {
                deletePlatformUpdateInfo(background)

                return
            }

            val asset = assets.find { it.getString("name").endsWith(".jar") }

            if (asset == null) {
                deletePlatformUpdateInfo(background)

                return
            }

            val downloadUrl = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            val hash = asset.getString("digest")
            val fileName = asset.getString("name")

            val sqlClient = databaseManager.getSqlClient()
            val propertyExists = databaseManager.systemPropertyDao.existsByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

            val versionInfo = JsonObject(
                mapOf(
                    "changelog" to changelog,
                    "version" to version,
                    "downloadUrl" to downloadUrl,
                    "fileName" to fileName,
                    "size" to size,
                    "hash" to hash,
                    "releaseDate" to releaseDate,
                    "channel" to VersionUtil.getReleaseType(version),
                    "state" to UUID.randomUUID()
                )
            )

            if (background) {
                logger.info("A Pano update found: v{} -> {}", Main.VERSION, version)
            }

            if (propertyExists) {
                databaseManager.systemPropertyDao.update(PLATFORM_UPDATE_CHECK_INFO, versionInfo.encode(), sqlClient)
                return
            }

            databaseManager.systemPropertyDao.add(
                SystemProperty(
                    option = PLATFORM_UPDATE_CHECK_INFO,
                    value = versionInfo.encode()
                ), sqlClient
            )
        } catch (e: Exception) {
            e.printStackTrace()

            if (background) {
                logger.error("Failed to check Pano updates!")
                return
            }

            throw InternalServerError()
        }
    }

    private suspend fun deletePlatformUpdateInfo(background: Boolean) {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.systemPropertyDao.deleteByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

        if (background) {
            logger.info("No Pano update found!")
        }
    }

    suspend fun checkResourceUpdates(background: Boolean) {
        if (!panoApiManager.isConnected()) {
            return
        }

        try {
            panoApiManager.updatePlatformMetadata()

            val updates = panoApiManager.getUpdates() ?: JsonArray()

            val sqlClient = databaseManager.getSqlClient()

            val existingProperty =
                databaseManager.systemPropertyDao.getByOption(RESOURCES_UPDATE_CHECK_INFO, sqlClient)

            val updateIconFolder = configManager.config.fileUploadsFolder + File.separator + UPDATE_ICON_FOLDER

            val tempFolder = File(AppConstants.TEMP_FOLDER)

            if (!tempFolder.exists() || !tempFolder.isDirectory) {
                tempFolder.deleteRecursively()
                tempFolder.mkdirs()
            }

            val downloadedFiles = mutableMapOf<String, String>()

            updates.map { it as JsonObject }.forEach {
                it.put("state", UUID.randomUUID())
                val id = it.getString("id")

                val url = "/resources/${id}/" + if (ResourceType.valueOf(it.getString("type")) == ResourceType.THEME) {
                    "screenshots/" + it.getJsonObject("screenshot").getString("id")
                } else {
                    "icon"
                } + "?preview=true"

                val fileSystem = vertx.fileSystem()
                val temporaryFilePath = AppConstants.TEMP_FOLDER + File.separator + "pano-download_" + UUID.randomUUID()
                val writeStream = fileSystem.openBlocking(
                    temporaryFilePath,
                    OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)
                )

                val getFileResponse = panoApiManager.createRequest(HttpMethod.GET, url)
                    .`as`(BodyCodec.pipe(writeStream))
                    .send()
                    .coAwait()

                if (getFileResponse.statusCode() != 200) {
                    throw PanoConnectFailed()
                }

                downloadedFiles[id] = temporaryFilePath
            }

            val folder = File(updateIconFolder)

            if (folder.exists()) {
                folder.deleteRecursively()
            }

            updates.map { it as JsonObject }.forEach {
                val id = it.getString("id")

                val iconFileName = UUID.randomUUID()

                val target = File(updateIconFolder + iconFileName)
                val file = File(downloadedFiles[id]!!)

                target.parentFile.mkdirs()
                file.copyTo(target)
                file.delete()

                it.put("iconFileName", iconFileName)
            }

            if (background) {
                if (updates.size() == 0) {
                    logger.info("No resource update found!")
                } else {
                    logger.info(
                        "{} resource updates found! {}",
                        updates.size(),
                        updates.map { it as JsonObject }.map { "${it.getString("id")}@${it.getString("version")}" })
                }
            }

            if (existingProperty != null) {
                databaseManager.systemPropertyDao.update(RESOURCES_UPDATE_CHECK_INFO, updates.encode(), sqlClient)
                return
            }

            databaseManager.systemPropertyDao.add(
                SystemProperty(
                    option = RESOURCES_UPDATE_CHECK_INFO,
                    value = updates.encode()
                ), sqlClient
            )
        } catch (e: Error) {
            if (e is PanoNotConnected) {
                val sqlClient = databaseManager.getSqlClient()

                databaseManager.systemPropertyDao.update(RESOURCES_UPDATE_CHECK_INFO, JsonArray().encode(), sqlClient)
                panoApiManager.removePanoAccount()
            }

            if (background) {
                return
            }

            throw e
        } catch (e: Exception) {
            if (background) {
                return
            }

            e.printStackTrace()
            throw InternalServerError()
        }
    }

    suspend fun checkUpdates(background: Boolean = false) {
        if (background) {
            logger.info("Checking for updates...")
        }

        updateLastCheck()

        checkPlatformUpdate(background)
        checkResourceUpdates(background)
    }

    suspend fun updatePlatform(userId: Long?, state: UUID, progressHandler: (result: Result) -> Unit) {
        try {
            val sqlClient = databaseManager.getSqlClient()

            val platformUpdateInfoOption =
                databaseManager.systemPropertyDao.getByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)
                    ?: throw FailedToUpdatePlatform(extras = mapOf("message" to "There are no updates. Please run check updates."))
            val platformUpdateInfo = JsonObject(platformUpdateInfoOption.value)
            val versionState = UUID.fromString(platformUpdateInfo.getString("state"))

            if (state != versionState) {
                throw FailedToUpdatePlatform(extras = mapOf("message" to "There are no updates. Please run check updates."))
            }

            progressHandler.invoke(Successful()) // Getting platform update info success

            val tempFolder = File(AppConstants.TEMP_FOLDER)

            if (!tempFolder.exists() || !tempFolder.isDirectory) {
                tempFolder.deleteRecursively()
                tempFolder.mkdirs()
            }

            val fileSystem = vertx.fileSystem()
            val temporaryFilePath = AppConstants.TEMP_FOLDER + File.separator + "pano-download_" + UUID.randomUUID()
            val writeStream = fileSystem.openBlocking(
                temporaryFilePath,
                OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)
            )

            webClient
                .getAbs(platformUpdateInfo.getString("downloadUrl"))
                .`as`(BodyCodec.pipe(writeStream))
                .send()
                .coAwait()

            progressHandler.invoke(Successful()) // Downloading update success

            val hash = platformUpdateInfo.getString("hash").split("sha256:")[1]
            val version = platformUpdateInfo.getString("version")

            val temporaryFile = File(temporaryFilePath)

            if (!HashUtil.verifyFileHash(temporaryFile, hash)) {
                throw InvalidPlatformUpdateFile()
            }

            progressHandler.invoke(Successful()) // Verifying hash success

            val panoUpdaterJarPath = extractPanoUpdaterJar()

            progressHandler.invoke(Successful()) // Extracting pano updater done

            val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()

            // Detect current pano.jar path
            val targetJar = Path.of(
                Main::class.java.protectionDomain.codeSource.location.toURI()
            ).toAbsolutePath().toString()

            // Assume the downloaded update jar is in working dir
            val newUpdateJar = Path.of(temporaryFilePath).toAbsolutePath().toString()

            val config = configManager.config
            val serverConfig = config.server
            val host = serverConfig.host
            val port = serverConfig.port

            val args = mutableListOf(
                javaBin, "-jar", panoUpdaterJarPath.toAbsolutePath().toString(),
                "--target", targetJar,
                "--update", newUpdateJar,
                "--host", host,
                "--port", port.toString(),
                "--restart"
            )

            if (!IS_GUI) {
                args.add("-nogui")
            }

            ProcessBuilder(args)
                .inheritIO()
                .start()

            progressHandler.invoke(Successful()) // Installation start success

            if (userId != null) {
                val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

                databaseManager.panelActivityLogDao.add(
                    UpdatedPlatformLog(
                        userId,
                        username,
                        "v" + Main.VERSION,
                        version,
                    ), sqlClient
                )
            }

            vertx.close().coAwait()
            exitProcess(0)
        } catch (e: Error) {
            progressHandler.invoke(e)
        } catch (e: Exception) {
            progressHandler.invoke(InvalidPlatformUpdateFile(extras = mapOf("message" to e.message)))
        }
    }

    suspend fun updateResource(
        context: RoutingContext,
        resourceId: String,
        state: UUID,
        progressHandler: (result: Result) -> Unit
    ) {
        try {
            val sqlClient = databaseManager.getSqlClient()

            val platformUpdateInfoOption =
                databaseManager.systemPropertyDao.getByOption(RESOURCES_UPDATE_CHECK_INFO, sqlClient)
                    ?: throw FailedToUpdateResource(extras = mapOf("message" to "There are no updates. Please run check updates."))
            val resourceUpdateInfo = JsonArray(platformUpdateInfoOption.value).map { it as JsonObject }
                .find { it.getString("id") == resourceId }
                ?: throw FailedToUpdateResource(extras = mapOf("message" to "There are no updates. Please run check updates."))

            val versionState = UUID.fromString(resourceUpdateInfo.getString("state"))

            if (state != versionState) {
                throw FailedToUpdateResource(extras = mapOf("message" to "There are no updates. Please run check updates."))
            }

            val versionId = UUID.fromString(resourceUpdateInfo.getString("versionId"))

            var successAmount = 0

            panoApiManager.installResourceFromStore(context, versionId) {
                if (it is Successful) {
                    successAmount++

                    if (successAmount == 4) {
                        val foundUpdateInfo = JsonArray(platformUpdateInfoOption.value).map { it as JsonObject }
                            .find { it.getString("id") == resourceId }!!
                        val iconFileName = foundUpdateInfo.getString("iconFileName")

                        if (iconFileName != null) {
                            val updateIconFolder =
                                configManager.config.fileUploadsFolder + File.separator + UPDATE_ICON_FOLDER
                            val file = File(updateIconFolder + iconFileName)

                            if (file.exists()) {
                                file.delete()
                            }
                        }

                        val filteredUpdateList =
                            JsonArray(JsonArray(platformUpdateInfoOption.value).filter { (it as JsonObject).getString("id") != resourceId })

                        CoroutineScope(vertx.dispatcher()).launch {
                            databaseManager.systemPropertyDao.update(
                                RESOURCES_UPDATE_CHECK_INFO,
                                filteredUpdateList.encode(),
                                sqlClient
                            )
                        }
                    }
                }

                progressHandler.invoke(it)
            }
        } catch (e: Error) {
            progressHandler.invoke(e)
        } catch (e: Exception) {
            progressHandler.invoke(InvalidPlatformUpdateFile(extras = mapOf("message" to e.message)))
        }
    }

    internal suspend fun init() {
        checkDidUpgrade()

        startUpdateChecker()
    }

    fun startUpdateChecker() {
        logger.info("Started update checker.")

        vertx.setPeriodic(1000 * 60) { // each minute run
            CoroutineScope(vertx.dispatcher()).launch {
                if (!shouldCheckUpdates()) {
                    return@launch
                }

                val sqlClient = databaseManager.getSqlClient()
                val oldPlatformUpdateInfo =
                    databaseManager.systemPropertyDao.getByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

                try {
                    checkUpdates(true)
                } catch (_: Error) {
                } catch (_: Exception) {
                }

                val platformUpdateInfo =
                    databaseManager.systemPropertyDao.getByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

                if (oldPlatformUpdateInfo == null && platformUpdateInfo != null) {
                    sendPanoUpdateFoundNotification(sqlClient)

                    return@launch
                }

                if (oldPlatformUpdateInfo != null && platformUpdateInfo != null) {
                    val oldPlatformUpdateInfoJsonObject = JsonObject(oldPlatformUpdateInfo.value)
                    val platformUpdateInfoJsonObject = JsonObject(platformUpdateInfo.value)

                    oldPlatformUpdateInfoJsonObject.remove("state")
                    platformUpdateInfoJsonObject.remove("state")

                    if (oldPlatformUpdateInfoJsonObject.encode() != platformUpdateInfoJsonObject.encode()) {
                        sendPanoUpdateFoundNotification(sqlClient)

                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun sendPanoUpdateFoundNotification(sqlClient: SqlClient) {
        notificationManager.sendNotificationToAllWithPermission(
            notificationType = PanoUpdateFoundNotification(),
            panelPermission = ManagePlatformSettingsPermission(),
            sqlClient = sqlClient
        )
    }

    private suspend fun shouldCheckUpdates(): Boolean {
        if (!setupManager.isSetupDone()) {
            return false
        }

        val sqlClient = databaseManager.getSqlClient()

        val config = configManager.config
        if (config.updatePeriod == UpdatePeriod.NEVER) {
            return false
        }

        val lastUpdateCheck = databaseManager.systemPropertyDao.getByOption(UPDATE_LAST_CHECK, sqlClient) ?: return true
        val lastUpdateCheckDate = lastUpdateCheck.value.toLong()

        val now = LocalDateTime.now()
        val time = Instant.ofEpochMilli(lastUpdateCheckDate)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

        if (config.updatePeriod == UpdatePeriod.ONCE_PER_DAY && time.plusDays(1).isBefore(now)) {
            return true
        }

        if (config.updatePeriod == UpdatePeriod.ONCE_PER_WEEK && time.plusWeeks(1).isBefore(now)) {
            return true
        }

        if (config.updatePeriod == UpdatePeriod.ONCE_PER_MONTH && time.plusMonths(1).isBefore(now)) {
            return true
        }

        return false
    }

    private suspend fun checkDidUpgrade() {
        val panoUpdaterJar = File(UPDATER_JAR)

        if (!panoUpdaterJar.exists()) {
            return
        }

        logger.info("Detected an upgrade.")
        panoUpdaterJar.delete()

        val sqlClient = databaseManager.getSqlClient()

        databaseManager.systemPropertyDao.deleteByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)
    }

    suspend fun getPlatformUpdateInfo(): JsonObject? {
        val sqlClient = databaseManager.getSqlClient()

        val platformUpdateInfo =
            databaseManager.systemPropertyDao.getByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

        val platformUpdate = platformUpdateInfo?.value?.let { JsonObject(it) } ?: return null

        val version = platformUpdate.getString("version")

        if (version == Main.VERSION) {
            return null
        }

        return platformUpdate
    }

    suspend fun getResourcesUpdateList(): List<JsonObject> {
        val sqlClient = databaseManager.getSqlClient()
        val resourceUpdatesInfo =
            databaseManager.systemPropertyDao.getByOption(RESOURCES_UPDATE_CHECK_INFO, sqlClient)

        val resourceUpdatesConverted = resourceUpdatesInfo?.value?.let { JsonArray(it) } ?: JsonArray()

        return resourceUpdatesConverted.map { it as JsonObject }.filter {
            val type = ResourceType.valueOf(it.getString("type"))
            val id = it.getString("id")

            installManager.isInstalled(id, type)
        }
    }

    /**
     * Extracts pano-updater.jar from pano-updater.zip (in resources) to the current working directory.
     */
    private suspend fun extractPanoUpdaterJar(): Path = withContext(Dispatchers.IO) {
        val resourcePath = "pano-updater.zip"
        val outputPath = Path.of(UPDATER_JAR)

        val inStream = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        ZipInputStream(BufferedInputStream(inStream)).use { zis ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entry: ZipEntry? = zis.nextEntry
            var written = false

            while (entry != null) {
                if (!entry.isDirectory && entry.name == UPDATER_JAR) {
                    Files.createDirectories(outputPath.parent ?: Path.of("."))
                    BufferedOutputStream(
                        Files.newOutputStream(
                            outputPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE
                        )
                    ).use { out ->
                        var read = zis.read(buffer)
                        while (read > 0) {
                            out.write(buffer, 0, read)
                            read = zis.read(buffer)
                        }
                    }
                    written = true
                    break
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }

            if (!written) {
                throw IllegalStateException("$UPDATER_JAR not found inside $resourcePath")
            }
        }

        outputPath
    }

    private suspend fun updateLastCheck() {
        val sqlClient = databaseManager.getSqlClient()

        if (databaseManager.systemPropertyDao.existsByOption(UPDATE_LAST_CHECK, sqlClient)) {
            databaseManager.systemPropertyDao.update(
                UPDATE_LAST_CHECK,
                System.currentTimeMillis().toString(),
                sqlClient
            )
            return
        }

        databaseManager.systemPropertyDao.add(
            SystemProperty(
                option = UPDATE_LAST_CHECK,
                value = System.currentTimeMillis().toString()
            ), sqlClient
        )
    }
}