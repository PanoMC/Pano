package com.panomc.platform.route.api.auth

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.notification.PasswordUpdatedMail
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.MailUtil
import com.panomc.platform.util.TokenProvider
import com.panomc.platform.util.TokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class RenewPasswordAPI(
    private val mailUtil: MailUtil,
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
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val token = data.getString("token")
        val newPassword = data.getString("newPassword")
        val newPasswordRepeat = data.getString("newPasswordRepeat")

        validateInput(token, newPassword, newPasswordRepeat)

        val sqlConnection = createConnection(databaseManager, context)

        val isTokenValid = tokenProvider.isTokenValid(token, TokenType.RESET_PASSWORD, sqlConnection)

        if (!isTokenValid) {
            throw Error(ErrorCode.INVALID_LINK)
        }

        val userId = authProvider.getUserIdFromToken(token)

        databaseManager.userDao.setPasswordById(userId, newPassword, sqlConnection)

        tokenProvider.invalidateToken(token, sqlConnection)

        mailUtil.sendMail(sqlConnection, userId, PasswordUpdatedMail())

        return Successful()
    }

    private fun validateInput(token: String, newPassword: String, newPasswordRepeat: String) {
        if (token.isBlank()) {
            throw Error(ErrorCode.INVALID_LINK)
        }

        if (newPassword.isBlank()) {
            throw Error(ErrorCode.NEW_PASSWORD_EMPTY)
        }

        if (newPassword.length < 6) {
            throw Error(ErrorCode.NEW_PASSWORD_TOO_SHORT)
        }

        if (newPassword.length > 128) {
            throw Error(ErrorCode.NEW_PASSWORD_TOO_LONG)
        }

        if (newPassword != newPasswordRepeat) {
            throw Error(ErrorCode.NEW_PASSWORD_REPEAT_DOESNT_MATCH)
        }
    }
}