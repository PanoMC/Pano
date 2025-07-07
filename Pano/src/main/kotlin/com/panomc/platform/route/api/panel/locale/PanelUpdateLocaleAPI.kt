package com.panomc.platform.route.api.panel.locale


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdateLocaleLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Locale
import com.panomc.platform.error.*
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelUpdateLocaleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales/:id", RouteType.PUT))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("code", stringSchema())
                        .requiredProperty("name", stringSchema())
                        .requiredProperty("dateFnsCode", stringSchema())
                        .requiredProperty("derivatives", arraySchema().items(stringSchema()))
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long

        val code = data.getString("code")
        val name = data.getString("name")
        val dateFnsCode = data.getString("dateFnsCode")
        val derivatives = data.getJsonArray("derivatives").map { it.toString() }

        validateInput(code, name, dateFnsCode, derivatives)

        val sqlClient = getSqlClient()

        val locale = databaseManager.localeDao.byId(id, sqlClient) ?: throw NotExists()

        if (locale.definedBy == Locale.Companion.DefinedBy.SYSTEM) {
            throw NoPermission()
        }

        val oldCode = "" + locale.code
        locale.code = code
        locale.name = name
        locale.dateFnsCode = dateFnsCode
        locale.derivatives = derivatives

        databaseManager.localeDao.update(locale, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdateLocaleLog(
                userId,
                username,
                id,
                name
            ), sqlClient
        )

        val config = configManager.config
        val currentLocaleCode = config.locale

        if (currentLocaleCode == oldCode) {
            config.locale = code

            configManager.saveConfig()
        }

        return Successful()
    }

    private fun validateInput(code: String, name: String, dateFnsCode: String, derivatives: List<String>) {
        if (TextUtil.isValidLanguageTag(code)) {
            throw InvalidLocaleCode()
        }

        if (name.isBlank() || name.length < 2 || name.length > 40) {
            throw InvalidLocaleName()
        }

        if (TextUtil.isValidLanguageTag(dateFnsCode)) {
            throw InvalidDateFnsCode()
        }

        derivatives.forEach {
            if (TextUtil.isValidLanguageTag(it)) {
                throw InvalidLocaleDerivative()
            }
        }
    }
}