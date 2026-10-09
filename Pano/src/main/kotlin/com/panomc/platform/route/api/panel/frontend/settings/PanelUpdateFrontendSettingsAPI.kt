package com.panomc.platform.route.api.panel.frontend.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.frontend.FrontendSettings
import com.panomc.platform.frontend.SettingsUpload
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.platform.util.UsageMode
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema

/**
 * `PUT /panel/frontend/settings` (doc 05 section 9): `{ "settings": { <field>: <value>, "files": {...}, "remove-files": [...] } }`
 * as JSON, or as multipart when an image field uploads a file (the file part is named after the field).
 *
 * The values are checked by the `fields` of the active front-end's `settingsSchema`: a bad value is
 * `FRONTEND_SETTING_INVALID { key, reason }`, a key that is no field is dropped, a front-end without `fields`
 * is `FRONTEND_SETTINGS_NO_SCHEMA`. The write replaces what was stored (image files stay until named in
 * `remove-files`), so the form sends every field. Answers like the GET.
 */
@Endpoint
class PanelUpdateFrontendSettingsAPI(
    private val frontendSettings: FrontendSettings,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/frontend/settings", RouteType.PUT))

    override fun bodyHandler(): Handler<RoutingContext> = authorizedBodyHandler(
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(MAX_BODY_BYTES)
    )

    override suspend fun checkBeforeBody(context: RoutingContext) {
        authProvider.requirePermission(ManageViewPermission(), context)
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().requiredProperty("settings", objectSchema())))
            .body(Bodies.multipartFormData(objectSchema().requiredProperty("settings", objectSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        val request = getParameters(context).body().jsonObject.getJsonObject("settings")

        val uploads = context.fileUploads().map {
            SettingsUpload(it.name(), it.uploadedFileName(), it.fileName(), it.contentType())
        }

        val sqlClient = getSqlClient()
        val view = frontendSettings.update(request, uploads, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(ChangedFrontendSettingsLog(userId, username, view.id), sqlClient)

        return Successful(frontendSettingsState(view))
    }

    companion object {
        const val MAX_BODY_BYTES = 100L * 1024 * 1024
    }
}
