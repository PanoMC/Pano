package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantResetPasswordWait5Minutes
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ResetPasswordMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ResetPasswordTokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class SendResetPasswordEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider,
    private val tokenProvider: TokenProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/resetPassword", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val lastToken =
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), ResetPasswordTokenType, sqlClient)

        if (lastToken != null) {
            val cooldownEndMillis = lastToken.startDate + 5 * 60 * 1000

            if (System.currentTimeMillis() < cooldownEndMillis) {
                throw CantResetPasswordWait5Minutes()
            }
        }

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ResetPasswordTokenType, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), ResetPasswordTokenType)

        tokenProvider.saveToken(
            token,
            userId.toString(),
            ResetPasswordTokenType,
            expireDate,
            sqlClient
        )

        mailManager.sendMail(sqlClient, userId, ResetPasswordMail(token, ""))

        return Successful()
    }
}