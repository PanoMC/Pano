package com.panomc.platform.route.api.auth

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Api
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.util.RegisterUtil
import de.triology.recaptchav2java.ReCaptcha
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class RegisterAPI(
    private val reCaptcha: ReCaptcha,
    private val databaseManager: DatabaseManager
) : Api() {
    override val routeType = RouteType.POST

    override val routes = arrayListOf("/api/auth/register")

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("username", Schemas.stringSchema())
                        .property("email", Schemas.stringSchema())
                        .property("password", Schemas.stringSchema())
                        .property("passwordRepeat", Schemas.stringSchema())
                        .property("agreement", Schemas.booleanSchema())
                        .property("recaptcha", Schemas.stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
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

        val sqlConnection = createConnection(databaseManager, context)

        RegisterUtil.register(
            databaseManager,
            sqlConnection,
            username,
            email,
            password,
            remoteIP,
            isAdmin = false,
            isSetup = false
        )

        return Successful()
    }
}