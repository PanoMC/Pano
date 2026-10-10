package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/panel/settings/pano-mail` → `{website, connected, account {username} | null, provider, enabled,
 * ready, from, held, plan {id, name, free, mailsPerMonth, mailsPerDay} | null, usage {day, month} | null,
 * freePlan {mailsPerMonth, mailsPerDay} | null, manageUrl}`. `ready`/`from`/`held`/`plan`/`usage` come from the
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
