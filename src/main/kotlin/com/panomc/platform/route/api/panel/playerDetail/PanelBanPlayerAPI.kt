package com.panomc.platform.route.api.panel.playerDetail

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.notification.BannedMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelBanPlayerAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val tokenProvider: TokenProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/ban", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .optionalProperty("sendNotification", booleanSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = parameters.pathParameter("username").string

        val sendNotification = data.getBoolean("sendNotification") ?: false

        val sqlConnection = createConnection(context)

        val isExists = databaseManager.userDao.isExistsByUsername(username, sqlConnection)

        if (!isExists) {
            throw Error(ErrorCode.NOT_EXISTS)
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsername(username, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        if (userId == authProvider.getUserIdFromRoutingContext(context)) {
            throw Error(ErrorCode.CANT_BAN_YOURSELF)
        }

        val isBanned = databaseManager.userDao.isBanned(userId, sqlConnection)

        if (isBanned) {
            throw Error(ErrorCode.ALREADY_BANNED)
        }

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(userId, sqlConnection)
            ?: throw Error(ErrorCode.UNKNOWN)

        if (userPermissionGroupId != -1L) {
            val userPermissionGroup =
                databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlConnection)
                    ?: throw Error(ErrorCode.UNKNOWN)

            if (userPermissionGroup.name == "admin") {
                val count = databaseManager.userDao.getCountOfUsersByPermissionGroupId(
                    userPermissionGroupId,
                    sqlConnection
                )

                if (count == 1L) {
                    throw Error(ErrorCode.LAST_ADMIN)
                }
            }
        }

        databaseManager.userDao.banPlayer(userId, sqlConnection)

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.AUTHENTICATION, sqlConnection)

        if (sendNotification) {
            mailManager.sendMail(sqlConnection, userId, BannedMail())
        }

        return Successful()
    }
}