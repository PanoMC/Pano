package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.auth.panel.log.UpdatedPlayerPermissionGroupLog
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
import io.vertx.sqlclient.SqlClient

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
                        .requiredProperty("permissionGroup", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PERMISSION_GROUPS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val player = parameters.pathParameter("username").string
        val permissionGroup = data.getString("permissionGroup")

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(player, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val playerId = databaseManager.userDao.getUserIdFromUsername(player, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        if (playerId == userId) {
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
            databaseManager.userDao.getPermissionGroupIdFromUsername(player, sqlClient)!!

        if (userPermissionGroupId == -1L) {
            databaseManager.userDao.setPermissionGroupByUsername(
                permissionGroupId,
                player,
                sqlClient
            )

            addPanelActivityLog(userId, username, player, permissionGroup, sqlClient)

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
            player,
            sqlClient
        )

        addPanelActivityLog(userId, username, player, permissionGroup, sqlClient)

        return Successful()
    }

    private suspend fun addPanelActivityLog(
        userId: Long,
        username: String,
        player: String,
        permissionGroupName: String,
        sqlClient: SqlClient
    ) {
        databaseManager.panelActivityLogDao.add(
            UpdatedPlayerPermissionGroupLog(
                userId,
                username,
                player,
                permissionGroupName
            ), sqlClient
        )
    }
}