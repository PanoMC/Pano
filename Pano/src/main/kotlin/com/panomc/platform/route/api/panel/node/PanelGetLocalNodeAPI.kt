package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.model.Path
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.node.LocalNodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * Status of the local node's process (`GET /api/panel/nodes/local`).
 *
 * Separate from the node row this daemon owns, because the two answer different questions: the row
 * says whether a node is paired and connected, this says whether the process Pano is supposed to
 * be supervising is actually alive, and the interesting case for an admin is exactly when those
 * two disagree.
 *
 * Route order 0 for the same reason as the setup endpoint next to it.
 */
@Endpoint
class PanelGetLocalNodeAPI(
    private val authProvider: AuthProvider,
    private val localNodeManager: LocalNodeManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/nodes/local", RouteType.GET))

    override val order = 0

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        return Successful(localNodeManager.status().map)
    }
}
