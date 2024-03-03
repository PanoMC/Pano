package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantChangeEmailWait15Minutes
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.InvalidEmail
import com.panomc.platform.error.NewEmailExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.mails.ChangeEmailMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
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
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val currentPassword = data.getString("currentPassword")
        val newEmail = data.getString("newEmail")

        if (!newEmail.matches(Regex(Regexes.EMAIL))) {
            throw InvalidEmail()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val lastToken =
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), TokenType.CHANGE_EMAIL, sqlClient)

        if (lastToken != null) {
            val fifteenMinutesLaterInMillis = lastToken.startDate + 15 * 60 * 1000

            if (System.currentTimeMillis() < fifteenMinutesLaterInMillis) {
                throw CantChangeEmailWait15Minutes()
            }
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, DigestUtils.md5Hex(currentPassword), sqlClient)

        if (!isCurrentPasswordCorrect) {
            throw CurrentPasswordNotCorrect()
        }

        val emailExists = databaseManager.userDao.isEmailExists(newEmail, sqlClient)

        if (emailExists) {
            throw NewEmailExists()
        }

        databaseManager.userDao.updatePendingEmailById(userId, newEmail, sqlClient)

        mailManager.sendMail(sqlClient, userId, ChangeEmailMail(), newEmail)

        return Successful()
    }
}