package com.panomc.platform.route.api.panel.access

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.model.*
import com.panomc.platform.util.ProxyDetector
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/** The reverse-proxy state seen from live traffic: `{state, peers, suggestion}` (`state` is `OK` when nothing is wrong). */
@Endpoint
class PanelGetProxyStatusAPI(
    private val proxyDetector: ProxyDetector,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/access/proxy-status", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val status = proxyDetector.status()

        return Successful(
            mapOf(
                "state" to status.state,
                "peers" to status.peers,
                "suggestion" to status.suggestion
            )
        )
    }
}
