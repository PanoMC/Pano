package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelGetPermissionGroupAPI(
    setupManager: SetupManager,
    authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) :
    PanelApi(setupManager, authProvider) {
    override val routeType = RouteType.GET

    override val routes = arrayListOf("/api/panel/permissionGroups/:id")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermissionGroupById = databaseManager.permissionGroupDao.isThereById(id, sqlConnection)

        if (!isTherePermissionGroupById) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val result = mutableMapOf<String, Any?>()

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlConnection)!!

        result["id"] = id
        result["name"] = permissionGroup.name

        val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(id, sqlConnection)

        result["countOfUsers"] = count

        val usernameList =
            databaseManager.userDao.getUsernamesByPermissionGroupId(id, -1, sqlConnection)

        result["users"] = usernameList

        return Successful(result)
    }
}