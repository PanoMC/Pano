package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import com.panomc.platform.model.Error
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.ui.ThemeCompatibility
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.kotlin.coroutines.coAwait

/**
 * `PUT /panel/theme/home` with `{ "homePage": "store" | "custom:/rules" | null }` -- writes the home page
 * of the active theme (doc 01 section 9). `null` clears it, so the theme's own default applies. A value
 * that is not in the list of `GET /panel/theme/home` answers 400 `INVALID_HOME_PAGE`.
 *
 * The pick lives in its own system property, `home_page`, keyed by theme id. It is not part of
 * `theme_settings`, whose save replaces the whole object, so saving or resetting the theme settings
 * never touches it.
 */
@Endpoint
class PanelUpdateThemeHomeAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val themeCompatibility: ThemeCompatibility
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/theme/home", RouteType.PUT))

    companion object {
        const val HOME_PAGE = "home_page"
    }

    /** 400 with code `INVALID_HOME_PAGE`: the value is not one of the theme's home page options. */
    class InvalidHomePage(statusMessage: String = "") : Error("INVALID_HOME_PAGE", 400, statusMessage)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema()))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val themeId = uiManager.activeTheme

        if (themeId.isBlank()) {
            throw NotFound()
        }

        val body = getParameters(context).body().jsonObject

        if (!body.containsKey("homePage")) {
            throw InvalidHomePage("homePage is required (null clears it)")
        }

        val value = when (val raw = body.getValue("homePage")) {
            null -> null
            is String -> raw
            else -> throw InvalidHomePage("homePage must be a string or null")
        }

        if (value != null) {
            val options = context.vertx().executeBlocking<ThemeCompatibility.HomeOptions> {
                themeCompatibility.homeOptions()
            }.coAwait()

            if (!options.accepts(value)) {
                throw InvalidHomePage("'$value' is not a home page option of this theme")
            }
        }

        val sqlClient = getSqlClient()
        val property = databaseManager.systemPropertyDao.getByOption(HOME_PAGE, sqlClient)
        val updated = ThemeCompatibility.withHomeValue(property?.value, themeId, value)

        if (property == null) {
            if (!updated.isEmpty) {
                databaseManager.systemPropertyDao.add(
                    SystemProperty(option = HOME_PAGE, value = updated.encode()), sqlClient
                )
            }
        } else {
            databaseManager.systemPropertyDao.update(HOME_PAGE, updated.encode(), sqlClient)
        }

        return Successful(mapOf("value" to value))
    }
}
