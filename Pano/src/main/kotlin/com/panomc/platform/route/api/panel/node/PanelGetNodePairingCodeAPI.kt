package com.panomc.platform.route.api.panel.node

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodePairingCodeManager
import com.panomc.platform.node.PanoUrlOverride
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * The rotating pairing code for the Add-node modal, with how long it is still good for and the
 * one-line commands that use it.
 *
 * Registered at a lower order than the rest of the node routes: `/api/panel/nodes/:id` would
 * otherwise match `pairing-code` first and reject it as a non-numeric id, and Vert.x fails the
 * first matching route rather than trying the next one.
 *
 * `?panoUrl=` puts a different address in the commands, for a machine that cannot reach Pano at
 * the website URL — NAT, an SSH tunnel, a lab network; [PanoUrlOverride] has the full reasoning.
 * Everything else about the pairing is unchanged: the code is the same code.
 */
@Endpoint
class PanelGetNodePairingCodeAPI(
    private val authProvider: AuthProvider,
    private val nodePairingCodeManager: NodePairingCodeManager,
    private val nodeInstallScriptProvider: NodeInstallScriptProvider
) : PanelApi() {
    override val order = 0

    override val usageModes = UsageMode.WITH_SERVERS


    override val paths = listOf(Path("/api/panel/nodes/pairing-code", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageNodesPermission(), context)

        val panoUrl = PanoUrlOverride.sanitize(context.queryParams().get("panoUrl"))

        val generatedAt = nodePairingCodeManager.getGeneratedAt()
        val pairingCode = nodePairingCodeManager.getPairingCode().toString()

        return Successful(
            mapOf(
                "pairingCode" to pairingCode,
                "generatedAt" to generatedAt,
                "expiresAt" to generatedAt + NodePairingCodeManager.ROTATE_INTERVAL_MS,
                // The commands carry the code, so they rotate with it. Built here rather than in
                // the panel because only Pano knows its own public URL and which release of the
                // daemon matches it.
                "installCommand" to nodeInstallScriptProvider.installCommand(pairingCode, panoUrl),
                "installCommandWindows" to nodeInstallScriptProvider.installCommandWindows(pairingCode, panoUrl)
            )
        )
    }
}
