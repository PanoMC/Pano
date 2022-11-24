package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUpdatePermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/permissionGroups/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("name", stringSchema())
                        .property("addedUsers", arraySchema().items(stringSchema()))
                        .property("removedUsers", arraySchema().items(stringSchema()))
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        var name = data.getString("name")

        val addedUsers = data.getJsonArray("addedUsers").map { it.toString() }
        val removedUsers = data.getJsonArray("removedUsers").map { it.toString() }

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(databaseManager, context)

        val isTherePermissionGroupById = databaseManager.permissionGroupDao.isThereById(id, sqlConnection)

        if (!isTherePermissionGroupById) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlConnection)!!

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlConnection)!!

        val admins = databaseManager.userDao.getUsernamesByPermissionGroupId(adminPermissionGroupId, -1, sqlConnection)
            .map { it.lowercase() }

        var addUserAdminMatchCount = 0
        var removeUserAdminMatchCount = 0

        admins.forEach { admin ->
            if (addedUsers.find { it.lowercase() == admin } != null) {
                addUserAdminMatchCount++
            }

            if (removedUsers.find { it.lowercase() == admin } != null) {
                removeUserAdminMatchCount++
            }
        }

        if (addUserAdminMatchCount == admins.size || removeUserAdminMatchCount == admins.size) {
            throw Error(ErrorCode.LAST_ADMIN)
        }

        if (addedUsers.isNotEmpty()) {
            val areAddedUsersExists = databaseManager.userDao.areUsernamesExists(addedUsers, sqlConnection)

            if (!areAddedUsersExists) {
                throw Error(ErrorCode.INVALID_DATA)
            }
        }

        if (removedUsers.isNotEmpty()) {
            val areRemovedUsersExists = databaseManager.userDao.areUsernamesExists(removedUsers, sqlConnection)

            if (!areRemovedUsersExists) {
                throw Error(ErrorCode.INVALID_DATA)
            }
        }

        if (permissionGroup.name != name) {
            val isTherePermissionGroupByName =
                databaseManager.permissionGroupDao.isThereByName(name, sqlConnection)

            if (isTherePermissionGroupByName) {
                throw Errors(mapOf("name" to true))
            }

            if (permissionGroup.name == "admin") {
                throw Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION)
            }

            databaseManager.permissionGroupDao.update(
                PermissionGroup(id, name),
                sqlConnection
            )
        }

        if (addedUsers.isNotEmpty()) {
            databaseManager.userDao.setPermissionGroupByUsernames(id, addedUsers, sqlConnection)
        }

        if (removedUsers.isNotEmpty()) {
            databaseManager.userDao.setPermissionGroupByUsernames(-1, removedUsers, sqlConnection)
        }

        return Successful()
    }

    private fun validateForm(
        name: String
    ) {
        val errors = mutableMapOf<String, Boolean>()

        if (name.isEmpty() || name.length > 32)
            errors["name"] = true

        if (errors.isNotEmpty()) {
            throw Errors(errors)
        }
    }

    private fun getSystematicName(name: String) = name.lowercase().replace("\\s+".toRegex(), "-")
}