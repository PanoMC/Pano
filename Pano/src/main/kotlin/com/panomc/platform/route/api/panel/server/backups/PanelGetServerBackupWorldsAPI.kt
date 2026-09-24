package com.panomc.platform.route.api.panel.server.backups

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerBackupsPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileListMessage
import com.panomc.platform.node.message.FileReadMessage
import com.panomc.platform.server.backup.BackupWorlds
import com.panomc.platform.server.feature.ServerFeature
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.panomc.platform.util.UsageMode

/**
 * What a `WORLDS` backup would take right now: `{worlds: [names], levelName}`.
 *
 * Asked of whichever side takes the backups, through the same `FILE_LIST` and `FILE_READ` the file
 * manager uses: the root listing, `server.properties` for `level-name`, and a one-byte read of
 * `<dir>/level.dat` for every other top-level directory that could be a Bukkit multiworld. See
 * [BackupWorlds] for the rule, which is the node's and the plugin's own. A preview, not a promise
 * — a world created between this call and the backup is still taken.
 */
@Endpoint
class PanelGetServerBackupWorldsAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/backups/worlds", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerBackupsPermission(), context, id)

        // The side that takes the backups is the side whose disk the answer has to describe.
        val target = fileClient.resolve(id, getSqlClient(), ServerFeature.BACKUPS_CREATE)

        val listing = fileClient.request(target, FileListMessage(target.serverUuid, ""))

        val entries = listing.getJsonArray("entries") ?: JsonArray()

        val directories = (0 until entries.size())
            .mapNotNull { entries.getValue(it) as? JsonObject }
            .filter { it.getString("type") == "dir" }
            .mapNotNull { it.getString("name") }

        // A missing server.properties is a server that has never started, which runs on `world`.
        val properties = try {
            fileClient.request(
                target,
                FileReadMessage(target.serverUuid, "server.properties", MAX_PROPERTIES_BYTES),
                PROBE_TIMEOUT_MS
            ).getString("content")
        } catch (_: Exception) {
            null
        }

        val levelName = BackupWorlds.levelNameOf(properties)

        val withLevelDat = coroutineScope {
            BackupWorlds.probeCandidates(levelName, directories).map { directory ->
                async {
                    try {
                        fileClient.request(
                            target,
                            FileReadMessage(target.serverUuid, "$directory/level.dat", 1),
                            PROBE_TIMEOUT_MS
                        )

                        directory
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        return Successful(
            mapOf(
                "worlds" to BackupWorlds.select(levelName, directories, withLevelDat),
                "levelName" to levelName
            )
        )
    }

    companion object {
        private const val MAX_PROPERTIES_BYTES = 65_536

        private const val PROBE_TIMEOUT_MS = 5_000L
    }
}
