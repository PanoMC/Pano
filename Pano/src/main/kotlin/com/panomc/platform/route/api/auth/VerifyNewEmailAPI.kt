package com.panomc.platform.route.api.auth

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidLink
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.ChangeEmailTokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class VerifyNewEmailAPI(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider,
    private val authProvider: AuthProvider
) : Api() {
    override val paths = listOf(Path("/api/auth/verifyNewEmail", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("token", Schemas.stringSchema())
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

        val token = data.getString("token")

        validateInput(token)

        val isValid = tokenProvider.isTokenValid(token, ChangeEmailTokenType, sqlClient)

        if (!isValid) {
            throw InvalidLink()
        }

        val userId = authProvider.getUserIdFromToken(token)

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), ChangeEmailTokenType, sqlClient)

        val pendingEmail = databaseManager.userDao.getPendingEmailById(userId, sqlClient)

        val emailExists = databaseManager.userDao.isEmailExists(pendingEmail, sqlClient)

        if (emailExists) {
            throw InvalidLink()
        }

        databaseManager.userDao.setEmailById(userId, pendingEmail, sqlClient)

        databaseManager.userDao.updatePendingEmailById(userId, "", sqlClient)

        return Successful()
    }

    private fun validateInput(token: String) {
        if (token.isBlank()) {
            throw InvalidLink()
        }
    }
}