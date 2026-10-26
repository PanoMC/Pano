package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.backup.PanoBackupUploadTickets
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PanoHostError
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.node.NodeAuthProvider
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * What a node sending a backup straight to Pano Backup asks of Pano (`POST /api/v1/node/pano-backup/:ticket/:action`,
 * protocol 8). Node token only, and only the node the ticket was issued to:
 *
 * - `progress {phase, done, total}` → `{}`
 * - `archive {size, sha256}` → `{partSize, parts[{partNumber, url}]}` (the upload session the archive is PUT into)
 * - `missing {snapshotId, chunks[{id, size}]}` → `{missing[]}`
 * - `pack {snapshotId, size}` → `{packId, url}`
 *
 * The bytes never come here: they go from the node to the storage under the URLs these answers carry.
 */
@Endpoint
class NodePanoBackupUploadAPI(
    private val nodeAuthProvider: NodeAuthProvider,
    private val tickets: PanoBackupUploadTickets
) : Api() {
    override val paths = listOf(Path("/node/pano-backup/:ticket/:action", RouteType.POST))

    // Authenticates with a node token, never a user JWT.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!nodeAuthProvider.isAuthenticated(context)) throw NotExists()

        val nodeId = nodeAuthProvider.getNodeIdFromRoutingContext(context) ?: throw NotExists()
        val ticket = tickets.resolve(context.pathParam("ticket"), nodeId) ?: throw NotExists()
        val body = runCatching { context.body().asJsonObject() }.getOrNull() ?: JsonObject()
        val handler = ticket.handler

        try {
            return when (context.pathParam("action")) {
                "progress" -> {
                    handler.progress(body.getString("phase") ?: "", body.getLong("done") ?: 0L, body.getLong("total") ?: 0L)

                    Successful()
                }

                "archive" -> {
                    val size = body.getLong("size")?.takeIf { it > 0 } ?: throw BadRequest()
                    val sha256 = body.getString("sha256")?.takeIf { it.matches(SHA256) } ?: throw BadRequest()
                    val session = handler.archive(size, sha256)

                    Successful(
                        mapOf(
                            "partSize" to session.partSize,
                            "parts" to session.parts.map { mapOf("partNumber" to it.partNumber, "url" to it.url) }
                        )
                    )
                }

                "missing" -> {
                    val chunks = (body.getJsonArray("chunks") ?: JsonArray()).map {
                        val chunk = it as? JsonObject ?: throw BadRequest()

                        (chunk.getString("id") ?: throw BadRequest()) to (chunk.getInteger("size") ?: throw BadRequest())
                    }

                    Successful(mapOf("missing" to handler.missing(snapshotId(body), chunks).toList()))
                }

                "pack" -> {
                    val slot = handler.packSlot(snapshotId(body), body.getLong("size")?.takeIf { it > 0 } ?: throw BadRequest())

                    Successful(mapOf("packId" to slot.id, "url" to slot.url))
                }

                else -> throw NotExists()
            }
        } catch (e: PanoHostException) {
            throw PanoHostError.of(e)
        }
    }

    private fun snapshotId(body: JsonObject) = body.getString("snapshotId")?.takeIf { it.isNotBlank() } ?: throw BadRequest()

    companion object {
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
