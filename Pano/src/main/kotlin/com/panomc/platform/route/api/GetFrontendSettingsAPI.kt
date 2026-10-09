package com.panomc.platform.route.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.frontend.FrontendSettings
import com.panomc.platform.model.*
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.util.UsageMode
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * `GET /frontend/settings` (public; doc 05 section 9, doc 04 section 8): the settings of the active front-end,
 * `{ "id", "settings": {...}, "files": {...} }`. With a `settingsSchema` the values have their defaults filled in
 * and only the schema's fields are listed; `files` holds the uploaded file names of the image fields (served by
 * `GET /theme/file/:filename`). Without one, what is stored is returned as it is (what `site-info.themeSettings`
 * holds for a theme). Stays available in maintenance mode, as site info does.
 */
@Endpoint
class GetFrontendSettingsAPI(private val frontendSettings: FrontendSettings) : Api() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/settings", RouteType.GET))

    override val maintenanceAccess = MaintenanceAccess.ALWAYS

    override val doc = EndpointDoc(
        summary = "The settings of the active front-end, defaults filled in.",
        tag = "frontend",
        response = objectSchema()
            .requiredProperty("id", stringSchema())
            .requiredProperty("settings", objectSchema())
            .requiredProperty("files", objectSchema())
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val view = frontendSettings.read(getSqlClient())

        return Successful(mapOf("id" to view.id, "settings" to view.reading.settings.map, "files" to view.reading.files.map))
    }
}
