package com.panomc.platform.route.api.panel.transfers

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.auth.panel.permission.ManageServerPluginsPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NodeOffline
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.NodeManager
import com.panomc.platform.node.transfer.TransferSpaceService
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * `POST /api/v1/panel/transfers/check` — whether a file of `size` bytes fits before it is sent.
 *
 * Asked by the panel before every big upload: into an existing server (`serverId`, the disk of that
 * server's directory) or for the create wizard (`nodeId`, the node's data disk). The answer is the
 * node's free space and what the file needs with the reserve; a file that does not fit is refused
 * with `NOT_ENOUGH_SPACE` before a byte crosses the network. `free` is null when the node cannot say
 * (an older daemon, or a server reached through its plugin): the upload then goes ahead and the
 * node's own check while writing is what protects the disk.
 */
@Endpoint
class PanelTransferCheckAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient,
    private val nodeManager: NodeManager,
    private val transferSpaceService: TransferSpaceService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/transfers/check", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .optionalProperty("serverId", numberSchema().nullable())
                        .optionalProperty("nodeId", numberSchema().nullable())
                        .requiredProperty("size", numberSchema())
                        .optionalProperty("format", stringSchema().nullable())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val data = getParameters(context).body().jsonObject

        val size = data.getLong("size") ?: throw BadRequest()
        val serverId = data.getLong("serverId")
        val nodeId = data.getLong("nodeId")

        if (size < 0 || (serverId == null) == (nodeId == null)) {
            throw BadRequest()
        }

        val free = if (serverId != null) {
            // Files or plugins: either right is enough to ask, since both upload into the server.
            if (!authProvider.hasPermission(ManageServerFilesPermission(), context, serverId) &&
                !authProvider.hasPermission(ManageServerPluginsPermission(), context, serverId)
            ) {
                throw NoPermission()
            }

            val target = fileClient.resolve(serverId, getSqlClient())

            target.nodeId?.let { transferSpaceService.freeOnNode(it, target.serverUuid) }
        } else {
            authProvider.requirePermission(CreateServersPermission(), context)

            val id = nodeId ?: throw BadRequest()

            if (!nodeManager.isConnected(id)) {
                throw NodeOffline()
            }

            transferSpaceService.freeOnNode(id)
        }

        TransferSpaceService.requireFits(free, size)

        return Successful(
            mapOf(
                "free" to free,
                "required" to TransferSpaceService.required(size)
            )
        )
    }
}
