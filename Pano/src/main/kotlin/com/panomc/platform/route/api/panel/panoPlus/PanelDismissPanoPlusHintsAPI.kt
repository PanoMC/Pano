package com.panomc.platform.route.api.panel.panoPlus

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.panoplus.PanoPlusManager
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * `POST /api/v1/panel/pano-plus/hints/dismiss`: turns every Pano+ hint in this panel off for good
 * (one switch for the whole Pano, stored as a system property). The grace bar is not a hint and stays.
 */
@Endpoint
class PanelDismissPanoPlusHintsAPI(private val panoPlusManager: PanoPlusManager) : PanelApi() {
    override val paths = listOf(Path("/pano-plus/hints/dismiss", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        panoPlusManager.dismissHints()

        return Successful(mapOf("hintsDismissed" to true))
    }
}
