package com.panomc.platform.route.api.auth

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ActivationTokenType
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.PasswordHasher
import com.panomc.platform.util.Regexes
import com.panomc.platform.util.TextUtil
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.sqlclient.SqlClient

@Endpoint
class LoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val mailManager: MailManager,
    private val configManager: ConfigManager,
    private val passwordHasher: PasswordHasher
) : Api() {
    override val paths = listOf(Path("/api/auth/login", RouteType.POST))

    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("usernameOrEmail", Schemas.stringSchema())
                        .optionalProperty("password", Schemas.stringSchema())
                        .optionalProperty("registerEmail", Schemas.stringSchema())
                        .optionalProperty("newUsername", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = TextUtil.stripWhitespace(data.getString("usernameOrEmail", ""))
        val password = data.getString("password")
        val registerEmail = data.getString("registerEmail")?.let { TextUtil.stripWhitespace(it) }
        val newUsername = data.getString("newUsername")?.let { TextUtil.stripWhitespace(it) }

        val sqlClient = getSqlClient()

        val remoteIp = authProvider.getRemoteIP(context)
        val activeIpBan = databaseManager.bannedIpDao.getActiveByIp(remoteIp, sqlClient)
        if (activeIpBan != null) {
            throw IpIsBanned(
                extras = mapOf(
                    "reason" to activeIpBan.reason,
                    "bannedUntil" to activeIpBan.bannedUntil
                )
            )
        }

        val checkUserId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)

        // Run before user-specific short-circuits (e.g. link-code flow) so plugins can require captcha first.
        val authListeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()
        for (listener in authListeners) {
            val decision = listener.onBeforeAuthenticate(context, sqlClient)
            if (decision != null) {
                when (decision) {
                    is AuthEventListener.LoginDecision.Deny -> {
                        throw PluginDeniedLogin(decision.errorKey, decision.extras)
                    }
                    is AuthEventListener.LoginDecision.RequireUsername -> {
                        throw UsernameRequired(extras = mapOf("userId" to decision.userId))
                    }
                    is AuthEventListener.LoginDecision.Allow -> { /* proceed */ }
                }
            }
        }

        if (checkUserId != null) {
            val user = databaseManager.userDao.getById(checkUserId, sqlClient)

            if (user != null) {
                val hasPassword = databaseManager.userDao.hasPassword(checkUserId, sqlClient)

                if (!hasPassword && user.email.isNullOrEmpty()) {
                    throw LinkCodeRequired()
                }

                if (password == null) {
                    throw LoginIsInvalid()
                }
            }
        } else if (password == null) {
            throw LoginIsInvalid()
        }

        authProvider.validateInput(usernameOrEmail, password)

        try {
            authProvider.authenticate(
                usernameOrEmail,
                password,
                dontCheckVerified = false,
                dontCheckBanned = false,
                sqlClient = sqlClient
            )
        } catch(e: Error) {
            when (e) {
                is RegisterEmailRequired if registerEmail != null -> {}
                is LoginEmailNotVerified -> {
                    val lastActivationToken = databaseManager.tokenDao.getLastBySubjectAndType(checkUserId!!.toString(), ActivationTokenType, sqlClient)

                    if (lastActivationToken != null && lastActivationToken.expireDate > System.currentTimeMillis()) {
                        throw e
                    }

                    sendActivationEmail(checkUserId, sqlClient)

                    throw e
                }
                else -> {
                    throw e
                }
            }
        }

        val email = databaseManager.userDao.getEmailFromUserId(checkUserId!!, sqlClient)

        if (email == null) {
            if (registerEmail == null) {
                throw BadRequest()
            }

            if (!registerEmail.matches(Regex(Regexes.EMAIL))) {
                throw RegisterInvalidEmail()
            }

            val isEmailExists = databaseManager.userDao.isEmailExists(registerEmail, sqlClient)

            if (isEmailExists) {
                throw RegisterEmailNotAvailable()
            }

            databaseManager.userDao.setEmailById(checkUserId, registerEmail, sqlClient)

            sendActivationEmail(checkUserId, sqlClient)

            val authConfig = configManager.config.auth

            if (authConfig.requireEmailVerification) {
                throw LoginEmailNotVerified()
            }
        }

        val user = databaseManager.userDao.getById(checkUserId, sqlClient)!!

        // Check if username is a temp-user — require setting a new username
        if (user.username.startsWith("tmp_")) {
            if (newUsername.isNullOrEmpty()) {
                throw UsernameRequired(extras = mapOf("userId" to checkUserId))
            }

            // Validate new username
            if (newUsername.length < 3) throw RegisterUsernameTooShort()
            if (newUsername.length > 16) throw RegisterUsernameTooLong()
            if (!newUsername.matches(Regex(Regexes.USERNAME))) throw RegisterInvalidUsername()

            val isUsernameExists = databaseManager.userDao.existsByUsername(newUsername, sqlClient)
            if (isUsernameExists) throw RegisterUsernameNotAvailable()

            // Set the new username
            databaseManager.userDao.setUsernameById(checkUserId, newUsername, sqlClient)
        }

        // Fire AuthEventListener.onBeforeLogin hooks (e.g., 2FA check — after password verification)
        for (listener in authListeners) {
            val decision = listener.onBeforeLogin(user, context, sqlClient)
            if (decision != null) {
                when (decision) {
                    is AuthEventListener.LoginDecision.Deny -> {
                        throw PluginDeniedLogin(decision.errorKey, decision.extras)
                    }
                    is AuthEventListener.LoginDecision.RequireUsername -> {
                        throw UsernameRequired(extras = mapOf("userId" to decision.userId))
                    }
                    is AuthEventListener.LoginDecision.Allow -> { /* proceed */ }
                }
            }
        }

        // Use the potentially updated username for login
        val loginUsername = databaseManager.userDao.getUsernameFromUserId(checkUserId, sqlClient)!!
        val token = authProvider.login(loginUsername, context, sqlClient)

        val userId = checkUserId

        databaseManager.userDao.updateLastLoginDate(userId, sqlClient)

        // Transparent hash upgrade: if stored hash uses an old algorithm, rehash with the configured default
        val storedHash = databaseManager.userDao.getPasswordById(userId, sqlClient)
        if (storedHash != null) {
            val targetAlgorithm = PasswordHasher.Algorithm.fromString(configManager.config.auth.passwordHashAlgorithm)
            if (passwordHasher.needsRehash(storedHash, targetAlgorithm)) {
                val newHash = passwordHasher.hash(password, targetAlgorithm)
                databaseManager.userDao.setHashedPasswordById(userId, newHash, sqlClient)
            }
        }

        val csrfToken = CSRFTokenGenerator.nextToken()

        authProvider.setCookies(context, token, csrfToken)

        // Fire AuthEventListener.onAfterLogin hooks
        val updatedUser = databaseManager.userDao.getById(userId, sqlClient)!!
        for (listener in authListeners) {
            listener.onAfterLogin(updatedUser, context, sqlClient)
        }

        return Successful(
            mapOf(
                "csrfToken" to csrfToken
            )
        )
    }

    private suspend fun sendActivationEmail(userId: Long, sqlClient: SqlClient) {
        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ActivationTokenType, sqlClient)

        val (tokenGenerated, expireDate) = tokenProvider.generateToken(userId.toString(), ActivationTokenType)

        tokenProvider.saveToken(tokenGenerated, userId.toString(), ActivationTokenType, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, userId, ActivationMail(tokenGenerated, user.username, user.email!!,""))
    }
}