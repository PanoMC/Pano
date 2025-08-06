package com.panomc.platform

import com.panomc.platform.Main.Companion.STAGE
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.InternalServerError
import com.panomc.platform.error.NotFound
import com.panomc.platform.util.VersionUtil
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UpdateManager(
    private val webClient: WebClient,
    private val databaseManager: DatabaseManager,
    private val panoApiManager: PanoApiManager
) {
    companion object {
        const val PLATFORM_UPDATE_CHECK_INFO = "platform_update_check_info"
        const val RESOURCES_UPDATE_CHECK_INFO = "resources_update_check_info"
        const val UPDATE_LAST_CHECK = "update_last_checked_at"
    }

    suspend fun checkPlatformUpdate() {
        try {
            val latestRelease = if (STAGE == ReleaseStage.RELEASE) {
                val getLatestReleaseResponse = webClient
                    .getAbs("https://api.github.com/repos/${AppConstants.REPO}/releases/latest")
                    .send()
                    .coAwait()

                if (getLatestReleaseResponse.statusCode() == 404) {
                    throw NotFound()
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
                    throw NotFound()
                }

                releases.first()
            }

            val changelog = latestRelease.getString("body")
            val version = latestRelease.getString("tag_name")
            val assets = latestRelease.getJsonArray("assets").map { it as JsonObject }
            val releaseDate = latestRelease.getString("published_at")

            if (!VersionUtil.isVersionHigher(version, Main.VERSION)) {
                throw NotFound()
            }

            val asset = assets.find { it.getString("name").endsWith(".jar") } ?: throw NotFound()
            val downloadUrl = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            val hash = asset.getString("digest")

            val sqlClient = databaseManager.getSqlClient()
            val propertyExists = databaseManager.systemPropertyDao.existsByOption(PLATFORM_UPDATE_CHECK_INFO, sqlClient)

            val versionInfo = JsonObject(
                mapOf(
                    "changelog" to changelog,
                    "version" to version,
                    "downloadUrl" to downloadUrl,
                    "size" to size,
                    "hash" to hash,
                    "releaseDate" to releaseDate,
                    "channel" to VersionUtil.getReleaseType(version)
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

    suspend fun checkResourceUpdates() {
        if (!panoApiManager.isConnected()) {
            return
        }

        try {
            panoApiManager.updatePlatformMetadata()

            val updates = panoApiManager.getUpdates()

            val sqlClient = databaseManager.getSqlClient()

            val propertyExists =
                databaseManager.systemPropertyDao.existsByOption(RESOURCES_UPDATE_CHECK_INFO, sqlClient)

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
        checkResourceUpdates()
        checkPlatformUpdate()
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