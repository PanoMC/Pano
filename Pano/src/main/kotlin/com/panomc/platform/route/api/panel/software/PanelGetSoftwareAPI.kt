package com.panomc.platform.route.api.panel.software

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.CreateServersPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.software.ServerSoftwareCatalog
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.util.UsageMode

/**
 * The software options and versions the create-server wizard offers.
 *
 * Never fails on an upstream outage: a provider Pano could not reach comes back with an empty
 * version list, so the rest of the catalog still renders.
 */
@Endpoint
class PanelGetSoftwareAPI(
    private val authProvider: AuthProvider,
    private val serverSoftwareCatalog: ServerSoftwareCatalog
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/software", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(CreateServersPermission(), context)

        val catalog = serverSoftwareCatalog.getCatalog()

        return Successful(mapOf("software" to JsonArray(catalog.map { it.toJsonObject() })))
    }
}
