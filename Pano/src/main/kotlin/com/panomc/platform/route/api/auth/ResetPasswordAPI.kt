package com.panomc.platform.route.api.auth


import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CantResetPasswordWait5Minutes
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ResetPasswordMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ResetPasswordTokenType
import com.panomc.platform.util.Regexes
import com.panomc.platform.util.TextUtil
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
                        .optionalProperty("captchaToken", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()
        val authListeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()
        for (listener in authListeners) {
            val decision = listener.onBeforeAuthenticate(context, sqlClient)
            if (decision != null) {
                when (decision) {
                    is AuthEventListener.LoginDecision.Deny -> {
                        throw PluginDeniedLogin(decision.errorKey, decision.extras)
                    }
                    else -> { /* proceed */ }
                }
            }
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = TextUtil.stripWhitespace(data.getString("usernameOrEmail", ""))

        validateInput(usernameOrEmail)

        val exists = databaseManager.userDao.existsByUsernameOrEmail(usernameOrEmail, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val userId =
            databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient) ?: throw NotExists()

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

    private fun validateInput(usernameOrEmail: String) {
        if (usernameOrEmail.isBlank()) {
            throw NotExists()
        }

        if (!usernameOrEmail.matches(Regex(Regexes.USERNAME)) && !usernameOrEmail.matches(Regex(Regexes.EMAIL))) {
            throw NotExists()
        }
    }
}