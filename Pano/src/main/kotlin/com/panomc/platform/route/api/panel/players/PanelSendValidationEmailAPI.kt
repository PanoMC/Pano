package com.panomc.platform.route.api.panel.players


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.SentManualValidationEmailLog
import com.panomc.platform.auth.panel.permission.ManagePlayersPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.EmailAlreadyVerified
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class PanelSendValidationEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider,
    private val tokenProvider: TokenProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/players/:username/verificationMail", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlayersPermission(), context)

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

        tokenProvider.invalidateTokensBySubjectAndType(playerId.toString(), TokenType.ACTIVATION, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(playerId.toString(), TokenType.ACTIVATION)

        tokenProvider.saveToken(token, playerId.toString(), TokenType.ACTIVATION, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, playerId, ActivationMail(token), email)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(SentManualValidationEmailLog(playerId, username, email), sqlClient)

        return Successful()
    }
}