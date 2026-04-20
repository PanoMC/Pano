package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.json.schema.SchemaRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Endpoint
class PanelBannedIpsMigrationUploadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/server/banned-ips/upload", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(20 * 1024 * 1024) // 20MB

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val fileUploads = context.fileUploads()

        val fileUpload = fileUploads.find { it.name() == "file" }
            ?: throw InvalidData(extras = mapOf("message" to "banned-ips.json file is required"))

        val uploadedFile = File(fileUpload.uploadedFileName())

        if (uploadedFile.length() == 0L) {
            throw InvalidData(extras = mapOf("message" to "Uploaded file is empty"))
        }

        val text = try {
            uploadedFile.readText()
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Failed to read file: ${e.message}"))
        }

        val jsonArray: JsonArray = try {
            JsonArray(text)
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Invalid banned-ips.json format. Expected a JSON array."))
        }

        val sqlClient = getSqlClient()
        val nowMs = System.currentTimeMillis()

        val seenIps = mutableSetOf<String>()
        val entries = mutableListOf<JsonObject>()
        val previewItems = mutableListOf<Map<String, Any?>>()

        var newCount = 0
        var existingCount = 0
        var expiredCount = 0
        var invalidCount = 0

        for (raw in jsonArray) {
            val obj = raw as? JsonObject ?: run { invalidCount++; continue }

            val ip = obj.getString("ip")?.trim()
            if (ip.isNullOrEmpty()) {
                invalidCount++
                continue
            }

            if (!seenIps.add(ip.lowercase())) continue

            val createdStr = obj.getString("created")
            val source = obj.getString("source")
            val expiresStr = obj.getString("expires")
            val reason = obj.getString("reason")?.trim()

            val bannedUntilMs = parseExpires(expiresStr)
            val isExpired = bannedUntilMs != null && bannedUntilMs <= nowMs
            if (isExpired) expiredCount++

            val alreadyExists = databaseManager.bannedIpDao.existsByIp(ip, sqlClient)
            if (alreadyExists) existingCount++ else newCount++

            val status = when {
                isExpired -> "expired"
                alreadyExists -> "existing"
                else -> "new"
            }

            previewItems.add(
                mapOf(
                    "ip" to ip,
                    "source" to (source ?: ""),
                    "reason" to (reason ?: ""),
                    "createdAt" to parseCreated(createdStr),
                    "expiresAt" to bannedUntilMs,
                    "permanent" to (bannedUntilMs == null),
                    "status" to status
                )
            )

            entries.add(
                JsonObject()
                    .put("ip", ip)
                    .put("source", source ?: "")
                    .put("reason", reason ?: "")
                    .put("createdAt", parseCreated(createdStr))
                    .put("expiresAt", bannedUntilMs)
                    .put("permanent", bannedUntilMs == null)
            )
        }

        try {
            val tempFolder = File(AppConstants.TEMP_FOLDER)
            if (!tempFolder.exists()) tempFolder.mkdirs()

            val tempFile = File(AppConstants.TEMP_FOLDER + File.separator + "banned_ips_migration.json")
            tempFile.writeText(JsonArray(entries).encode())
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Failed to persist migration data: ${e.message}"))
        }

        return Successful(
            mapOf(
                "items" to previewItems,
                "totalCount" to previewItems.size,
                "newCount" to newCount,
                "existingCount" to existingCount,
                "expiredCount" to expiredCount,
                "invalidCount" to invalidCount
            )
        )
    }

    private fun parseExpires(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        if (value.equals("forever", ignoreCase = true)) return null
        return parseMinecraftDate(value)
    }

    private fun parseCreated(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        return parseMinecraftDate(value) ?: System.currentTimeMillis()
    }

    private fun parseMinecraftDate(value: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd HH:mm:ss Z",
            "yyyy-MM-dd HH:mm:ss XXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.isLenient = false
                return sdf.parse(value).time
            } catch (_: Exception) {
                // try next
            }
        }

        return null
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw InvalidData(extras = mapOf("message" to "Invalid upload"))
        }
    }
}
