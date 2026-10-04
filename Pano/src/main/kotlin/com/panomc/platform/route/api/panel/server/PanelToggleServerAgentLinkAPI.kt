package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServersPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.model.*
import com.panomc.platform.node.NodePairingCodeManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * The on/off switch of the "Link with the Pano Agent" dialog (`PUT
 * /api/panel/servers/agent-link/toggle`, SM-77), the twin of the plugin's Connect Server switch
 * (`TogglePlatformConnectAuthAPI`): body-less, flips `managed-servers.accept-agent-links`, saves
 * config.conf and answers `{ acceptAgentLinks }`, under the same permission.
 *
 * Off means everything is refused: `GET /api/panel/servers/agent-link` mints no code, every code
 * the panel already showed is dropped here -- a command somebody is typing on a server this very
 * moment stops working -- and `POST /api/node/connect` refuses an agent code exactly like a wrong
 * one. Node pairing codes are not touched. Turning it back on brings no dropped code back.
 *
 * Registered before `/api/panel/servers/:id`, like the dialog's own endpoint.
 */
@Endpoint
class PanelToggleServerAgentLinkAPI(
    private val configManager: ConfigManager,
    private val authProvider: AuthProvider,
    private val nodePairingCodeManager: NodePairingCodeManager
) : PanelApi() {
    override val order = 0

    override val usageModes = UsageMode.WITH_SERVERS


    override val paths = listOf(Path("/api/panel/servers/agent-link/toggle", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageServersPermission(), context)

        // A hand-removed block is written back, since that is where the key lives.
        val managedServers = configManager.config.managedServers
            ?: PanoConfig.Companion.ManagedServersConfig().also { configManager.config.managedServers = it }

        val acceptAgentLinks = toggle(managedServers, nodePairingCodeManager)

        configManager.saveConfig()

        return Successful(
            mapOf(
                "acceptAgentLinks" to acceptAgentLinks
            )
        )
    }

    companion object {
        /**
         * Flips [managedServers]' `accept-agent-links` and returns the new value; turning it off
         * drops every live agent code in [codes]. Pure apart from those two, so the rule can be
         * asserted without a config file.
         */
        fun toggle(managedServers: PanoConfig.Companion.ManagedServersConfig, codes: NodePairingCodeManager): Boolean {
            managedServers.acceptAgentLinks = !managedServers.acceptAgentLinks

            if (!managedServers.acceptAgentLinks) {
                codes.dropAgentCodes()
            }

            return managedServers.acceptAgentLinks
        }
    }
}
