package com.panomc.platform.route.api.panel.player

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.apache.commons.codec.digest.DigestUtils

@Endpoint
class PanelDeletePlayerAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/delete", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", stringSchema()))
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("currentPassword", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLAYERS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = parameters.pathParameter("username").string
        val currentPassword = data.getString("currentPassword")

        val sqlConnection = createConnection(context)

        val userId =
            databaseManager.userDao.getUserIdFromUsername(username, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)
        val authUserId = authProvider.getUserIdFromRoutingContext(context)

        if (userId == authUserId) {
            throw Error(ErrorCode.CANT_DELETE_YOURSELF)
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, DigestUtils.md5Hex(currentPassword), sqlConnection)

        if (!isCurrentPasswordCorrect) {
            throw Error(ErrorCode.CURRENT_PASSWORD_NOT_CORRECT)
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(userId, sqlConnection)!!

        if (userPermissionGroupId != -1L) {
            val userPermissionGroup =
                databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlConnection)
                    ?: throw Error(ErrorCode.UNKNOWN)

            if (userPermissionGroup.name == "admin") {
                val isAdmin = context.get<Boolean>("isAdmin") ?: false

                if (!isAdmin) {
                    throw Error(ErrorCode.NO_PERMISSION)
                }

                val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                    userPermissionGroupId,
                    sqlConnection
                )

                if (count == 1L) {
                    throw Error(ErrorCode.LAST_ADMIN)
                }
            }
        }

        databaseManager.notificationDao.deleteAllByUserId(userId, sqlConnection)
        databaseManager.panelConfigDao.deleteByUserId(userId, sqlConnection)
        databaseManager.panelNotificationDao.deleteAllByUserId(userId, sqlConnection)
        databaseManager.postDao.updateUserIdByUserId(userId, -1, sqlConnection)

        val tickets = databaseManager.ticketDao.getByUserId(userId, sqlConnection)

        val ticketIdList = JsonArray(tickets.map { it.id })

        if (ticketIdList.size() != 0) {
            databaseManager.ticketMessageDao.deleteByTicketIdList(ticketIdList, sqlConnection)
        }

        databaseManager.ticketMessageDao.updateUserIdByUserId(userId, -1, sqlConnection)

        TokenType.values().forEach { tokenType ->
            tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), tokenType, sqlConnection)
        }

        databaseManager.userDao.deleteById(userId, sqlConnection)

        return Successful()
    }
}