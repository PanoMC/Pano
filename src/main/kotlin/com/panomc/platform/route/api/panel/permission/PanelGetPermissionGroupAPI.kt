package com.panomc.platform.route.api.panel.permission


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) :
    PanelApi() {
    override val paths = listOf(Path("/api/panel/permissionGroups/:id", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PERMISSION_GROUPS, context)

        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val isTherePermissionGroupById = databaseManager.permissionGroupDao.isThereById(id, sqlClient)

        if (!isTherePermissionGroupById) {
            throw NotExists()
        }

        val result = mutableMapOf<String, Any?>()

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlClient)!!

        val userCount = databaseManager.userDao.getCountOfUsersByPermissionGroupId(id, sqlClient)

        val usernameList =
            databaseManager.userDao.getUsernamesByPermissionGroupId(id, -1, sqlClient)

        val permissionGroupPerms =
            databaseManager.permissionGroupPermsDao.getByPermissionGroupId(id, sqlClient).map { it.permissionId }

        result["id"] = id
        result["name"] = permissionGroup.name
        result["users"] = usernameList
        result["userCount"] = userCount
        result["permissionGroupPerms"] = permissionGroupPerms

        return Successful(result)
    }
}