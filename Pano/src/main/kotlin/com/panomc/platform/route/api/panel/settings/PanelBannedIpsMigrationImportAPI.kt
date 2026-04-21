package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.BannedIp
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
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
class PanelBannedIpsMigrationImportAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/server/banned-ips/import", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("selection", arraySchema().items(stringSchema()))
                        .optionalProperty("skipExpired", booleanSchema())
                        .optionalProperty("overrideExisting", booleanSchema())
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
        val overrideExisting = body.getBoolean("overrideExisting") ?: false

        if (selection.isEmpty()) {
            throw InvalidData(extras = mapOf("message" to "No entries selected"))
        }

        val tempFile = File(AppConstants.TEMP_FOLDER + File.separator + "banned_ips_migration.json")
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
        var updatedCount = 0
        var skippedCount = 0
        val errors = mutableListOf<Map<String, String>>()

        for (raw in entries) {
            val obj = raw as? JsonObject ?: continue

            val ip = obj.getString("ip").orEmpty()
            if (ip.isEmpty() || ip !in selection) continue

            val reason = obj.getString("reason")?.takeIf { it.isNotBlank() }
            val source = obj.getString("source")?.takeIf { it.isNotBlank() }
            val bannedUntil = obj.getLong("expiresAt")
            val createdAt = obj.getLong("createdAt") ?: nowMs

            val isExpired = bannedUntil != null && bannedUntil <= nowMs
            if (isExpired && skipExpired) {
                skippedCount++
                continue
            }

            val exists = databaseManager.bannedIpDao.existsByIp(ip, sqlClient)
            if (exists && !overrideExisting) {
                skippedCount++
                continue
            }

            try {
                val reasonLabel = buildReason(reason, source)?.take(255)
                val sourceLabel = "MIGRATION:${source ?: "SERVER"}".take(255)

                databaseManager.bannedIpDao.upsert(
                    BannedIp(
                        ip = ip,
                        reason = reasonLabel,
                        bannedUntil = bannedUntil,
                        bannedBy = authUsername,
                        source = sourceLabel,
                        bannedBySystem = true,
                        createdAt = createdAt,
                        updatedAt = nowMs
                    ),
                    sqlClient
                )

                if (exists) updatedCount++ else importedCount++
            } catch (e: Exception) {
                skippedCount++
                errors.add(
                    mapOf(
                        "item" to ip,
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
                "updatedCount" to updatedCount,
                "skippedCount" to skippedCount,
                "errors" to errors
            )
        )
    }

    private fun buildReason(reason: String?, source: String?): String? {
        val parts = mutableListOf<String>()
        if (!reason.isNullOrBlank()) parts.add(reason)
        if (!source.isNullOrBlank() && !source.equals("Server", ignoreCase = true)) {
            parts.add("(by $source)")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
