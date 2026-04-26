package com.panomc.platform.route.api.profile

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantChangeEmailWait15Minutes
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.InvalidEmail
import com.panomc.platform.error.NewEmailExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ChangeEmailMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ChangeEmailTokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class ChangeEmailAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val authProvider: AuthProvider,
    private val tokenProvider: TokenProvider
) : LoggedInApi() {
    override val paths = listOf(Path("/api/profile/changeEmail", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("currentPassword", stringSchema())
                        .requiredProperty("newEmail", stringSchema())
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
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), ChangeEmailTokenType, sqlClient)

        if (lastToken != null) {
            val fifteenMinutesLaterInMillis = lastToken.startDate + 15 * 60 * 1000

            if (System.currentTimeMillis() < fifteenMinutesLaterInMillis) {
                throw CantChangeEmailWait15Minutes()
            }
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, currentPassword, sqlClient)

        if (!isCurrentPasswordCorrect) {
            throw CurrentPasswordNotCorrect()
        }

        val emailExists = databaseManager.userDao.isEmailExists(newEmail, sqlClient)

        if (emailExists) {
            throw NewEmailExists()
        }

        databaseManager.userDao.updatePendingEmailById(userId, newEmail, sqlClient)

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ChangeEmailTokenType, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), ChangeEmailTokenType)

        tokenProvider.saveToken(token, userId.toString(), ChangeEmailTokenType, expireDate, sqlClient)

        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        mailManager.sendMail(sqlClient, userId, ChangeEmailMail(token, user.username, newEmail), newEmail)

        return Successful()
    }
}