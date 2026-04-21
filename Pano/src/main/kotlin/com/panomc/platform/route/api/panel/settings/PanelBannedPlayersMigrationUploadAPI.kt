package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
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
class PanelBannedPlayersMigrationUploadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/server/banned/upload", RouteType.POST))

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
            ?: throw InvalidData(extras = mapOf("message" to "banned-players.json file is required"))

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
            throw InvalidData(extras = mapOf("message" to "Invalid banned-players.json format. Expected a JSON array."))
        }

        val sqlClient = getSqlClient()
        val nowMs = System.currentTimeMillis()

        val seenUuids = mutableSetOf<String>()
        val seenUsernames = mutableSetOf<String>()
        val entries = mutableListOf<JsonObject>()
        val previewItems = mutableListOf<Map<String, Any?>>()

        var matchedCount = 0
        var unmatchedCount = 0
        var alreadyBannedCount = 0
        var expiredCount = 0
        var invalidCount = 0

        for (raw in jsonArray) {
            val obj = raw as? JsonObject ?: run { invalidCount++; continue }

            val uuid = obj.getString("uuid")?.trim()?.lowercase()
            val name = obj.getString("name")?.trim()
            val createdStr = obj.getString("created")
            val source = obj.getString("source")
            val expiresStr = obj.getString("expires")
            val reason = obj.getString("reason")?.trim()

            if (uuid.isNullOrEmpty() && name.isNullOrEmpty()) {
                invalidCount++
                continue
            }

            val normalizedUuid = uuid?.let { normalizeUuid(it) }

            val dedupKey = normalizedUuid ?: name?.lowercase() ?: continue
            if (normalizedUuid != null) {
                if (!seenUuids.add(normalizedUuid)) continue
            } else if (name != null) {
                if (!seenUsernames.add(name.lowercase())) continue
            }

            val bannedUntilMs = parseExpires(expiresStr)
            val isExpired = bannedUntilMs != null && bannedUntilMs <= nowMs
            if (isExpired) expiredCount++

            // Try to match Pano user: prefer mcUuid, then username
            val matchedUser = (normalizedUuid?.let { databaseManager.userDao.getByMcUuid(it, sqlClient) })
                ?: (name?.let { databaseManager.userDao.getByUsername(it, sqlClient) })

            val alreadyBanned = matchedUser?.let { BanUtil.isBanned(it) } ?: false
            if (matchedUser != null) matchedCount++ else unmatchedCount++
            if (alreadyBanned) alreadyBannedCount++

            val status = when {
                matchedUser == null -> "unmatched"
                alreadyBanned -> "already-banned"
                isExpired -> "expired"
                else -> "ready"
            }

            previewItems.add(
                mapOf(
                    "uuid" to (normalizedUuid ?: ""),
                    "name" to (name ?: ""),
                    "source" to (source ?: ""),
                    "reason" to (reason ?: ""),
                    "createdAt" to parseCreated(createdStr),
                    "expiresAt" to bannedUntilMs,
                    "permanent" to (bannedUntilMs == null),
                    "matchedUsername" to matchedUser?.username,
                    "matchedUserId" to matchedUser?.id,
                    "status" to status
                )
            )

            entries.add(
                JsonObject()
                    .put("uuid", normalizedUuid ?: "")
                    .put("name", name ?: "")
                    .put("source", source ?: "")
                    .put("reason", reason ?: "")
                    .put("createdAt", parseCreated(createdStr))
                    .put("expiresAt", bannedUntilMs)
                    .put("permanent", bannedUntilMs == null)
            )
        }

        // Persist parsed entries to a temp file for the import step
        try {
            val tempFolder = File(AppConstants.TEMP_FOLDER)
            if (!tempFolder.exists()) tempFolder.mkdirs()

            val tempFile = File(AppConstants.TEMP_FOLDER + File.separator + "banned_players_migration.json")
            tempFile.writeText(JsonArray(entries).encode())
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Failed to persist migration data: ${e.message}"))
        }

        return Successful(
            mapOf(
                "items" to previewItems,
                "totalCount" to previewItems.size,
                "matchedCount" to matchedCount,
                "unmatchedCount" to unmatchedCount,
                "alreadyBannedCount" to alreadyBannedCount,
                "expiredCount" to expiredCount,
                "invalidCount" to invalidCount
            )
        )
    }

    private fun normalizeUuid(raw: String): String? {
        val stripped = raw.replace("-", "").lowercase()
        if (stripped.length != 32 || !stripped.all { it.isDigit() || it in 'a'..'f' }) {
            return null
        }

        return buildString {
            append(stripped.substring(0, 8)).append('-')
            append(stripped.substring(8, 12)).append('-')
            append(stripped.substring(12, 16)).append('-')
            append(stripped.substring(16, 20)).append('-')
            append(stripped.substring(20, 32))
        }
    }

    /**
     * Parses the "expires" field from Minecraft's banned-players.json.
     *
     * Returns null for permanent bans ("forever" / empty / unparseable),
     * otherwise the epoch millis until which the ban is active.
     */
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
        // Minecraft vanilla uses this format (e.g. "2026-04-21 00:46:42 +0300")
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
