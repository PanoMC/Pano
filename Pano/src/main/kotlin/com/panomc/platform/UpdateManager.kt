package com.panomc.platform

import com.panomc.platform.AppConstants.UPDATER_JAR
import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.Main.Companion.IS_GUI
import com.panomc.platform.Main.Companion.STAGE
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.FailedToUpdatePlatform
import com.panomc.platform.error.FailedToUpdateResource
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.InvalidPlatformUpdateFile
import com.panomc.platform.model.Error
import com.panomc.platform.model.Result
import com.panomc.platform.model.Successful
import com.panomc.platform.util.HashUtil
import com.panomc.platform.util.VersionUtil
import io.vertx.core.Vertx
import io.vertx.core.file.OpenOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
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
    private val installManager: InstallManager
) {
    companion object {
        const val PLATFORM_UPDATE_CHECK_INFO = "platform_update_check_info"
        const val RESOURCES_UPDATE_CHECK_INFO = "resources_update_check_info"
        const val UPDATE_LAST_CHECK = "update_last_checked_at"
    }

    @Autowired
    private lateinit var logger: Logger

    suspend fun checkPlatformUpdate() {
        try {
            val latestRelease = if (STAGE == ReleaseStage.RELEASE) {
                val getLatestReleaseResponse = webClient
                    .getAbs("https://api.github.com/repos/${AppConstants.REPO}/releases/latest")
                    .send()
                    .coAwait()

                if (getLatestReleaseResponse.statusCode() == 404) {
                    deletePlatformUpdateInfo()

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
                    deletePlatformUpdateInfo()

                    return
                }

                releases.first()
            }

            val changelog = latestRelease.getString("body")
            val version = latestRelease.getString("tag_name")
            val assets = latestRelease.getJsonArray("assets").map { it as JsonObject }
            val releaseDate = latestRelease.getString("published_at")

            if (!VersionUtil.isVersionHigher(version, Main.VERSION)) {
                deletePlatformUpdateInfo()

                return
            }

            val asset = assets.find { it.getString("name").endsWith(".jar") }

            if (asset == null) {
                deletePlatformUpdateInfo()

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
            throw InternalServerError()
        }
    }

    private suspend fun deletePlatformUpdateInfo() {
        val sqlClient = databaseManager.getSqlClient()

        databaseManager.systemPropertyDao.deleteByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)
    }

    suspend fun checkResourceUpdates() {
        if (!panoApiManager.isConnected()) {
            return
        }

        try {
            panoApiManager.updatePlatformMetadata()

            val updates = panoApiManager.getUpdates() ?: JsonArray()

            val sqlClient = databaseManager.getSqlClient()

            val propertyExists =
                databaseManager.systemPropertyDao.existsByOption(RESOURCES_UPDATE_CHECK_INFO, sqlClient)

            updates.forEach {
                (it as JsonObject).put("state", UUID.randomUUID())
            }

            if (propertyExists) {
                databaseManager.systemPropertyDao.update(RESOURCES_UPDATE_CHECK_INFO, updates.encode(), sqlClient)
                return
            }

            databaseManager.systemPropertyDao.add(
                SystemProperty(
                    option = RESOURCES_UPDATE_CHECK_INFO,
                    value = updates.encode()
                ), sqlClient
            )
        } catch (e: Exception) {
            e.printStackTrace()
            throw InternalServerError()
        }
    }

    suspend fun checkUpdates() {
        updateLastCheck()
        checkPlatformUpdate()
        checkResourceUpdates()
    }

    suspend fun updatePlatform(state: UUID, progressHandler: (result: Result) -> Unit) {
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

            if (IS_GUI) {
                args.add("--nogui")
            }

            ProcessBuilder(args)
                .inheritIO()
                .start()

            progressHandler.invoke(Successful()) // Installation start success

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

    suspend fun getPlatformUpdateeInfo(): JsonObject? {
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