package com.panomc.platform.route.api.panel.ticket

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.util.UsageMode
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.ticket.TicketSettings
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/** What the gear on the panel's Tickets page shows. */
@Endpoint
class PanelGetTicketSettingsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/ticket/settings", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTicketsPermission(), context)

        return Successful(
            mapOf("allowNoCategory" to TicketSettings.allowNoCategory(databaseManager, getSqlClient()))
        )
    }
}
