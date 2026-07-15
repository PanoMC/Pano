package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidToken
import com.panomc.platform.error.LoginUserIsBanned
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.CSRFTokenGenerator
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

/**
 * Completes a pending auth session. The session was minted by an entry adapter (social login,
 * magic link, …) that has already proven the user's identity through its own channel; this
 * endpoint runs the standard `onBeforeLogin` hooks so cross-cutting plugins (2FA, …) apply
 * uniformly, then issues a real session.
 *
 * Body: `{pendingToken, …}`. Any extra fields are passed through the routing context body so
 * AuthEventListener implementations can read their own challenge fields (e.g. `totpCode`) exactly
 * as they would in the core `/api/auth/login` flow.
 *
 * On a hook deny the token is kept alive so the user can retry the challenge (e.g. mistyped TOTP).
 * Only a successful completion or an explicit timeout removes the token.
 *
 * Note: `onBeforeAuthenticate` is intentionally NOT run here. That hook's semantics are
 * "pre-credential", and the pending-session contract is "credentials already verified".
 */
@Endpoint
class CompletePendingAuthAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/api/auth/complete-pending", RouteType.POST))

    override fun isAllowedInDemo(method: HttpMethod) = true

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("pendingToken", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val pendingToken = data.getString("pendingToken")

        val sqlClient = getSqlClient()

        val session = authProvider.peekPendingSession(pendingToken, sqlClient) ?: throw InvalidToken()
        val user = databaseManager.userDao.getById(session.userId, sqlClient) ?: throw InvalidToken()

        if (BanUtil.isBanned(user)) {
            authProvider.consumePendingSession(pendingToken, sqlClient)
            throw LoginUserIsBanned(extras = mapOf("reason" to user.banMessage, "until" to user.bannedUntil))
        }

        // Step-up challenges (2FA, …) — keep the pending token alive on Deny so the user can retry.
        val decision = authProvider.runOnBeforeLogin(user, context, sqlClient)
        if (decision != null) {
            when (decision) {
                is AuthEventListener.LoginDecision.Deny ->
                    throw PluginDeniedLogin(decision.errorKey, decision.extras)
                is AuthEventListener.LoginDecision.RequireUsername -> {
                    authProvider.consumePendingSession(pendingToken, sqlClient)
                    throw com.panomc.platform.error.UsernameRequired(extras = mapOf("userId" to decision.userId))
                }
                is AuthEventListener.LoginDecision.Allow -> { /* proceed */ }
            }
        }

        authProvider.consumePendingSession(pendingToken, sqlClient)

        val authToken = authProvider.login(user.username, context, sqlClient)
        databaseManager.userDao.updateLastLoginDate(user.id, sqlClient)

        val csrfToken = CSRFTokenGenerator.nextToken()
        authProvider.setCookies(context, authToken, csrfToken)

        val updatedUser = databaseManager.userDao.getById(user.id, sqlClient)!!
        authProvider.runOnAfterLogin(updatedUser, context, sqlClient)

        return Successful(
            mapOf(
                "csrfToken" to csrfToken
            )
        )
    }
}
