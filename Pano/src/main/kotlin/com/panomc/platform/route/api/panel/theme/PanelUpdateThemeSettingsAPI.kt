package com.panomc.platform.route.api.panel.theme

import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageViewPermission
import com.panomc.platform.error.NoPermission
import com.panomc.platform.frontend.FrontendSettings
import com.panomc.platform.frontend.FrontendSettingsStorage
import com.panomc.platform.frontend.SettingsPlan
import com.panomc.platform.frontend.SettingsUpload
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.platform.util.UsageMode

@Endpoint
class PanelUpdateThemeSettingsAPI(
    private val authProvider: AuthProvider,
    private val uiManager: UIManager,
    private val frontendSettings: FrontendSettings
) : PanelApi() {
    override val usageModes = UsageMode.WITH_WEBSITE

    override val paths = listOf(Path("/theme/settings", RouteType.PUT))

    companion object {
        const val THEME_SETTINGS = FrontendSettingsStorage.PROPERTY
    }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(100 * 1024 * 1024) // 100MB

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.multipartFormData(
                    objectSchema()
                        .requiredProperty(
                            "settings", objectSchema()
                        )
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    /**
     * Replaces the settings of the active front-end with `settings` (multipart, so image files can ride along).
     * The storage rules (kept, added and removed files, the `theme_settings` property) are those of
     * [FrontendSettingsStorage], shared with the schema-driven `PUT /panel/frontend/settings`; this endpoint
     * checks the values against nothing, a theme's own settings page owns its keys.
     */
    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageViewPermission(), context)

        if (!uiManager.activatedUIList.containsKey(Type.THEME_UI)) {
            throw NoPermission()
        }

        val data = getParameters(context).body().jsonObject

        val uploads = context.fileUploads().map {
            SettingsUpload(it.name(), it.uploadedFileName(), it.fileName(), it.contentType())
        }

        val sqlClient = getSqlClient()
        val frontendId = frontendSettings.activeId(sqlClient)
        val storage = frontendSettings.storage()
        val access = frontendSettings.access(sqlClient)

        val plan: SettingsPlan = FrontendSettingsStorage.plan(storage.load(access, frontendId), data.getJsonObject("settings"), uploads)

        storage.commit(access, frontendId, plan)

        return Successful(plan.settings.map)
    }
}
