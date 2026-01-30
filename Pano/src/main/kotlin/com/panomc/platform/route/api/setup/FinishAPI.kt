package com.panomc.platform.route.api.setup

import com.panomc.platform.AppConstants
import com.panomc.platform.AppConstants.AVAILABLE_LOCALES
import com.panomc.platform.UIManager
import com.panomc.platform.UpdateManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.InstalledPlatformLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.MariaDBManager
import com.panomc.platform.model.*
import com.panomc.platform.util.CSRFTokenGenerator
import com.panomc.platform.util.RegisterUtil
import io.vertx.core.http.Cookie
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait
import org.springframework.context.annotation.Lazy

@Endpoint
class FinishAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    @Lazy private val router: Router,
    private val uiManager: UIManager,
    private val updateManager: UpdateManager,
    private val mariaDBManager: MariaDBManager
) : SetupApi() {
    override val paths = listOf(Path("/api/setup/finish", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("email", stringSchema())
                        .requiredProperty("password", stringSchema())
                        .requiredProperty(
                            "setupLocale",
                            enumSchema(*AVAILABLE_LOCALES.toTypedArray())
                        )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (setupManager.getCurrentStep() != 4) {
            return Successful(setupManager.getCurrentStepData().map)
        }

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val username = data.getString("username")
        val email = data.getString("email")
        val password = data.getString("password")
        val setupLocale = data.getString("setupLocale")

        val remoteIP = context.request().remoteAddress().host()

        if (configManager.config.database.type == "portable") {
            context.vertx().executeBlocking {
                mariaDBManager.start()
                mariaDBManager.createDefaultDatabase()
            }.coAwait()
        }

        RegisterUtil.validateForm(
            username,
            email,
            password,
            password,
            true,
            "",
            null
        )

        val sqlClient = getSqlClient()

        databaseManager.initDatabase(sqlClient)

        val userId = RegisterUtil.register(
            databaseManager,
            sqlClient,
            username,
            email,
            password,
            remoteIP,
            isAdmin = true,
            isSetup = true
        )

        val token = authProvider.login(username, sqlClient)

        configManager.config.locale = setupLocale

        configManager.saveConfig()

        databaseManager.panelActivityLogDao.add(InstalledPlatformLog(userId, username), sqlClient)

        setupManager.finishSetup()

        try {
            updateManager.checkUpdates(true)
        } catch (_: Exception) {
        } catch (_: Error) {
        }

        uiManager.prepareUI(router)

        val response = context.response()

        val csrfToken = CSRFTokenGenerator.nextToken()

        authProvider.setCookies(context, token, csrfToken)

        return Successful(
            mapOf(
                "jwt" to token
            )
        )
    }
}