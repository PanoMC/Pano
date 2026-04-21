package com.panomc.platform.route.api.auth


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantResetPasswordWait5Minutes
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ResetPasswordMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.Regexes
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class ResetPasswordAPI(
    private val mailManager: MailManager,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) : Api() {
    override val paths = listOf(Path("/api/auth/resetPassword", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("usernameOrEmail", Schemas.stringSchema())
//                TODO: Add recaptcha
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")

        validateInput(usernameOrEmail)

        val sqlClient = getSqlClient()

        val exists = databaseManager.userDao.existsByUsernameOrEmail(usernameOrEmail, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient) ?: throw NotExists()

        val lastToken =
            databaseManager.tokenDao.getLastBySubjectAndType(userId.toString(), TokenType.RESET_PASSWORD, sqlClient)

        if (lastToken != null) {
            val cooldownEndMillis = lastToken.startDate + 5 * 60 * 1000

            if (System.currentTimeMillis() < cooldownEndMillis) {
                throw CantResetPasswordWait5Minutes()
            }
        }

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.RESET_PASSWORD, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.RESET_PASSWORD)

        tokenProvider.saveToken(
            token,
            userId.toString(),
            TokenType.RESET_PASSWORD,
            expireDate,
            sqlClient
        )

        mailManager.sendMail(sqlClient, userId, ResetPasswordMail(token, ""))

        return Successful()
    }

    private fun validateInput(usernameOrEmail: String) {
        if (usernameOrEmail.isBlank()) {
            throw NotExists()
        }

        if (!usernameOrEmail.matches(Regex(Regexes.USERNAME)) && !usernameOrEmail.matches(Regex(Regexes.EMAIL))) {
            throw NotExists()
        }
    }
}