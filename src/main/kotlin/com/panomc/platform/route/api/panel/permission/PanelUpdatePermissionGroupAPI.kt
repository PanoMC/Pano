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
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUpdatePermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    setupManager: SetupManager,
    private val authProvider: AuthProvider
) : PanelApi(setupManager, authProvider) {
    override val paths = listOf(Path("/api/panel/permissionGroups/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
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

        val userId = authProvider.getUserIdFromRoutingContext(context)

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(databaseManager, context)

        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)!!.lowercase()

        if (addedUsers.any { it.lowercase() == username } || removedUsers.any { it.lowercase() == username }) {
            throw Error(ErrorCode.CANT_UPDATE_PERM_GROUP_YOURSELF)
        }

        val isTherePermissionGroupById = databaseManager.permissionGroupDao.isThereById(id, sqlConnection)

        if (!isTherePermissionGroupById) {
            throw Error(ErrorCode.PERM_GROUP_NOT_EXISTS)
        }

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlConnection)!!

        if (id == adminPermissionGroupId) {
            val user = databaseManager.userDao.getById(userId, sqlConnection)

            if (user!!.permissionGroupId != adminPermissionGroupId) {
                throw Error(ErrorCode.NO_PERMISSION_TO_UPDATE_ADMIN_PERM_GROUP)
            }
        }

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlConnection)!!

        if (permissionGroup.name != name && permissionGroup.id == adminPermissionGroupId) {
            throw Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION)
        }

        if (addedUsers.isNotEmpty()) {
            val areAddedUsersExists = databaseManager.userDao.areUsernamesExists(addedUsers, sqlConnection)

            if (!areAddedUsersExists) {
                throw Error(ErrorCode.SOME_USERS_ARENT_EXISTS)
            }
        }

        if (removedUsers.isNotEmpty()) {
            val areRemovedUsersExists = databaseManager.userDao.areUsernamesExists(removedUsers, sqlConnection)

            if (!areRemovedUsersExists) {
                throw Error(ErrorCode.SOME_USERS_ARENT_EXISTS)
            }
        }

        if (permissionGroup.name != name) {
            val isTherePermissionGroupByName =
                databaseManager.permissionGroupDao.isThereByName(name, sqlConnection)

            if (isTherePermissionGroupByName) {
                throw Errors(mapOf("name" to true))
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