package com.panomc.platform.route.api.panel.locale

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageTranslations
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Locale
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

@Endpoint
class PanelGetLocalesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/locales", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        Paging.params(ValidationHandlerBuilder.create(schemaRepository)).build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageTranslations(), context)

        val page = Paging.request(context)

        val sqlClient = getSqlClient()

        val count = databaseManager.localeDao.count(sqlClient)

        Paging.requireInRange(page, count)

        val locales = databaseManager.localeDao.getAllByPage(page.limit, page.offset, sqlClient)

        return Successful(payload(locales, count, page))
    }

    companion object {
        /** The whole response body: `{ items, page }`. */
        fun payload(locales: List<Locale>, count: Long, page: PageRequest): Map<String, Any?> =
            Paging.response(locales, count, page)
    }
}
