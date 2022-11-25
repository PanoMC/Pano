package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.util.AppConstants
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class GetPostThumbnailAPI(private val configManager: ConfigManager) : Api() {
    override val paths = listOf(Path("/api/post/thumbnail/:filename", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("filename", stringSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val filename = parameters.pathParameter("filename").string

        val path = configManager.getConfig()
            .getString("file-uploads-folder") + "/${AppConstants.DEFAULT_POST_THUMBNAIL_UPLOAD_PATH}/" +
                filename

        val file = File(path)

        if (!file.exists()) {
            context.response().setStatusCode(404).end()

            return null
        }

        context.response().sendFile(path)

        return null
    }
}