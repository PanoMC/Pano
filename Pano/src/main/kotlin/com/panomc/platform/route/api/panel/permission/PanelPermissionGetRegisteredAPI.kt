package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.PermissionRegistry
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelPermissionGetRegisteredAPI(
    private val permissionRegistry: PermissionRegistry
) : Api() {
    override val paths = listOf(Path("/api/panel/permission/registered", RouteType.GET))

    // Lives under /api/panel/ but extends Api, not PanelApi — it feeds the panel's permission-node
    // autocomplete, including the maintenance settings card.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val permissions = permissionRegistry.getAllPermissions()
        
        val result = permissions.mapValues { (_, perms) ->
            perms.map { perm ->
                mapOf(
                    "node" to perm.toString(),
                    "key" to perm.key,
                    "icon" to perm.iconName,
                    "source" to perm.source,
                    "pluginId" to if (perm.source != "platform") perm.source else null
                )
            }
        }

        return Successful(mapOf("data" to result))
    }
}
