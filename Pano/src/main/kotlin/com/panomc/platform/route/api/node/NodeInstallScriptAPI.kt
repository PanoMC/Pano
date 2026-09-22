package com.panomc.platform.route.api.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.Api
import com.panomc.platform.model.MaintenanceAccess
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.PanoUrlOverride
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * The installer a new node is set up with (`GET /api/node/install.sh`).
 *
 * Public and unauthenticated, like every `get the installer` URL: the script itself grants
 * nothing — pairing still needs a code an admin read out of the panel — and the one-line command
 * has to work on a machine that has never talked to this Pano before, including while the site is
 * in maintenance.
 *
 * `?panoUrl=` renders the script for a Pano the node reaches somewhere other than the website URL
 * — a tunnel, a LAN address, split-horizon DNS; see [PanoUrlOverride]. It only decides where the
 * script downloads the daemon from, and whoever fetches the script already chose that host by
 * typing this URL, so there is nothing to protect here that the request itself does not already
 * decide.
 */
@Endpoint
class NodeInstallScriptAPI(
    private val nodeInstallScriptProvider: NodeInstallScriptProvider
) : Api() {
    override val paths = listOf(Path("/api/node/install.sh", RouteType.GET))

    // Setting a node up is exactly the kind of work an operator does *during* maintenance.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val panoUrl = PanoUrlOverride.sanitize(context.queryParams().get("panoUrl"))

        context.response()
            .putHeader("Content-Type", "text/x-shellscript; charset=utf-8")
            // Pinned to this Pano's version, so a cached copy would install the wrong daemon
            // after an update.
            .putHeader("Cache-Control", "no-store")
            .end(nodeInstallScriptProvider.shellScript(panoUrl))
            .coAwait()

        return null
    }
}
