package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.PanelApi
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaParser

@Endpoint
class PanelGetPermissionsAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/permissions")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser).build()

    override suspend fun handler(context: RoutingContext): Result {
        val sqlConnection = createConnection(databaseManager, context)

        val permissions = databaseManager.permissionDao.getPermissions(sqlConnection)

        val result = mutableMapOf<String, Any?>()

        result["permissions"] = permissions

        val permissionGroups = databaseManager.permissionGroupDao.getPermissionGroups(sqlConnection)

        val permissionGroupList: List<MutableMap<String, Any?>> = permissionGroups.map { permissionGroup ->
            mutableMapOf(
                "id" to permissionGroup.id,
                "name" to permissionGroup.name
            )
        }

        val getPermissionGroupData: suspend (PermissionGroup) -> Unit = { permissionGroup ->
            val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(permissionGroup.id, sqlConnection)

            permissionGroupList.find { it["id"] == permissionGroup.id }!!["userCount"] = count

            val usernameList =
                databaseManager.userDao.getUsernamesByPermissionGroupId(permissionGroup.id, 3, sqlConnection)

            permissionGroupList.find { it["id"] == permissionGroup.id }!!["users"] = usernameList
        }

        permissionGroups.forEach {
            getPermissionGroupData(it)
        }

        result["permissionGroups"] = permissionGroupList

        val permissionGroupPerms = databaseManager.permissionGroupPermsDao.getPermissionGroupPerms(sqlConnection)

        val permissionGroupPermIdListMap = permissionGroupPerms
            .distinctBy { it.permissionGroupId }
            .associateBy({ it.permissionGroupId }, { mutableListOf<Int>() })

        permissionGroupPerms.forEach { perm ->
            permissionGroupPermIdListMap[perm.permissionGroupId]!!.add(perm.permissionId)
        }

        result["permissionGroupPerms"] = permissionGroupPermIdListMap

        return Successful(result)
    }
}