package com.panomc.platform.route.api

import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetSiteInfoAPI(private val configManager: ConfigManager) : Api() {
    override val paths = listOf(Path("/api/siteInfo", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any>()
        val config = configManager.getConfig()

        response["locale"] = config.getString("locale")
        response["websiteName"] = config.getString("website-name")
        response["websiteDescription"] = config.getString("website-description")
        response["keywords"] = config.getJsonArray("keywords")
        response["panoVersion"] = VERSION

        return Successful(response)
    }
}