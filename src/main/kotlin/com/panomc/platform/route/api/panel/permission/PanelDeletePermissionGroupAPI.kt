package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelDeletePermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/permissions/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PERMISSION_GROUPS, context)

        val parameters = getParameters(context)

        val permissionGroupId = parameters.pathParameter("id").long

        val sqlConnection = createConnection(context)

        val isTherePermissionGroup =
            databaseManager.permissionGroupDao.isThereById(permissionGroupId, sqlConnection)

        if (!isTherePermissionGroup) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val permissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(permissionGroupId, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)

        if (permissionGroup.name == "admin") {
            throw Error(ErrorCode.CANT_DELETE_ADMIN_PERMISSION)
        }

        databaseManager.permissionGroupPermsDao.removePermissionGroup(permissionGroupId, sqlConnection)

        databaseManager.userDao.removePermissionGroupByPermissionGroupId(permissionGroupId, sqlConnection)

        databaseManager.permissionGroupDao.deleteById(permissionGroupId, sqlConnection)

        return Successful()
    }
}