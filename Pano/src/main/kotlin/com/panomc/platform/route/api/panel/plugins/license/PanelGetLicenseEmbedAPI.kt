package com.panomc.platform.route.api.panel.plugins.license

import com.panomc.platform.PanoApiManager
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageAddonsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.net.URLEncoder

/**
 * Builds the URL of the freemium license view the panel embeds from panomc.com.
 *
 * The tier catalogue (Pro, Ultra …) and what this platform owns live in the store, not in the
 * host, so the panel renders them in an iframe instead of duplicating the data. Each call mints a
 * fresh single-use token, which is why the panel hits this endpoint on every page open.
 *
 * Throws [com.panomc.platform.error.PanoNotConnected] when no Pano account is connected — the
 * panel turns that into a "connect your account" prompt.
 */
@Endpoint
class PanelGetLicenseEmbedAPI(
    private val authProvider: AuthProvider,
    private val pluginManager: PluginManager,
    private val configManager: ConfigManager,
    private val panoApiManager: PanoApiManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/plugins/:pluginId/license/embed", RouteType.GET))

    override fun isAllowedInDemo(method: HttpMethod): Boolean {
        return false
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("pluginId", stringSchema()))
            .queryParameter(optionalParam("theme", stringSchema()))
            .queryParameter(optionalParam("hl", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageAddonsPermission(), context)

        val parameters = getParameters(context)
        val pluginId = parameters.pathParameter("pluginId").string

        val wrapper = pluginManager.getPlugin(pluginId) ?: throw NotFound()
        val descriptor = wrapper.descriptor as? PanoPluginDescriptor ?: throw NotFound()

        // Only freemium plugins have purchasable tiers. Premium plugins use the DRM license card
        // and free ones have nothing to show.
        if (!descriptor.freemium) {
            throw BadRequest()
        }

        // The store resource id matches the plugin id, same assumption the install flow makes.
        val resourceId = pluginId

        val (token, state) = panoApiManager.getLicenseEmbedToken(resourceId)

        val websiteBase = configManager.config.panoWebsiteUrl.trimEnd('/')

        if (websiteBase.isEmpty()) {
            throw BadRequest()
        }

        val query = buildList {
            add("token=" + URLEncoder.encode(token, Charsets.UTF_8))
            add("state=" + URLEncoder.encode(state, Charsets.UTF_8))
            parameters.queryParameter("theme")?.string?.takeIf { it.isNotBlank() }?.let {
                add("theme=" + URLEncoder.encode(it, Charsets.UTF_8))
            }
            parameters.queryParameter("hl")?.string?.takeIf { it.isNotBlank() }?.let {
                add("hl=" + URLEncoder.encode(it, Charsets.UTF_8))
            }
        }.joinToString("&")

        val encodedResourceId = URLEncoder.encode(resourceId, Charsets.UTF_8)

        return Successful(
            mapOf(
                "data" to mapOf(
                    "url" to "$websiteBase/embed/addon-license/$encodedResourceId?$query",
                    "resourceId" to resourceId
                )
            )
        )
    }
}
