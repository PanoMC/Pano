package com.panomc.platform.route.api.panel.player


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantUpdatePermGroupYourself
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelUpdatePlayerPermissionGroupAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
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
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PERMISSION_GROUPS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = parameters.pathParameter("username").string
        val permissionGroup = data.getString("permissionGroup")

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(username, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient)

        val authUserId = authProvider.getUserIdFromRoutingContext(context)

        if (userId == authUserId) {
            throw CantUpdatePermGroupYourself()
        }

        var permissionGroupId = -1L

        if (permissionGroup != "-") {
            val isTherePermissionGroup = databaseManager.permissionGroupDao.isThereByName(
                permissionGroup,
                sqlClient
            )

            if (!isTherePermissionGroup) {
                throw NotExists()
            }

            permissionGroupId = databaseManager.permissionGroupDao.getPermissionGroupIdByName(
                permissionGroup,
                sqlClient
            )!!
        }

        val userPermissionGroupId =
            databaseManager.userDao.getPermissionGroupIdFromUsername(username, sqlClient)!!

        if (userPermissionGroupId == -1L) {
            databaseManager.userDao.setPermissionGroupByUsername(
                permissionGroupId,
                username,
                sqlClient
            )

            return Successful()
        }

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

        if (userPermissionGroup.name == "admin") {
            val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                userPermissionGroupId,
                sqlClient
            )

            val isAdmin = context.get<Boolean>("isAdmin") ?: false

            if (!isAdmin) {
                throw NoPermission()
            }

            if (count == 1L) {
                throw Errors(mapOf("LAST_ADMIN" to true))
            }
        }

        databaseManager.userDao.setPermissionGroupByUsername(
            permissionGroupId,
            username,
            sqlClient
        )

        return Successful()
    }
}