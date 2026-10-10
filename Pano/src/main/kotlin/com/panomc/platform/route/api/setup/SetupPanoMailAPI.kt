package com.panomc.platform.route.api.setup

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.mail.PanoMailService
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.json.schema.SchemaRepository

/**
 * `GET /api/v1/setup/pano-mail` → `{website, connected, account {username} | null, freePlan {mailsPerMonth,
 * mailsPerDay} | null, sender {name, zone, localPart} | null}`: what setup step 3 shows for "send through Pano Mail". `website` is the connected
 * website's URL (`pano-website-url`); `freePlan` is the free plan of the website's public catalog (cached a few
 * minutes), null when it cannot be read. `sender` (the address name the website has or suggests, its zone, and the local part) is null when not connected or the website is unreachable. Never fails because the website is down.
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
        val sender = PanoMailService.senderView(config, panoMailService.shortStatus())

        return Successful(
            mapOf(
                "website" to config.panoWebsiteUrl,
                "connected" to connected,
                "account" to if (connected) mapOf("username" to config.panoAccount.username) else null,
                "freePlan" to panoMailService.freePlan(),
                "sender" to sender
            )
        )
    }
}

/**
 * `POST /api/v1/setup/pano-mail/name-check {name}` → `{name, status, reason?, minLength?, maxLength?}`; `status` is
 * `AVAILABLE`, `CURRENT`, `TAKEN` (also reserved names), `INVALID` or `UNKNOWN` (not connected, website unreachable or
 * any failure). Never an error answer; ~5 s timeout, no caching.
 */
@Endpoint
class SetupPanoMailNameCheckAPI(
    private val panoMailService: PanoMailService
) : SetupApi() {
    override val paths = listOf(Path("/setup/pano-mail/name-check", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("name", stringSchema())))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val name = getParameters(context).body().jsonObject.getString("name")

        return Successful(panoMailService.checkName(name))
    }
}
