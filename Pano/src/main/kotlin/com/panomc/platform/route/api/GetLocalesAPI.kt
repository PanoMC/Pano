package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.Locale
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.CoreSchemas

@Endpoint
class GetLocalesAPI(
    private val databaseManager: DatabaseManager
) : Api() {
    override val paths = listOf(Path("/locales", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The languages of the site.",
        tag = "locales",
        response = CoreSchemas.list(CoreSchemas.locale)
    )

    // panel-ui bootstraps its i18n from here, both SSR and CSR.
    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val sqlClient = getSqlClient()

        val locales = databaseManager.localeDao.getAll(sqlClient)

        return Successful(payload(locales))
    }

    companion object {
        /** The response body: `{ items }` (doc 04 section 4). */
        fun payload(locales: List<Locale>): Map<String, Any?> = mapOf("items" to locales)
    }
}