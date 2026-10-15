package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.route.api.panel.theme.PanelUpdateThemeHomeAPI.Companion.HOME_PAGE
import com.panomc.platform.ui.ThemeCompatibility
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait

/**
 * `GET /panel/theme/home` -- the admin's home page pick for the active theme and the list to pick from
 * (doc 01 section 9): `{ value, default, options: [{ id, label, kind, path?, available }] }`. `value` is
 * null while the theme's own default applies.
 */
@Endpoint
class PanelGetThemeHomeAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val themeCompatibility: ThemeCompatibility
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/theme/home", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val property = databaseManager.systemPropertyDao.getByOption(HOME_PAGE, getSqlClient())
        val value = ThemeCompatibility.homeValueFor(property?.value, uiManager.activeTheme)
        val options = context.vertx().executeBlocking<ThemeCompatibility.HomeOptions> {
            themeCompatibility.homeOptions()
        }.coAwait()

        return Successful(
            mapOf(
                "value" to value,
                "default" to options.default,
                "options" to options.options.map { it.toMap() }
            )
        )
    }
}
