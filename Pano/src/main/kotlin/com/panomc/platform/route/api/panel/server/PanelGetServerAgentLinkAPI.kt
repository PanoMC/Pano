package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeInstallScriptProvider
import com.panomc.platform.node.NodePairingCodeManager
import com.panomc.platform.node.PanoUrlOverride
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * What the "Link an existing server with the Pano Agent" dialog shows (`GET
 * /api/panel/servers/agent-link`, SM-74): an agent code for the caller and the one-line commands
 * that put the agent into a server's folder and run it there.
 *
 * Response: `{ enabled, code, expiresAt, panoUrl, jarUrl, jarFileName, downloadCommand,
 * downloadCommandWindows, runCommand, startCommand, javaVersion }` (see
 * [NodeInstallScriptProvider.agentLink]). The admin
 * saves `pano-agent.jar` in the server's folder (the download command, the jar URL in a browser, or
 * an FTP upload), stops the server, runs `runCommand` there once, and from then on starts the
 * server with `startCommand` wherever it used to start the server jar.
 *
 * The code is not the node pairing code (see [NodePairingCodeManager]): it is minted for the caller,
 * pairs for one minute and only once, and a daemon that pairs with it is approved immediately,
 * never listed as a node, and on its first connect Pano creates the server row and adopts the folder
 * in place (`IMPORT_SERVER` mode `IN_PLACE`). Asking again while it is valid returns the same code,
 * so reopening the dialog does not invalidate a command already typed on the server. That is why it
 * needs [CreateServersPermission] rather than the nodes permission -- it creates a server, and only
 * that. `?panoUrl=` works as on the nodes page, for a machine that reaches Pano at another address.
 *
 * While `managed-servers.accept-agent-links` is off (the dialog's switch, SM-77) nothing is minted
 * and the answer is `{ enabled: false, jarUrl, jarFileName, startCommand, javaVersion }`
 * ([NodeInstallScriptProvider.agentLinkDisabled]); otherwise the response above carries
 * `enabled: true`.
 *
 * Registered before `/api/panel/servers/:id`, which would otherwise take `agent-link` for an id.
 */
@Endpoint
class PanelGetServerAgentLinkAPI(
    private val authProvider: AuthProvider,
    private val nodePairingCodeManager: NodePairingCodeManager,
    private val nodeInstallScriptProvider: NodeInstallScriptProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val order = 0

    override val usageModes = UsageMode.WITH_SERVERS


    override val paths = listOf(Path("/api/panel/servers/agent-link", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val panoUrl = PanoUrlOverride.sanitize(context.queryParams().get("panoUrl"))

        if (!configManager.config.effectiveManagedServers.acceptAgentLinks) {
            return Successful(NodeInstallScriptProvider.agentLinkDisabled(nodeInstallScriptProvider.agentJarUrl(panoUrl)))
        }

        val agentCode = nodePairingCodeManager.agentCodeFor(authProvider.getUserIdFromRoutingContext(context))

        return Successful(
            NodeInstallScriptProvider.agentLink(
                code = agentCode.code,
                expiresAt = agentCode.expiresAt,
                panoUrl = nodeInstallScriptProvider.panoUrl(panoUrl),
                jarUrl = nodeInstallScriptProvider.agentJarUrl(panoUrl)
            )
        )
    }
}
