package com.panomc.platform.route.api.panel.panoPlus

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.model.*
import com.panomc.platform.panoplus.PanoPlusInfo
import com.panomc.platform.panoplus.PanoPlusManager
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Pano+ for the panel (`GET /api/v1/panel/pano-plus[?refresh=true]`):
 * `{enabled, priceMonthly, priceYearly, currency, benefits, me, hintsDismissed, plusUrl}`. Any panel user may read
 * it. `enabled` is false when Pano+ is off on panomc.com, this Pano is not connected or panomc.com
 * cannot be reached; the panel then shows nothing about Pano+.
 */
@Endpoint
class PanelGetPanoPlusAPI(private val panoPlusManager: PanoPlusManager) : PanelApi() {
    override val paths = listOf(Path("/pano-plus", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(optionalParam("refresh", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val refresh = getParameters(context).queryParameter("refresh")?.string == "true"

        return Successful(
            view(panoPlusManager.info(refresh), panoPlusManager.hintsDismissed(), panoPlusManager.plusUrl())
        )
    }

    companion object {
        internal fun view(info: PanoPlusInfo, hintsDismissed: Boolean, plusUrl: String?): Map<String, Any?> {
            if (!info.enabled || plusUrl == null) {
                return mapOf(
                    "enabled" to false,
                    "priceMonthly" to null,
                    "priceYearly" to null,
                    "currency" to null,
                    "benefits" to emptyMap<String, Any>(),
                    "me" to null,
                    "hintsDismissed" to hintsDismissed,
                    "plusUrl" to null
                )
            }

            return mapOf(
                "enabled" to true,
                "priceMonthly" to info.priceMonthly,
                "priceYearly" to info.priceYearly,
                "currency" to info.currency,
                "benefits" to info.benefits,
                "me" to info.me?.toMap(),
                "hintsDismissed" to hintsDismissed,
                "plusUrl" to plusUrl
            )
        }
    }
}
