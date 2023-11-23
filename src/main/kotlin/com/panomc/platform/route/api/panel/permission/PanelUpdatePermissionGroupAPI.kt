package com.panomc.platform.route.api.panel.permission


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Permission
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.error.*
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.sqlclient.SqlClient

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
            .predicate(RequestPredicate.BODY_REQUIRED)
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

        val sqlClient = getSqlClient()

        validateSelfUpdating(addedUsers, removedUsers, userId, sqlClient)

        validatePermissionGroupExists(id, sqlClient)

        val adminPermissionGroupId =
            databaseManager.permissionGroupDao.getPermissionGroupIdByName("admin", sqlClient)!!

        validateHavePermissionToUpdateAdminPermGroup(id, adminPermissionGroupId, userId, sqlClient)

        validateHavePermissionToUpdateAdminUser(
            addedUsers,
            removedUsers,
            adminPermissionGroupId,
            sqlClient,
            context
        )

        val permissionGroup = databaseManager.permissionGroupDao.getPermissionGroupById(id, sqlClient)!!

        val permissionsInDb = databaseManager.permissionDao.getPermissions(sqlClient)

        validatePermissions(permissions, permissionsInDb)

        validateIsAdminPermGroup(permissions, permissionsInDb, permissionGroup, name, adminPermissionGroupId)

        validateAreAddedUsersExist(addedUsers, sqlClient)
        validateAreRemovedUsersExist(removedUsers, sqlClient)

        validateIsPermissionGroupNameExists(name, permissionGroup, sqlClient)

        permissions.forEach { permission ->
            val permissionId = permission.getLong("id")
            val isPermissionSelected = permission.getBoolean("selected")

            val permissionExists = databaseManager.permissionGroupPermsDao.doesPermissionGroupHavePermission(
                id,
                permissionId,
                sqlClient
            )

            if (isPermissionSelected && !permissionExists) {
                databaseManager.permissionGroupPermsDao.addPermission(id, permissionId, sqlClient)
            }

            if (!isPermissionSelected && permissionExists) {
                databaseManager.permissionGroupPermsDao.removePermission(id, permissionId, sqlClient)
            }
        }

        if (permissionGroup.name != name) {
            databaseManager.permissionGroupDao.update(
                PermissionGroup(id, name),
                sqlClient
            )
        }

        if (addedUsers.isNotEmpty()) {
            databaseManager.userDao.setPermissionGroupByUsernames(id, addedUsers, sqlClient)
        }

        if (removedUsers.isNotEmpty()) {
            databaseManager.userDao.setPermissionGroupByUsernames(-1, removedUsers, sqlClient)
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
            throw SomePermissionsArentExists()
        }
    }

    private suspend fun validateIsPermissionGroupNameExists(
        name: String,
        permissionGroup: PermissionGroup,
        sqlClient: SqlClient
    ) {
        if (permissionGroup.name != name) {
            val isTherePermissionGroupByName =
                databaseManager.permissionGroupDao.isThereByName(name, sqlClient)

            if (isTherePermissionGroupByName) {
                throw Errors(mapOf("name" to true))
            }
        }
    }

    private suspend fun validateAreRemovedUsersExist(removedUsers: List<String>, sqlClient: SqlClient) {
        if (removedUsers.isNotEmpty()) {
            val areRemovedUsersExists = databaseManager.userDao.areUsernamesExists(removedUsers, sqlClient)

            if (!areRemovedUsersExists) {
                throw SomeUsersArentExists()
            }
        }
    }

    private suspend fun validateAreAddedUsersExist(addedUsers: List<String>, sqlClient: SqlClient) {
        if (addedUsers.isNotEmpty()) {
            val areAddedUsersExists = databaseManager.userDao.areUsernamesExists(addedUsers, sqlClient)

            if (!areAddedUsersExists) {
                throw SomeUsersArentExists()
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
            throw CantUpdateAdminPermission()
        }
    }

    private suspend fun validateHavePermissionToUpdateAdminPermGroup(
        permissionGroupId: Long,
        adminPermissionGroupId: Long,
        userId: Long,
        sqlClient: SqlClient
    ) {
        if (permissionGroupId == adminPermissionGroupId) {
            val user = databaseManager.userDao.getById(userId, sqlClient)

            if (user!!.permissionGroupId != adminPermissionGroupId) {
                throw NoPermissionToUpdateAdminPermGroup()
            }
        }
    }

    private suspend fun validateHavePermissionToUpdateAdminUser(
        addedUsers: List<String>,
        removedUsers: List<String>,
        adminPermissionGroupId: Long,
        sqlClient: SqlClient,
        context: RoutingContext
    ) {
        val admins = databaseManager.userDao.getUsernamesByPermissionGroupId(adminPermissionGroupId, -1, sqlClient)
            .map { it.lowercase() }

        val isAdmin = context.get<Boolean>("isAdmin") ?: false

        admins.forEach { admin ->
            if ((addedUsers.find { it.lowercase() == admin } != null || removedUsers.find { it.lowercase() == admin } != null) && !isAdmin) {
                throw NoPermissionToUpdateAdminUser()
            }
        }
    }

    private suspend fun validateSelfUpdating(
        addedUsers: List<String>,
        removedUsers: List<String>,
        userId: Long,
        sqlClient: SqlClient
    ) {
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!.lowercase()

        if (addedUsers.any { it.lowercase() == username } || removedUsers.any { it.lowercase() == username }) {
            throw CantUpdatePermGroupYourself()
        }
    }

    private suspend fun validatePermissionGroupExists(permissionGroupId: Long, sqlClient: SqlClient) {
        val isTherePermissionGroupById =
            databaseManager.permissionGroupDao.isThereById(permissionGroupId, sqlClient)

        if (!isTherePermissionGroupById) {
            throw PermGroupNotExists()
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