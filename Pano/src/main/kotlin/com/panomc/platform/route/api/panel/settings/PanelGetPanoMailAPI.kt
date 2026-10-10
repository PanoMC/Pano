package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/panel/settings/pano-mail` → `{website, connected, account {username} | null, provider, enabled,
 * ready, from, held, plan {id, name, free, mailsPerMonth, mailsPerDay} | null, usage {day, month} | null,
 * freePlan {mailsPerMonth, mailsPerDay} | null, sender {name, zone, localPart} | null, manageUrl}`. `ready`/`from`/`held`/`plan`/`usage` come from the
 * website's `GET /host/connected/mail` (cached a minute) and are null when this Pano is not connected or the
 * website is unreachable; the request never fails because of the website.
 */
@Endpoint
class PanelGetPanoMailAPI(
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager,
    private val panoMailService: PanoMailService
) : PanelApi() {
    override val paths = listOf(Path("/settings/pano-mail", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        return Successful(PanoMailService.panelView(configManager.config, panoMailService.cachedStatus(), panoMailService.freePlan()))
    }
}

/**
 * `POST /api/v1/panel/settings/pano-mail/name-check {name}` → `{name, status, reason?, minLength?, maxLength?}`; see
 * `SetupPanoMailNameCheckAPI`. Never an error answer because of the website.
 */
@Endpoint
class PanelPanoMailNameCheckAPI(
    private val authProvider: AuthProvider,
    private val panoMailService: PanoMailService
) : PanelApi() {
    override val paths = listOf(Path("/settings/pano-mail/name-check", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("name", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val name = getParameters(context).body().jsonObject.getString("name")

        return Successful(panoMailService.checkName(name))
    }
}
