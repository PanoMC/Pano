package com.panomc.platform.route.api.auth

import com.panomc.platform.webhook.WebhookCoreEvents
import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ActivationTokenType
import com.panomc.platform.token.RegisterWithLinkCodeTokenType
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.error.IpIsBanned
import com.panomc.platform.error.RegisterUsernameEmpty
import com.panomc.platform.error.RegisterUsernameTooShort
import com.panomc.platform.error.RegisterUsernameTooLong
import com.panomc.platform.error.RegisterInvalidUsername
import com.panomc.platform.error.RegisterUsernameNotAvailable
import com.panomc.platform.error.RegisterEmailEmpty
import com.panomc.platform.error.RegisterInvalidEmail
import com.panomc.platform.error.RegisterEmailNotAvailable
import com.panomc.platform.error.PasswordEmpty
import com.panomc.platform.error.PasswordTooShort
import com.panomc.platform.error.PasswordTooLong
import com.panomc.platform.error.RegisterPasswordAndPasswordRepeatNotSame
import com.panomc.platform.error.RegisterNotAcceptedAgreement
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class RegisterAPI(
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val tokenProvider: TokenProvider,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : Api() {
    override val paths = listOf(Path("/auth/register", RouteType.POST))

    override val doc = EndpointDoc(
        summary = "Creates an account; signs the new user in when e-mail verification is not required.",
        tag = "auth",
        response = CoreSchemas.sessionFields(
            objectSchema()
                .optionalProperty("login", booleanSchema())
                .optionalProperty("emailVerificationRequired", booleanSchema())
                .optionalProperty("email", stringSchema())
        ),
        errors = listOf(IpIsBanned::class, InvalidToken::class, PluginDeniedLogin::class, RegisterUsernameEmpty::class, RegisterUsernameTooShort::class, RegisterUsernameTooLong::class, RegisterInvalidUsername::class, RegisterUsernameNotAvailable::class, RegisterEmailEmpty::class, RegisterInvalidEmail::class, RegisterEmailNotAvailable::class, PasswordEmpty::class, PasswordTooShort::class, PasswordTooLong::class, RegisterPasswordAndPasswordRepeatNotSame::class, RegisterNotAcceptedAgreement::class)
    )

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

        val username = data.getString("username")?.let { TextUtil.stripWhitespace(it) } ?: ""
        val email = data.getString("email")?.let { TextUtil.stripWhitespace(it) } ?: ""
        val password = data.getString("password")
        val passwordRepeat = data.getString("passwordRepeat")
        val agreement = data.getBoolean("agreement")
        val registerWithLinkToken = data.getString("registerWithLinkToken")

        val remoteIP = authProvider.getRemoteIP(context)
        val sqlClient = getSqlClient()
        
        if (!registerWithLinkToken.isNullOrEmpty()) {
            if (!tokenProvider.isTokenValid(registerWithLinkToken, RegisterWithLinkCodeTokenType, sqlClient)) {
                throw InvalidToken()
            }

            val jwt = tokenProvider.parseToken(registerWithLinkToken)
            val userId = jwt.subject.toLong()

            val user = databaseManager.userDao.getById(userId, sqlClient) ?: throw InvalidToken()

            RegisterUtil.validateForm(user.username, email, password, passwordRepeat, agreement)

            val authConfigForLink = configManager.config.auth

            databaseManager.userDao.setEmailById(userId, email, sqlClient)
            databaseManager.userDao.setPasswordById(userId, password, sqlClient)
            databaseManager.userDao.setLinkCode(user.username, "", 0, sqlClient)

            if (!authConfigForLink.requireEmailVerification) {
                databaseManager.userDao.makeEmailVerifiedById(userId, sqlClient)
            }

            tokenProvider.invalidateToken(registerWithLinkToken, sqlClient)

            val registeredUser = databaseManager.userDao.getById(userId, sqlClient)!!

            val authListenersForLink = PluginEventManager.getPanoEventListeners<AuthEventListener>()
            for (listener in authListenersForLink) {
                listener.onAfterRegister(registeredUser, sqlClient)
            }

            WebhookCoreEvents.fire { userRegistered(registeredUser, sqlClient) }

            if (!authConfigForLink.requireEmailVerification) {
                val linkRegisterSession = authProvider.issueSession(registeredUser.username, context, sqlClient)

                databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

                return Successful(mapOf<String, Any?>("login" to true) + linkRegisterSession)
            }

            tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ActivationTokenType, sqlClient)

            val (linkActivationToken, linkActivationExpire) =
                tokenProvider.generateToken(userId.toString(), ActivationTokenType)
            tokenProvider.saveToken(
                linkActivationToken,
                userId.toString(),
                ActivationTokenType,
                linkActivationExpire,
                sqlClient
            )

            mailManager.sendMail(
                sqlClient,
                userId,
                ActivationMail(linkActivationToken, user.username, email, "")
            )

            return Successful(
                mapOf(
                    "emailVerificationRequired" to true,
                    "email" to email
                )
            )
        }
        
        // Fire AuthEventListener.onBeforeAuthenticate hooks (e.g., captcha check)
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
        for (listener in authListeners) {
            listener.onAfterRegister(registeredUser, sqlClient)
        }

        WebhookCoreEvents.fire { userRegistered(registeredUser, sqlClient) }

        val authConfig = configManager.config.auth

        if (!authConfig.requireEmailVerification) {
            databaseManager.userDao.makeEmailVerifiedById(userId, sqlClient)

            val session = authProvider.issueSession(username, context, sqlClient)

            val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(username, sqlClient)!!

            databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

            return Successful(mapOf<String, Any?>("login" to true) + session)
        }

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ActivationTokenType, sqlClient)

        val (tokenGenerated, expireDate) = tokenProvider.generateToken(userId.toString(), ActivationTokenType)

        tokenProvider.saveToken(tokenGenerated, userId.toString(), ActivationTokenType, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, userId, ActivationMail(tokenGenerated, username, email,""))

        return Successful()
    }
}