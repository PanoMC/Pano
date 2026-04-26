package com.panomc.platform.route.api.auth

import com.panomc.platform.PluginEventManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.event.AuthEventListener
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PluginDeniedLogin
import com.panomc.platform.error.RegisterLinkCodeInvalid
import com.panomc.platform.model.*
import com.panomc.platform.token.TokenProvider
import com.panomc.platform.token.RegisterWithLinkCodeTokenType
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas
import java.util.*

@Endpoint
class VerifyLinkCodeAPI(
    private val databaseManager: DatabaseManager,
    private val tokenProvider: TokenProvider
) : Api() {
    override val paths = listOf(Path("/api/auth/verifyLinkCode", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .requiredProperty("username", Schemas.stringSchema())
                        .requiredProperty("code", Schemas.stringSchema())
                        .optionalProperty("captchaToken", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject
        val code = data.getString("code")
        val username = data.getString("username")

        val sqlClient = getSqlClient()

        val authListeners = PluginEventManager.getPanoEventListeners<AuthEventListener>()
        for (listener in authListeners) {
            val decision = listener.onBeforeVerifyLinkCode(context, sqlClient)
            if (decision != null) {
                when (decision) {
                    is AuthEventListener.LoginDecision.Deny -> {
                        throw PluginDeniedLogin(decision.errorKey, decision.extras)
                    }
                    else -> { }
                }
            }
        }

        val linkCodeData = databaseManager.userDao.getLinkCode(username, sqlClient) ?: throw RegisterLinkCodeInvalid()
        val (dbLinkCode, dbLinkCodeCreatedAt) = linkCodeData

        if (dbLinkCode != code) {
            throw RegisterLinkCodeInvalid()
        }

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = dbLinkCodeCreatedAt ?: 0
        calendar.add(Calendar.SECOND, 30)

        if (calendar.timeInMillis < System.currentTimeMillis()) {
            throw RegisterLinkCodeInvalid()
        }
        
        val userId = databaseManager.userDao.getUserIdFromUsername(username, sqlClient) ?: throw RegisterLinkCodeInvalid()
        
        val activeEmail = databaseManager.userDao.getEmailFromUserId(userId, sqlClient)
        val hasPassword = databaseManager.userDao.hasPassword(userId, sqlClient)

        if (!activeEmail.isNullOrEmpty() && hasPassword) {
            throw RegisterLinkCodeInvalid()
        }

        tokenProvider.invalidateTokensBySubjectAndType(userId.toString(), RegisterWithLinkCodeTokenType, sqlClient)
        databaseManager.userDao.setLinkCode(username, "", 0, sqlClient)

        val (token, expireDate) = tokenProvider.generateToken(userId.toString(), RegisterWithLinkCodeTokenType)
        tokenProvider.saveToken(token, userId.toString(), RegisterWithLinkCodeTokenType, expireDate, sqlClient)

        return Successful(
            mapOf(
                "token" to token,
                "username" to username
            )
        )
    }
}
