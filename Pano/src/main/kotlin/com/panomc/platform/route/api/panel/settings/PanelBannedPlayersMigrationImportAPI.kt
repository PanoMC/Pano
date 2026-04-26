package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.BannedPlayerLog
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.BanHistory
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.AuthenticationTokenType
import com.panomc.platform.util.BanUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import java.io.File

@Endpoint
class PanelBannedPlayersMigrationImportAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/server/banned/import", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("selection", arraySchema().items(stringSchema()))
                        .optionalProperty("skipExpired", booleanSchema())
                        .optionalProperty("overrideAlreadyBanned", booleanSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val body = context.body().asJsonObject()
        val selection = body.getJsonArray("selection").map { it as String }.toSet()
        val skipExpired = body.getBoolean("skipExpired") ?: true
        val overrideAlreadyBanned = body.getBoolean("overrideAlreadyBanned") ?: false

        if (selection.isEmpty()) {
            throw InvalidData(extras = mapOf("message" to "No entries selected"))
        }

        val tempFile = File(AppConstants.TEMP_FOLDER + File.separator + "banned_players_migration.json")
        if (!tempFile.exists()) {
            throw InvalidData(extras = mapOf("message" to "Migration session expired. Please upload the file again."))
        }

        val entries: JsonArray = try {
            JsonArray(tempFile.readText())
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Migration data is corrupted. Please upload the file again."))
        }

        val sqlClient = getSqlClient()
        val nowMs = System.currentTimeMillis()

        val authUserId = authProvider.getUserIdFromRoutingContext(context)
        val authUsername = databaseManager.userDao.getUsernameFromUserId(authUserId, sqlClient)!!

        var importedCount = 0
        var skippedCount = 0
        val errors = mutableListOf<Map<String, String>>()

        for (raw in entries) {
            val obj = raw as? JsonObject ?: continue

            val uuid = obj.getString("uuid").orEmpty()
            val name = obj.getString("name").orEmpty()

            val selectionKey = uuid.ifEmpty { name.lowercase() }
            if (selectionKey.isEmpty() || selectionKey !in selection) continue

            val reason = obj.getString("reason")?.takeIf { it.isNotBlank() }
            val source = obj.getString("source")?.takeIf { it.isNotBlank() }
            val bannedUntil = obj.getLong("expiresAt")

            val isExpired = bannedUntil != null && bannedUntil <= nowMs
            if (isExpired && skipExpired) {
                skippedCount++
                continue
            }

            val matchedUser = (uuid.takeIf { it.isNotEmpty() }?.let { databaseManager.userDao.getByMcUuid(it, sqlClient) })
                ?: (name.takeIf { it.isNotEmpty() }?.let { databaseManager.userDao.getByUsername(it, sqlClient) })

            if (matchedUser == null) {
                skippedCount++
                errors.add(
                    mapOf(
                        "item" to (name.ifEmpty { uuid }),
                        "error" to "No matching Pano user"
                    )
                )
                continue
            }

            val alreadyBanned = BanUtil.isBanned(matchedUser)
            if (alreadyBanned && !overrideAlreadyBanned) {
                skippedCount++
                continue
            }

            try {
                // Trim ban reason to column limit (255)
                val banMessage = buildBanMessage(reason, source)?.take(255)
                val sourceLabel = "MIGRATION:${source?.takeIf { it.isNotBlank() } ?: "SERVER"}"
                    .take(255)

                databaseManager.userDao.banPlayer(matchedUser.id, banMessage, bannedUntil, sqlClient)

                databaseManager.banHistoryDao.add(
                    BanHistory(
                        userId = matchedUser.id,
                        reason = banMessage,
                        emailNotified = false,
                        bannedUntil = bannedUntil,
                        bannedBy = authUsername,
                        bannedBySystem = true,
                        source = sourceLabel
                    ),
                    sqlClient
                )

                tokenProvider.invalidateTokensBySubjectAndType(
                    matchedUser.id.toString(),
                    AuthenticationTokenType,
                    sqlClient
                )

                databaseManager.panelActivityLogDao.add(
                    BannedPlayerLog(
                        authUserId,
                        authUsername,
                        matchedUser.username,
                        banMessage ?: "unknown",
                        bannedUntil ?: 0L,
                        bannedUntil == null,
                        true
                    ),
                    sqlClient
                )

                importedCount++
            } catch (e: Exception) {
                skippedCount++
                errors.add(
                    mapOf(
                        "item" to matchedUser.username,
                        "error" to (e.message ?: "Unknown error")
                    )
                )
            }
        }

        try {
            tempFile.delete()
        } catch (_: Exception) {
        }

        return Successful(
            mapOf(
                "importedCount" to importedCount,
                "skippedCount" to skippedCount,
                "errors" to errors
            )
        )
    }

    private fun buildBanMessage(reason: String?, source: String?): String? {
        val parts = mutableListOf<String>()
        if (!reason.isNullOrBlank()) parts.add(reason)
        if (!source.isNullOrBlank() && !source.equals("Server", ignoreCase = true)) {
            parts.add("(by $source)")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
