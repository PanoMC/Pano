package com.panomc.platform.route.api.panel.maintenance

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.maintenance.MaintenanceModeManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelGetMaintenanceBannedIpsAPI(
    private val authProvider: AuthProvider,
    private val maintenanceModeManager: MaintenanceModeManager
) : PanelApi() {
    override val paths = listOf(Path("/maintenance/banned-ips", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository))
            .queryParameter(optionalParam("search", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val page = Paging.request(context, DEFAULT_PAGE_SIZE)
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

        Paging.requireInRange(page, count)

        return Successful(
            Paging.response(
                bannedIps.drop(page.offset.toInt()).take(page.limit).map { it.toJson() },
                count,
                page
            )
        )
    }

    companion object {
        /** Entries per page when the client sends no `pageSize` (as before the page shape). */
        const val DEFAULT_PAGE_SIZE = 25
    }
}
