package com.panomc.platform.route.api.auth

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.RegisterUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class RegisterAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val tokenProvider: TokenProvider,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : Api() {
    override val paths = listOf(Path("/api/auth/register", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .optionalProperty("username", Schemas.stringSchema())
                        .requiredProperty("email", Schemas.stringSchema())
                        .requiredProperty("password", Schemas.stringSchema())
                        .requiredProperty("passwordRepeat", Schemas.stringSchema())
                        .requiredProperty("agreement", Schemas.booleanSchema())
                        .optionalProperty("registerWithLinkToken", Schemas.stringSchema())
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
        val registerWithLinkToken = data.getString("registerWithLinkToken")

        val remoteIP = authProvider.getRemoteIP(context)
        val sqlClient = getSqlClient()
        
        if (!registerWithLinkToken.isNullOrEmpty()) {
             if (!tokenProvider.isTokenValid(registerWithLinkToken, TokenType.REGISTER_WITH_LINK_CODE, sqlClient)) {
                 throw InvalidToken()
             }
             
             val jwt = tokenProvider.parseToken(registerWithLinkToken)
             val userId = jwt.subject.toLong()
             
             val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw InvalidToken()
             
             // Validate inputs
             RegisterUtil.validateForm(user.username, email, password, passwordRepeat, agreement)
             
             // Update user
             databaseManager.userDao.setEmailById(userId, email, sqlClient)
             
             databaseManager.userDao.setPasswordById(userId, password, sqlClient)
             databaseManager.userDao.makeEmailVerifiedById(userId, sqlClient)
             databaseManager.userDao.setLinkCode(user.username, "", 0, sqlClient)
             
             // Invalidate token
             tokenProvider.invalidateToken(registerWithLinkToken, sqlClient)

             return Successful()
        }
        
        RegisterUtil.validateForm(username, email, password, passwordRepeat, agreement)

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

        // Fire AuthEventListener.onAfterRegister hooks
        val registeredUser = databaseManager.userDao.getById(userId, sqlClient)!!
        val authListeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()
        for (listener in authListeners) {
            listener.onAfterRegister(registeredUser, sqlClient)
        }

        val authConfig = configManager.config.auth

        if (!authConfig.requireEmailVerification) {
            val token = authProvider.login(username, context, sqlClient)

            val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(username, sqlClient)!!

            databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

            val csrfToken = CSRFTokenGenerator.nextToken()

            authProvider.setCookies(context, token, csrfToken)

            return Successful(mapOf("login" to true))
        }

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.ACTIVATION, sqlClient)

        val (tokenGenerated, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.ACTIVATION)

        tokenProvider.saveToken(tokenGenerated, userId.toString(), TokenType.ACTIVATION, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, userId, ActivationMail(tokenGenerated, username, email,""))

        return Successful()
    }
}