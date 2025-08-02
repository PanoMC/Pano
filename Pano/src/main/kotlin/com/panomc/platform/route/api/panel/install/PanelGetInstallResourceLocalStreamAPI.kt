package com.panomc.platform.route.api.panel.install

import com.panomc.platform.AppConstants
import com.panomc.platform.InstallManager
import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.error.InvalidResourceFile
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class PanelGetInstallResourceLocalStreamAPI(
    private val installManager: InstallManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/install/local/:type/:fileName/stream", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("fileName", stringSchema()))
            .pathParameter(param("type", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        val parameters = getParameters(context)

        val type = try {
            ResourceType.valueOf(parameters.pathParameter("type").string)
        } catch (_: Exception) {
            throw InvalidResourceFile()
        }
        val fileName = parameters.pathParameter("fileName").string

        val response = context.response()

        response.putHeader("Content-Type", "text/event-stream")
        response.putHeader("Cache-Control", "no-cache")
        response.putHeader("Connection", "keep-alive")
        response.isChunked = true

        val tempFolder = File(AppConstants.TEMP_FOLDER)
        val file = File(tempFolder, fileName)

        if (!file.exists()) {
            throw InvalidResourceFile()
        }

        installManager.installResource(null, null, file, type) {
            sendServerSentEventMessage(context, it)

            if (it is Error) {
                file.delete()
            }
        }

        return null
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() is Result) {
            sendServerSentEventMessage(context, context.failure() as Result)
        } else {
            sendServerSentEventMessage(
                context,
                FailedToInstallResource(extras = mapOf("message" to context.failure().message))
            )
        }
    }

    private fun sendServerSentEventMessage(context: RoutingContext, result: Result) {
        val response = context.response()
        val responseBody = result.encode()

        response.write("data: ${responseBody}\n\n")

        if (result is Error) {
            result.printStackTrace()
            response.end()
        }
    }
}