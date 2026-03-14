package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.*
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.templates.ActivationMail
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.TokenType
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.PasswordHasher
import com.panomc.platform.util.Regexes
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
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")
        val password = data.getString("password")
        val registerEmail = data.getString("registerEmail")

        val sqlClient = getSqlClient()

        val checkUserId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)

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
                    val lastActivationToken = databaseManager.tokenDao.getLastBySubjectAndType(checkUserId!!.toString(), TokenType.ACTIVATION, sqlClient)

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

        val token = authProvider.login(usernameOrEmail, context, sqlClient)

        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlClient)!!

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

        return Successful(
            mapOf(
                "csrfToken" to csrfToken
            )
        )
    }

    private suspend fun sendActivationEmail(userId: Long, sqlClient: SqlClient) {
        val user = databaseManager.userDao.getById(userId, sqlClient)!!

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), TokenType.ACTIVATION, sqlClient)

        val (tokenGenerated, expireDate) = tokenProvider.generateToken(userId.toString(), TokenType.ACTIVATION)

        tokenProvider.saveToken(tokenGenerated, userId.toString(), TokenType.ACTIVATION, expireDate, sqlClient)

        mailManager.sendMail(sqlClient, userId, ActivationMail(tokenGenerated, user.username, user.email!!,""))
    }
}