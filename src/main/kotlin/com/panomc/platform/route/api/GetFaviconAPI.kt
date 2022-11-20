package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser
import java.io.File

@Endpoint
class GetFaviconAPI(private val configManager: ConfigManager) : Api() {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/favicon")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result? {
        val faviconPath = configManager.getConfig().getJsonObject("file-paths").getString("favicon")

        if (faviconPath == null) {
            context.response().setStatusCode(404).end()

            return null
        }

        val path = configManager.getConfig().getString("file-uploads-folder") + "/" +
                faviconPath

        val file = File(path)

        if (!file.exists()) {
            context.response().setStatusCode(404).end()

            return null
        }

        context.response().sendFile(path)

        return null
    }
}