package com.panomc.platform.route.api

import com.panomc.platform.Main.Companion.VERSION
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class GetSiteInfoAPI(private val configManager: ConfigManager) : Api() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/siteInfo")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .build()

    override suspend fun handler(context: RoutingContext): Result {
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