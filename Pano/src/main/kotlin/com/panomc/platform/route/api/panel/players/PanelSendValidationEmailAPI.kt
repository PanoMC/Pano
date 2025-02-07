package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.auth.panel.log.SentManualValidationEmailLog
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.EmailAlreadyVerified
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ActivationMail
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelSendValidationEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/verificationMail", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_PLAYERS, context)

        val parameters = getParameters(context)

        val player = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsername(player, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val playerId =
            databaseManager.userDao.getUserIdFromUsername(player, sqlClient) ?: throw NotExists()

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(playerId, sqlClient)!!

        val userPermissionGroup =
            databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlClient)!!

        val isAdmin = context.get<Boolean>("isAdmin") ?: false

        if (userPermissionGroup.name == "admin" && !isAdmin) {
            throw NoPermission()
        }

        val isEmailVerified = databaseManager.userDao.isEmailVerifiedById(playerId, sqlClient)

        if (isEmailVerified) {
            throw EmailAlreadyVerified()
        }

        val email = databaseManager.userDao.getEmailFromUserId(playerId, sqlClient)!!

        mailManager.sendMail(sqlClient, playerId, ActivationMail(), email)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(SentManualValidationEmailLog(playerId, username, email), sqlClient)

        return Successful()
    }
}