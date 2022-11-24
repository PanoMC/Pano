package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.AuthProvider
import com.panomc.platform.util.RegisterUtil
import com.panomc.platform.util.SetupManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class FinishAPI(
    private val setupManager: SetupManager,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : SetupApi(setupManager) {
    override val paths = listOf(Path("/api/setup/finish", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandler.builder(schemaParser)
            .body(
                Bodies.json(
                    objectSchema()
                        .property("username", stringSchema())
                        .property("email", stringSchema())
                        .property("password", stringSchema())
                        .property("setupLocale", stringSchema())
                )
            )
            .build()

    override suspend fun handler(context: RoutingContext): Result {
        if (setupManager.getStep() != 4) {
            return Successful(setupManager.getCurrentStepData().map)
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")
        val setupLocale = data.getString("setupLocale")

        val remoteIP = context.request().remoteAddress().host()

        RegisterUtil.validateForm(
            username,
            email,
            password,
            password,
            true,
            "",
            null
        )

        val sqlConnection = createConnection(databaseManager, context)

        databaseManager.initDatabase(sqlConnection)

        RegisterUtil.register(
            databaseManager,
            sqlConnection,
            username,
            email,
            password,
            remoteIP,
            isAdmin = true,
            isSetup = true
        )

        val token = authProvider.login(username, sqlConnection)

        configManager.getConfig().put("locale", setupLocale)

        configManager.saveConfig()

        setupManager.finishSetup()

        return Successful(
            mapOf(
                "jwt" to token
            )
        )
    }
}