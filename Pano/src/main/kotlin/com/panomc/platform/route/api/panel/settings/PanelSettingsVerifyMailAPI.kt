package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.mail.MailManager
import com.panomc.platform.model.*
import io.vertx.core.json.JsonObject
import io.vertx.ext.mail.StartTLSOptions
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*

@Endpoint
class PanelSettingsVerifyMailAPI(
    private val mailManager: MailManager,
    private val authProvider: AuthProvider,
    private val configManager: ConfigManager
) : PanelApi() {
    override val paths = listOf(Path("/settings/verify/mail", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("hostname", stringSchema())
                        .requiredProperty("port", intSchema())
                        .optionalProperty("ssl", booleanSchema())
                        .requiredProperty(
                            "starttls",
                            enumSchema(*StartTLSOptions.entries.map { it.name }.toTypedArray())
                        )
                        .requiredProperty("username", stringSchema())
                        .requiredProperty("password", stringSchema())
                        .requiredProperty("sender", stringSchema())
                        .optionalProperty("authMethods", stringSchema())
                        .optionalProperty("hostManaged", booleanSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManagePlatformSettingsPermission(), context)

        val parameters = getParameters(context)
        val body = parameters.body().jsonObject

        // Pano Host mail: checked with the env's relay settings; the panel never sees the relay password.
        val hostMail = if (body.getBoolean("hostManaged") == true) {
            HostedEnvConfig.current.hostMail(body.getString("sender"), withPassword = true)
        } else null
        val config = hostMail?.let { JsonObject(it) } ?: body.also { custom ->
            // Own SMTP with an empty password: the stored one of the same server and account (the
            // panel never receives it).
            if (custom.getString("password").isNullOrEmpty()) {
                val email = configManager.config.email
                val stored = email.custom?.takeIf { it.hostname == custom.getString("hostname") && it.username == custom.getString("username") }

                stored?.password?.let { custom.put("password", it) }
            }
        }

        val sender = config.getString("sender")

        mailManager.validateConfig(config, sender)

        return Successful()
    }
}