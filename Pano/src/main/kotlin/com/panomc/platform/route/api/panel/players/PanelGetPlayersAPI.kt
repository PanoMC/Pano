package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.error.NotExists
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.PlayerStatus
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import kotlin.math.ceil

@Endpoint
class PanelGetPlayersAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                optionalParam(
                    "status",
                    arraySchema()
                        .items(enumSchema(*PlayerStatus.entries.map { it.name }.toTypedArray()))
                )
            )
            .queryParameter(optionalParam("permissionGroup", stringSchema()))
            .queryParameter(optionalParam("page", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

        val parameters = getParameters(context)

        val playerStatus = PlayerStatus.valueOf(
            parameters.queryParameter("status")?.jsonArray?.first() as String? ?: PlayerStatus.ALL.name
        )

        val page = parameters.queryParameter("page")?.long ?: 1L
        val permissionGroupName = parameters.queryParameter("permissionGroup")?.string

        val sqlClient = databaseManager.getSqlClient()

        var permissionGroup: PermissionGroup? = null

        if (permissionGroupName != null && permissionGroupName != "-") {
            val isTherePermission =
                databaseManager.permissionGroupDao.isThereByName(permissionGroupName, sqlClient)

            if (!isTherePermission) {
                throw NotExists()
            }

            val permissionGroupId =
                databaseManager.permissionGroupDao.getPermissionGroupIdByName(permissionGroupName, sqlClient)!!

            permissionGroup = PermissionGroup(permissionGroupId, permissionGroupName)
        }

        if (permissionGroupName != null && permissionGroupName == "-") {
            permissionGroup = PermissionGroup(name = "-")
        }

        val count =
            if (permissionGroup != null)
                databaseManager.userDao.getCountOfUsersByPermissionGroupId(permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.countByStatus(playerStatus, sqlClient)

        var totalPage = ceil(count.toDouble() / 10).toLong()

        if (totalPage < 1)
            totalPage = 1

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val userList =
            if (permissionGroup != null)
                databaseManager.userDao.getAllByPageAndPermissionGroup(page, permissionGroup.id, sqlClient)
            else
                databaseManager.userDao.getAllByPageAndStatus(page, playerStatus, sqlClient)


        val result = mutableMapOf<String, Any?>(
            "playerCount" to count,
            "totalPage" to totalPage
        )

        if (permissionGroup != null) {
            result["permissionGroup"] = permissionGroup
        }

        if (userList.isEmpty()) {
            return Successful(result)
        }

        val permissionGroupIdList = userList.map { it.permissionGroupId }
        val userIdList = userList.map { it.id }
        val usernameList = userList.map { it.username }
        val permissions = databaseManager.permissionGroupDao.byListOfId(permissionGroupIdList, sqlClient)
        val userIdTicketCountMap = databaseManager.ticketDao.countByUserIdList(userIdList, sqlClient)
        val usernameInGameMap = databaseManager.serverPlayerDao.existsByUsernameList(usernameList, sqlClient)

        result["players"] = userList.map {
            val user = JsonObject.mapFrom(it)

            user.put("isBanned", BanUtil.isBannedByUntil(it))
            user.put("inGame", usernameInGameMap[it.username])
            user.put("permissionGroup", permissions[it.permissionGroupId]?.name ?: "-")
            user.put("ticketCount", userIdTicketCountMap[it.id])
            user.put("isEmailVerified", it.emailVerified)

            user.remove("password")
            user.remove("banned")

            user
        }

        return Successful(result)
    }
}