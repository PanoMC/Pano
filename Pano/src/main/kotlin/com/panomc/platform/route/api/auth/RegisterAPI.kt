package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.RegisterUtil
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class RegisterAPI(
    private val reCaptcha: ReCaptcha,
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val tokenProvider: TokenProvider
) : Api() {
    override val paths = listOf(Path("/api/auth/register", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("username", Schemas.stringSchema())
                        .requiredProperty("email", Schemas.stringSchema())
                        .requiredProperty("password", Schemas.stringSchema())
                        .requiredProperty("passwordRepeat", Schemas.stringSchema())
                        .requiredProperty("agreement", Schemas.booleanSchema())
                        .requiredProperty("recaptcha", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")
        val passwordRepeat = data.getString("passwordRepeat")
        val agreement = data.getBoolean("agreement")
        val recaptchaToken = data.getString("recaptcha")

        val remoteIP = context.request().remoteAddress().host()

        RegisterUtil.validateForm(username, email, password, passwordRepeat, agreement, recaptchaToken, null)

        val sqlClient = getSqlClient()

        val userId = RegisterUtil.register(
            databaseManager,
            sqlClient,
            username,
            email,
            password,
            remoteIP,
            isAdmin = false,
            isSetup = false
        )

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.ACTIVATION, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.ACTIVATION)

        tokenProvider.saveToken(token, userId.toString(), TokenType.ACTIVATION, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, userId, ActivationMail(token))

        return Successful()
    }
}