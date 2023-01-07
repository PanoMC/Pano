package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUpdatePlayerPermissionGroupAPI(
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/permissionGroup", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("username", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .property("permissionGroup", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = parameters.pathParameter("username").string
        val permissionGroup = data.getString("permissionGroup")

        val sqlConnection = createConnection(context)

        val exists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!exists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        var permissionGroupId = -1L

        if (permissionGroup != "-") {
            val isTherePermissionGroup = databaseManager.permissionGroupDao.isThereByName(
                permissionGroup,
                sqlConnection
            )

            if (!isTherePermissionGroup) {
                throw Error(ErrorCode.NOT_EXISTS)
            }

            permissionGroupId = databaseManager.permissionGroupDao.getPermissionGroupIdByName(
                permissionGroup,
                sqlConnection
            ) ?: throw Error(ErrorCode.UNKNOWN)
        }

        val userPermissionGroupId =
            databaseManager.userDao.getPermissionGroupIdFromUsername(username, sqlConnection) ?: throw Error(
                ErrorCode.UNKNOWN
            )

        if (userPermissionGroupId == -1L) {
            databaseManager.userDao.setPermissionGroupByUsername(
                permissionGroupId,
                username,
                sqlConnection
            )

            return Successful()
        }

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlConnection)
                ?: throw Error(ErrorCode.UNKNOWN)

        if (userPermissionGroup.name == "admin") {
            val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                userPermissionGroupId,
                sqlConnection
            )

            if (count == 1L) {
                throw Errors(mapOf("LAST_ADMIN" to true))
            }
        }

        databaseManager.userDao.setPermissionGroupByUsername(
            permissionGroupId,
            username,
            sqlConnection
        )

        return Successful()
    }
}