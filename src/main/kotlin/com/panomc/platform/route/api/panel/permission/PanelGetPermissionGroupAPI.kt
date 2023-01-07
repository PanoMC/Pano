package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetPermissionGroupAPI(
    private val databaseManager: DatabaseManager
) :
    PanelApi() {
    override val paths = listOf(Path("/api/panel/permissionGroups/:id", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlConnection = createConnection(context)

        val isTherePermissionGroupById = databaseManager.permissionGroupDao.isThereById(id, sqlConnection)

        if (!isTherePermissionGroupById) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val result = mutableMapOf<String, Any?>()

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlConnection)!!

        val userCount = databaseManager.userDao.getCountOfUsersByPermissionGroupId(id, sqlConnection)

        val usernameList =
            databaseManager.userDao.getUsernamesByPermissionGroupId(id, -1, sqlConnection)

        val permissionGroupPerms =
            databaseManager.permissionGroupPermsDao.getByPermissionGroupId(id, sqlConnection).map { it.permissionId }

        result["id"] = id
        result["name"] = permissionGroup.name
        result["users"] = usernameList
        result["userCount"] = userCount
        result["permissionGroupPerms"] = permissionGroupPerms

        return Successful(result)
    }
}