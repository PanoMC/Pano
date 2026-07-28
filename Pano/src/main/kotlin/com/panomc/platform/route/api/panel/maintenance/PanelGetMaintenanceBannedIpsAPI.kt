package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import kotlin.math.ceil

@Endpoint
class PanelGetMaintenanceBannedIpsAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/maintenance/banned-ips", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("page", numberSchema()))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 1L
        val search = parameters.queryParameter("search")?.string?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        // The ban store is an in-memory map capped at 10 000 entries, so filtering and paging here
        // is cheaper than the round trip a DAO would cost. Already sorted by ban time, descending.
        val bannedIps = maintenanceModeManager.bannedIps().filter { entry ->
            search == null ||
                    entry.ip.lowercase().contains(search) ||
                    entry.socketPeer.lowercase().contains(search) ||
                    entry.lastUsernameTried?.lowercase()?.contains(search) == true
        }

        val count = bannedIps.size.toLong()

        var totalPage = ceil(count.toDouble() / PAGE_SIZE).toLong()

        if (totalPage < 1) {
            totalPage = 1
        }

        if (page !in 1..totalPage) {
            throw PageNotFound()
        }

        return Successful(
            mapOf(
                "bannedIps" to bannedIps
                    .drop(((page - 1) * PAGE_SIZE).toInt())
                    .take(PAGE_SIZE)
                    .map { it.toJson() },
                "count" to count,
                "totalPage" to totalPage
            )
        )
    }

    companion object {
        private const val PAGE_SIZE = 25
    }
}
