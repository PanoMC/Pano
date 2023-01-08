package com.panomc.platform.route.api.profile

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ChangeEmailMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import org.apache.commons.codec.digest.DigestUtils

@Endpoint
class ChangeEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/changeEmail", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                json(
                    objectSchema()
                        .property("currentPassword", stringSchema())
                        .property("newEmail", stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val currentPassword = data.getString("currentPassword")
        val newEmail = data.getString("newEmail")

        if (!newEmail.matches(Regex(Regexes.EMAIL))) {
            throw Error(ErrorCode.INVALID_EMAIL)
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlConnection = createConnection(context)

        val lastToken =
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), TokenType.CHANGE_EMAIL, sqlConnection)

        if (lastToken != null) {
            val fifteenMinutesLaterInMillis = lastToken.startDate + 15 * 60 * 1000

            if (System.currentTimeMillis() < fifteenMinutesLaterInMillis) {
                throw Error(ErrorCode.CANT_CHANGE_EMAIL_WAIT_15_MINUTES)
            }
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, DigestUtils.md5Hex(currentPassword), sqlConnection)

        if (!isCurrentPasswordCorrect) {
            throw Error(ErrorCode.CURRENT_PASSWORD_NOT_CORRECT)
        }

        val emailExists = databaseManager.userDao.isEmailExists(newEmail, sqlConnection)

        if (emailExists) {
            throw Error(ErrorCode.NEW_EMAIL_EXISTS)
        }

        databaseManager.userDao.updatePendingEmailById(userId, newEmail, sqlConnection)

        mailManager.sendMail(sqlConnection, userId, ChangeEmailMail(), newEmail)

        return Successful()
    }
}