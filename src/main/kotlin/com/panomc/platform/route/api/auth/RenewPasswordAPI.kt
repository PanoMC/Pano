package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.notification.PasswordUpdatedMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class RenewPasswordAPI(
    private val mailManager: MailManager,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/auth/renewPassword", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("token", Schemas.stringSchema())
                        .property("newPassword", Schemas.stringSchema())
                        .property("newPasswordRepeat", Schemas.stringSchema())
//                TODO: Add recaptcha
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val token = data.getString("token")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")

        validateInput(token, newPassword, newPasswordRepeat)

        val sqlClient = getSqlClient()

        val isTokenValid = tokenProvider.isTokenValid(token, TokenType.RESET_PASSWORD, sqlClient)

        if (!isTokenValid) {
            throw InvalidLink()
        }

        val userId = authProvider.getUserIdFromToken(token)

        databaseManager.userDao.setPasswordById(userId, newPassword, sqlClient)

        tokenProvider.invalidateToken(token, sqlClient)

        mailManager.sendMail(sqlClient, userId, PasswordUpdatedMail())

        return Successful()
    }

    private fun validateInput(token: String, newPassword: String, newPasswordRepeat: String) {
        if (token.isBlank()) {
            throw InvalidLink()
        }

        if (newPassword.isBlank()) {
            throw NewPasswordEmpty()
        }

        if (newPassword.length < 6) {
            throw NewPasswordTooShort()
        }

        if (newPassword.length > 128) {
            throw NewPasswordTooLong()
        }

        if (newPassword != newPasswordRepeat) {
            throw NewPasswordRepeatDoesntMatch()
        }
    }
}