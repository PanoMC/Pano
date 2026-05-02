package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.error.FailedToInstallResource
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.core.http.HttpMethod
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.*

@Endpoint
class PanelUpdateResourceAPI(
    private val updateManager: UpdateManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/resources/:resourceId/stream", RouteType.GET))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("resourceId", stringSchema()))
            .queryParameter(param("state", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val resourceId = parameters.pathParameter("resourceId").string
        val state = UUID.fromString(parameters.queryParameter("state").string)

        val response = context.response()

        response.putHeader("Content-Type", "text/event-stream")
        response.putHeader("Cache-Control", "no-cache")
        response.putHeader("Connection", "keep-alive")
        response.isChunked = true

        var successAmount = 0

        try {
            updateManager.updateResource(context, resourceId, state) { it ->
                sendServerSentEventMessage(context, it)

                if (it is Successful && it !is Progress) {
                    successAmount++

                    if (successAmount == 4) {
                        response.end()
                    }
                }
            }
        } catch (e: Throwable) {
            val payload = FailedToInstallResource(extras = mapOf("message" to e.message))
            sendServerSentEventMessage(context, payload)
        }

        return null
    }

    private fun sendServerSentEventMessage(context: RoutingContext, result: Result) {
        val response = context.response()
        val responseBody = result.encode()

        response.write("data: ${responseBody}\n\n")

        if (result is com.panomc.platform.model.Error) {
            result.printStackTrace()
            response.end()
        }
    }
}