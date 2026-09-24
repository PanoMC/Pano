package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.model.*
import com.panomc.platform.server.alert.AlertManager
import com.panomc.platform.server.alert.AlertSetting
import com.panomc.platform.server.alert.AlertSettings
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import com.panomc.platform.util.UsageMode

/**
 * Replaces the alert switch grid.
 *
 * The whole grid every time rather than one kind at a time: it is one screen with one save button,
 * and a partial update would need a rule for what a missing kind means. Kinds that are not in the
 * body fall back to their defaults, so an older panel cannot silently disable what it does not
 * know about.
 *
 * An e-mail switch turned on in a build with no template is stored as asked and simply does
 * nothing until the template ships — refusing it would lose a preference somebody meant.
 */
@Endpoint
class PanelUpdateAlertSettingsAPI(
    private val authProvider: AuthProvider,
    private val alertManager: AlertManager
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/settings/alerts", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("alerts", objectSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)

        val settings: Map<com.panomc.platform.server.alert.ServerAlertKind, AlertSetting> =
            AlertSettings.fromJson(parameters.body().jsonObject.getJsonObject("alerts"))

        alertManager.updateSettings(settings, getSqlClient())

        return Successful(
            mapOf(
                "alerts" to AlertSettings.toJson(settings),
                "emailAvailable" to alertManager.isEmailAvailable
            )
        )
    }
}
