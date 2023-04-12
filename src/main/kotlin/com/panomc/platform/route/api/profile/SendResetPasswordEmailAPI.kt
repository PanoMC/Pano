package com.panomc.platform.route.api.profile

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ResetPasswordMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaParser

@Endpoint
class SendResetPasswordEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/resetPassword", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser) = null

    override suspend fun handle(context: RoutingContext): Result {
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val lastToken =
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), TokenType.RESET_PASSWORD, sqlClient)

        if (lastToken != null) {
            val fifteenMinutesLaterInMillis = lastToken.startDate + 15 * 60 * 1000

            if (System.currentTimeMillis() < fifteenMinutesLaterInMillis) {
                throw Error(ErrorCode.CANT_RESET_PASSWORD_WAIT_15_MINUTES)
            }
        }

        mailManager.sendMail(sqlClient, userId, ResetPasswordMail())

        return Successful()
    }
}