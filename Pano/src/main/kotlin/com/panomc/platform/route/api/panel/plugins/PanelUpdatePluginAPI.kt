package com.panomc.platform.route.api.panel.plugins


import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import org.pf4j.PluginState

@Endpoint
class PanelUpdatePluginAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("pluginId", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("status", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_ADDONS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val pluginId = parameters.pathParameter("pluginId").string

        val pluginWrapper = pluginManager.getPluginWrappers().firstOrNull { it.pluginId == pluginId }

        if (pluginWrapper == null) {
            throw NotFound()
        }

        val status = data.getBoolean("status") ?: null

        try {
            if (status != null) {
                if (status) {
                    if (pluginWrapper.pluginState == PluginState.STARTED) {
                        return Successful()
                    }

                    pluginManager.enablePlugin(pluginId)
                    pluginManager.startPlugin(pluginId)
                }

                if (!status) {
                    if (pluginWrapper.pluginState != PluginState.STARTED) {
                        return Successful()
                    }

                    pluginManager.stopPlugin(pluginId)
                    pluginManager.disablePlugin(pluginId)
                }
            }
        } catch (e: Exception) {
            return Successful(
                mapOf(
                    "status" to pluginWrapper.pluginState,
                    "error" to if (pluginWrapper.failedException == null) null else TextUtil.getStackTraceAsString(
                        pluginWrapper.failedException
                    )
                )
            )
        }

        return Successful(
            mapOf(
                "status" to pluginWrapper.pluginState
            )
        )
    }
}