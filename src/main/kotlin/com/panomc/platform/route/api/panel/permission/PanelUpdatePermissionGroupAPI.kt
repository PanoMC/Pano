package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PanelUpdatePermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permissionGroups/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .property("name", stringSchema())
                        .property("addedUsers", arraySchema().items(stringSchema()))
                        .property("removedUsers", arraySchema().items(stringSchema()))
                        .property(
                            "permissions",
                            arraySchema()
                                .items(
                                    objectSchema()
                                        .requiredProperty("id", numberSchema())
                                        .requiredProperty("selected", booleanSchema())
                                )
                        )
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PERMISSION_GROUPS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        var name = data.getString("name")

        val addedUsers = data.getJsonArray("addedUsers").map { it.toString() }
        val removedUsers = data.getJsonArray("removedUsers").map { it.toString() }
        val permissions = data.getJsonArray("permissions").map { it as JsonObject }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(context)

        validateSelfUpdating(addedUsers, removedUsers, userId, sqlConnection)

        validatePermissionGroupExists(id, sqlConnection)

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlConnection)!!

        validateHavePermissionToUpdateAdminPermGroup(id, adminPermissionGroupId, userId, sqlConnection)

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlConnection)!!

        val permissionsInDb = databaseManager.permissionDao.getPermissions(sqlConnection)

        validatePermissions(permissions, permissionsInDb)

        validateIsAdminPermGroup(permissions, permissionsInDb, permissionGroup, name, adminPermissionGroupId)

        validateAreAddedUsersExist(addedUsers, sqlConnection)
        validateAreRemovedUsersExist(removedUsers, sqlConnection)

        validateIsPermissionGroupNameExists(name, permissionGroup, sqlConnection)

        permissions.forEach { permission ->
            val permissionId = permission.getLong("id")
            val isPermissionSelected = permission.getBoolean("selected")

            val permissionExists = databaseManager.permissionGroupPermsDao.doesPermissionGroupHavePermission(
                id,
                permissionId,
                sqlConnection
            )

            if (isPermissionSelected && !permissionExists) {
                databaseManager.permissionGroupPermsDao.addPermission(id, permissionId, sqlConnection)
            }

            if (!isPermissionSelected && permissionExists) {
                databaseManager.permissionGroupPermsDao.removePermission(id, permissionId, sqlConnection)
            }
        }

        if (permissionGroup.name != name) {
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

    private fun validatePermissions(
        permissions: List<JsonObject>,
        permissionsInDb: List<Permission>
    ) {
        val notExistingPermissions =
            permissionsInDb.filter { permissionInDb -> permissions.find { it.getLong("id") == permissionInDb.id } == null }

        if (notExistingPermissions.isNotEmpty()) {
            throw Error(ErrorCode.SOME_PERMISSIONS_ARENT_EXIST)
        }
    }

    private suspend fun validateIsPermissionGroupNameExists(
        name: String,
        permissionGroup: PermissionGroup,
        sqlConnection: SqlConnection
    ) {
        if (permissionGroup.name != name) {
            val isTherePermissionGroupByName =
                databaseManager.permissionGroupDao.isThereByName(name, sqlConnection)

            if (isTherePermissionGroupByName) {
                throw Errors(mapOf("name" to true))
            }
        }
    }

    private suspend fun validateAreRemovedUsersExist(removedUsers: List<String>, sqlConnection: SqlConnection) {
        if (removedUsers.isNotEmpty()) {
            val areRemovedUsersExists = databaseManager.userDao.areUsernamesExists(removedUsers, sqlConnection)

            if (!areRemovedUsersExists) {
                throw Error(ErrorCode.SOME_USERS_ARENT_EXISTS)
            }
        }
    }

    private suspend fun validateAreAddedUsersExist(addedUsers: List<String>, sqlConnection: SqlConnection) {
        if (addedUsers.isNotEmpty()) {
            val areAddedUsersExists = databaseManager.userDao.areUsernamesExists(addedUsers, sqlConnection)

            if (!areAddedUsersExists) {
                throw Error(ErrorCode.SOME_USERS_ARENT_EXISTS)
            }
        }
    }

    private fun validateIsAdminPermGroup(
        permissions: List<JsonObject>,
        permissionsInDb: List<Permission>,
        permissionGroup: PermissionGroup,
        name: String,
        adminPermissionGroupId: Long
    ) {
        var throwError = false

        if (permissionGroup.id == adminPermissionGroupId) {
            val selectedPermissionCount = permissions.count { it.getBoolean("selected") }

            if (selectedPermissionCount != permissionsInDb.size) {
                throwError = true
            }
        }

        if (permissionGroup.name != name && permissionGroup.id == adminPermissionGroupId) {
            throwError = true
        }

        if (throwError) {
            throw Error(ErrorCode.CANT_UPDATE_ADMIN_PERMISSION)
        }
    }

    private suspend fun validateHavePermissionToUpdateAdminPermGroup(
        permissionGroupId: Long,
        adminPermissionGroupId: Long,
        userId: Long,
        sqlConnection: SqlConnection
    ) {
        if (permissionGroupId == adminPermissionGroupId) {
            val user = databaseManager.userDao.getById(userId, sqlConnection)

            if (user!!.permissionGroupId != adminPermissionGroupId) {
                throw Error(ErrorCode.NO_PERMISSION_TO_UPDATE_ADMIN_PERM_GROUP)
            }
        }
    }

    private suspend fun validateSelfUpdating(
        addedUsers: List<String>,
        removedUsers: List<String>,
        userId: Long,
        sqlConnection: SqlConnection
    ) {
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)!!.lowercase()

        if (addedUsers.any { it.lowercase() == username } || removedUsers.any { it.lowercase() == username }) {
            throw Error(ErrorCode.CANT_UPDATE_PERM_GROUP_YOURSELF)
        }
    }

    private suspend fun validatePermissionGroupExists(permissionGroupId: Long, sqlConnection: SqlConnection) {
        val isTherePermissionGroupById =
            databaseManager.permissionGroupDao.isThereById(permissionGroupId, sqlConnection)

        if (!isTherePermissionGroupById) {
            throw Error(ErrorCode.PERM_GROUP_NOT_EXISTS)
        }
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