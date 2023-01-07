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
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.sqlclient.SqlConnection

@Endpoint
class PanelAddPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permissionGroups", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("name", stringSchema())
                        .property("addedUsers", arraySchema().items(stringSchema()))
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

        var name = data.getString("name")

        val addedUsers = data.getJsonArray("addedUsers").map { it.toString() }
        val permissions = data.getJsonArray("permissions").map { it as JsonObject }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        validateForm(name)

        name = getSystematicName(name)

        val sqlConnection = createConnection(context)

        validateSelfUpdating(addedUsers, userId, sqlConnection)

        validateIsPermissionGroupNameExists(name, sqlConnection)

        val permissionsInDb = databaseManager.permissionDao.getPermissions(sqlConnection)

        validatePermissions(permissions, permissionsInDb)

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlConnection)!!

        validateAddedUsersContainAdmin(adminPermissionGroupId, addedUsers, sqlConnection)

        validateAreAddedUsersExist(addedUsers, sqlConnection)

        val id = databaseManager.permissionGroupDao.add(PermissionGroup(name = name), sqlConnection)

        permissions.filter { it.getBoolean("selected") }.forEach { permission ->
            val permissionId = permission.getLong("id")

            databaseManager.permissionGroupPermsDao.addPermission(id, permissionId, sqlConnection)
        }

        if (addedUsers.isNotEmpty()) {
            databaseManager.userDao.setPermissionGroupByUsernames(id, addedUsers, sqlConnection)
        }

        return Successful(mapOf("id" to id))
    }

    private suspend fun validateAreAddedUsersExist(addedUsers: List<String>, sqlConnection: SqlConnection) {
        if (addedUsers.isNotEmpty()) {
            val areAddedUsersExists = databaseManager.userDao.areUsernamesExists(addedUsers, sqlConnection)

            if (!areAddedUsersExists) {
                throw Error(ErrorCode.SOME_USERS_ARENT_EXISTS)
            }
        }
    }

    private suspend fun validateAddedUsersContainAdmin(
        adminPermissionGroupId: Long,
        addedUsers: List<String>,
        sqlConnection: SqlConnection
    ) {
        val admins = databaseManager.userDao.getUsernamesByPermissionGroupId(adminPermissionGroupId, -1, sqlConnection)
            .map { it.lowercase() }

        admins.forEach { admin ->
            if (addedUsers.find { it.lowercase() == admin } != null) {
                throw Error(ErrorCode.NO_PERMISSION_TO_UPDATE_ADMIN_USER_PERM_GROUP)
            }
        }
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
        sqlConnection: SqlConnection
    ) {
        val isTherePermissionGroup =
            databaseManager.permissionGroupDao.isThereByName(name, sqlConnection)

        if (isTherePermissionGroup) {
            throw Errors(mapOf("name" to true))
        }
    }

    private suspend fun validateSelfUpdating(
        addedUsers: List<String>,
        userId: Long,
        sqlConnection: SqlConnection
    ) {
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlConnection)!!.lowercase()

        if (addedUsers.any { it.lowercase() == username }) {
            throw Error(ErrorCode.CANT_UPDATE_PERM_GROUP_YOURSELF)
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