package com.panomc.platform.route.api.panel.plugins.license

import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.license.EntitlementManager
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Re-reads which freemium packages this platform owns for a plugin.
 *
 * Buying a package on panomc.com does not notify the platform, so a plugin that asked
 * `hasTier(...)` at startup keeps its paid features locked until something re-checks. This endpoint
 * is that something — the panel offers it as "refresh" on the addon page, mirroring the premium
 * license refresh.
 *
 * Deliberately returns **no package details**: the caller learns only whether the check succeeded.
 * What the operator owns is shown by the store's embedded view, not by this endpoint, so the panel
 * has no second (and possibly stale) copy of that state.
 */
@Endpoint
class PanelRefreshPluginTiersAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val entitlementManager: EntitlementManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/license/tiers/refresh", RouteType.POST))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val pluginId = parameters.pathParameter("pluginId").string

        val wrapper = pluginManager.getPlugin(pluginId) ?: throw NotFound()
        val descriptor = wrapper.descriptor as? PanoPluginDescriptor ?: throw NotFound()

        // Premium plugins refresh through the DRM license endpoint instead.
        if (!descriptor.freemium) {
            throw BadRequest()
        }

        // Throws PanoNotConnected / PanoConnectFailed, which the panel turns into a message.
        entitlementManager.refresh(pluginId)

        return Successful()
    }
}
