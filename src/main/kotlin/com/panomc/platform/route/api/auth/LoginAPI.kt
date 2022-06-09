package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.AuthProvider
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class LoginAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/login")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
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

    override suspend fun handler(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val usernameOrEmail = data.getString("usernameOrEmail")
        val password = data.getString("password")
        val rememberMe = data.getBoolean("rememberMe")
        val recaptcha = data.getString("recaptcha")

        authProvider.validateInput(usernameOrEmail, password, recaptcha)

        val sqlConnection = createConnection(databaseManager, context)

        authProvider.authenticate(usernameOrEmail, password, sqlConnection)

        val token = authProvider.login(usernameOrEmail, sqlConnection)

        return Successful(
            mapOf(
                "jwt" to token
            )
        )
    }
}