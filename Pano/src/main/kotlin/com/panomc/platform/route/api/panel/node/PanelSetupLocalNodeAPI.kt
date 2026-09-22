package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.node.LocalNodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * Sets up the node daemon on Pano's own machine (`POST /api/panel/nodes/local/setup`).
 *
 * One button in the panel instead of the six-digit dance a remote host needs: Pano already trusts
 * a process it started itself, so the daemon is handed a one-time bootstrap token and arrives
 * already approved. The response deliberately says nothing about the node row -- there is not one
 * yet on the first run, because the daemon creates it by pairing a moment later, and the panel
 * follows that through the ordinary node list.
 *
 * Registered at route order 0 so it is matched before `/api/panel/nodes/:id`, which would
 * otherwise swallow the literal path segment and reject it as a non-numeric id.
 */
@Endpoint
class PanelSetupLocalNodeAPI(
    private val authProvider: AuthProvider,
    private val localNodeManager: LocalNodeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/nodes/local/setup", RouteType.POST))

    override val order = 0

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val status = try {
            localNodeManager.setup()
        } catch (exception: Exception) {
            throw BadRequest(extras = mapOf("message" to (exception.message ?: "The local node could not be started.")))
        }

        return Successful(status.map)
    }
}
