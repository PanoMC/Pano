package com.panomc.platform.route.api.panel.install

import com.panomc.platform.PanoApiManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Path
import com.panomc.platform.model.Progress
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.util.*

@Endpoint
class PanelGetInstallResourceStoreStreamAPI(
    private val panoApiManager: PanoApiManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/install/store/:versionId/stream", RouteType.GET))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("versionId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result? {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val versionId = try {
            UUID.fromString(parameters.pathParameter("versionId").string)
        } catch (_: Exception) {
            throw BadRequest()
        }

        val response = context.response()

        response.putHeader("Content-Type", "text/event-stream")
        response.putHeader("Cache-Control", "no-cache")
        response.putHeader("Connection", "keep-alive")
        response.isChunked = true

        var successAmount = 0

        panoApiManager.installResourceFromStore(context, versionId) {
            sendServerSentEventMessage(context, it)

            if (it is Successful && it !is Progress) {
                successAmount++

                if (successAmount == 4) {
                    response.end()
                }
            }
        }

        return null
    }

    private fun sendServerSentEventMessage(context: RoutingContext, result: Result) {
        val response = context.response()
        val responseBody = result.encode()

        response.write("data: ${responseBody}\n\n")

        if (result is com.panomc.platform.model.Error) {
            if (!result.hasExtra("licenseDeniedReason")) {
                result.printStackTrace()
            }
            response.end()
        }
    }
}