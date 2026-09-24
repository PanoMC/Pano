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
import com.panomc.platform.util.UsageMode

/**
 * The Windows half of [NodeInstallScriptAPI] (`GET /api/node/install.ps1`).
 *
 * Takes the same `?panoUrl=` override, for the same reasons.
 */
@Endpoint
class NodeInstallScriptWindowsAPI(
    private val nodeInstallScriptProvider: NodeInstallScriptProvider
) : Api() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/node/install.ps1", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result? {
        val panoUrl = PanoUrlOverride.sanitize(context.queryParams().get("panoUrl"))

        context.response()
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .end(nodeInstallScriptProvider.powerShellScript(panoUrl))
            .coAwait()

        return null
    }
}
