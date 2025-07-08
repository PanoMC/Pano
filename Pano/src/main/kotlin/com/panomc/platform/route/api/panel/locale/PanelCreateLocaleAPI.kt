package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.NewLocaleLog
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Locale
import com.panomc.platform.error.InvalidDateFnsCode
import com.panomc.platform.error.InvalidLocaleCode
import com.panomc.platform.error.InvalidLocaleDerivative
import com.panomc.platform.error.InvalidLocaleName
import com.panomc.platform.model.*
import com.panomc.platform.util.TextUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelCreateLocaleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
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
        authProvider.requirePermission(ManageTranslations(), context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val code = data.getString("code")
        val name = data.getString("name")
        val dateFnsCode = data.getString("dateFnsCode")
        val derivatives = data.getJsonArray("derivatives").map { it.toString() }

        validateInput(code, name, dateFnsCode, derivatives)

        val sqlClient = getSqlClient()

        val locale = Locale(
            code = code,
            name = name,
            dateFnsCode = dateFnsCode,
            derivatives = derivatives,
            definedBy = Locale.Companion.DefinedBy.USER
        )

        val id = databaseManager.localeDao.add(locale, sqlClient)
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            NewLocaleLog(
                userId,
                username,
                id,
                name
            ), sqlClient
        )

        return Successful(
            mapOf(
                "id" to id
            )
        )
    }

    private fun validateInput(code: String, name: String, dateFnsCode: String, derivatives: List<String>) {
        if (!TextUtil.isValidLanguageTag(code)) {
            throw InvalidLocaleCode()
        }

        if (name.isBlank() || name.length < 2 || name.length > 40) {
            throw InvalidLocaleName()
        }

        if (!TextUtil.isValidLanguageTag(dateFnsCode)) {
            throw InvalidDateFnsCode()
        }

        derivatives.forEach {
            if (!TextUtil.isValidLanguageTag(it)) {
                throw InvalidLocaleDerivative()
            }
        }
    }
}