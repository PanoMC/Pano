package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/setup/pano-mail` → `{website, connected, account {username} | null, freePlan {mailsPerMonth,
 * mailsPerDay} | null}`: what setup step 3 shows for "send through Pano Mail". `website` is the connected
 * website's URL (`pano-website-url`); `freePlan` is the free plan of the website's public catalog (cached a few
 * minutes), null when it cannot be read. Never fails because the website is down.
 */
@Endpoint
class SetupGetPanoMailAPI(
    private val configManager: ConfigManager,
    private val panoMailService: PanoMailService
) : SetupApi() {
    override val paths = listOf(Path("/setup/pano-mail", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handle(context: RoutingContext): Result {
        val config = configManager.config
        val connected = panoMailService.isConnected()

        return Successful(
            mapOf(
                "website" to config.panoWebsiteUrl,
                "connected" to connected,
                "account" to if (connected) mapOf("username" to config.panoAccount.username) else null,
                "freePlan" to panoMailService.freePlan()
            )
        )
    }
}
