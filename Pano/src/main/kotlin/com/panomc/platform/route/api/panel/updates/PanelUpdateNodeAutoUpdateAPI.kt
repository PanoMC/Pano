package com.panomc.platform.route.api.panel.updates

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageNodesPermission
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import com.panomc.platform.node.NodeDaemonUpdateService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * The Updates page's "update nodes automatically" switch (`PUT /api/panel/updates/node-auto-update`,
 * body `{ enabled }`, answer `{ enabled }`).
 *
 * It writes `managed-servers.node-auto-update` in config.conf -- the same key Settings → Updates
 * writes through `PUT /api/panel/settings` and `GET /api/panel/updates/servers` reads back as
 * `nodeAutoUpdate` -- so there is one setting whichever page it is changed from. Either the
 * platform settings permission or the nodes permission may change it: it is a platform setting that
 * only ever affects nodes (and Pano Agents).
 */
@Endpoint
class PanelUpdateNodeAutoUpdateAPI(
    private val authProvider: AuthProvider,
    private val nodeDaemonUpdateService: NodeDaemonUpdateService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/updates/node-auto-update", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("enabled", booleanSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context) &&
            !authProvider.hasPermission(ManageNodesPermission(), context)
        ) {
            throw NoPermission()
        }

        val enabled = getParameters(context).body().jsonObject.getBoolean("enabled")

        nodeDaemonUpdateService.setAutoUpdateEnabled(enabled)

        return Successful(mapOf("enabled" to enabled))
    }
}
