package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.PermissionRegistry
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelPermissionGetRegisteredAPI(
    private val permissionRegistry: PermissionRegistry
) : PanelApi() {
    override val paths = listOf(Path("/permission/registered", RouteType.GET))

    // Feeds the panel's permission-node autocomplete (the maintenance settings card too), so it is a panel API:
    // the list names every installed plugin and is not for anonymous callers.

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
