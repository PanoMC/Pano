package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.DeleteLocaleLog
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Locale
import com.panomc.platform.error.NoPermission
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PanelDeleteLocaleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/locales/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("id", Schemas.numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTranslations(), context)

        val parameters = getParameters(context)

        val id = parameters.pathParameter("id").long

        val sqlClient = getSqlClient()

        val locale = databaseManager.localeDao.byId(id, sqlClient) ?: return NotFound()

        if (locale.definedBy == Locale.Companion.DefinedBy.SYSTEM) {
            return NoPermission()
        }

        databaseManager.translationDao.deleteByLocaleId(id, sqlClient)
        databaseManager.localeDao.deleteById(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            DeleteLocaleLog(
                userId,
                username,
                id,
                locale.name
            ), sqlClient
        )

        val config = configManager.config
        val currentLocaleCode = config.locale

        if (currentLocaleCode == locale.code) {
            config.locale = AppConstants.DEFAULT_LOCALE_CODE

            configManager.saveConfig()
        }

        databaseManager.userDao.setLocaleCodeByLocaleCode(null, locale.code, sqlClient)

        return Successful()
    }
}