package com.panomc.platform.route.api.auth

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.CSRFTokenGenerator
import io.vertx.core.http.Cookie
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class LoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/api/auth/login", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("usernameOrEmail", Schemas.stringSchema())
                        .property("password", Schemas.stringSchema())
                        .property("rememberMe", Schemas.booleanSchema())
                        .property("recaptcha", Schemas.stringSchema())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")
        val password = data.getString("password")
        val rememberMe = data.getBoolean("rememberMe")
        val recaptcha = data.getString("recaptcha")

        authProvider.validateInput(usernameOrEmail, password, recaptcha)

        val sqlConnection = createConnection(context)

        authProvider.authenticate(usernameOrEmail, password, sqlConnection)

        val token = authProvider.login(usernameOrEmail, sqlConnection)

        val userId = databaseManager.userDao.getUserIdFromUsernameOrEmail(usernameOrEmail, sqlConnection)!!

        databaseManager.userDao.updateLastLoginDate(userId, sqlConnection)

        val csrfToken = CSRFTokenGenerator.nextToken()

        val response = context.response()

        val jwtCookie = Cookie.cookie(AppConstants.COOKIE_PREFIX + AppConstants.JWT_COOKIE_NAME, token)
        val csrfTokenCookie = Cookie.cookie(AppConstants.COOKIE_PREFIX + AppConstants.CSRF_TOKEN_COOKIE_NAME, csrfToken)

        jwtCookie.path = "/"
        jwtCookie.isHttpOnly = true

        csrfTokenCookie.path = "/"
        csrfTokenCookie.isHttpOnly = true

        response.addCookie(jwtCookie)
        response.addCookie(csrfTokenCookie)

        return Successful(
            mapOf(
                "CSRFToken" to csrfToken
            )
        )
    }
}