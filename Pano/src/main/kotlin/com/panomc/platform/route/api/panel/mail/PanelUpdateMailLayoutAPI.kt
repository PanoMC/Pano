package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedMailLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailTemplate
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** Overrides the shared layout; it must keep `{{{content}}}` and compile. */
@Endpoint
class PanelUpdateMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layout", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(objectSchema().requiredProperty("body", stringSchema())))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val body = getParameters(context).body().jsonObject.getString("body").orEmpty()
        val sqlClient = getSqlClient()

        val result = support.service.validateLayout(body, support.previewSystemVariables(configManager.config.locale))

        if (!result.valid) throw PanelMailTemplateSupport.invalid(result.fields, result.message)

        databaseManager.mailTemplateDao.upsert(
            MailTemplate(
                owner = MailTemplate.LAYOUT_OWNER,
                mailKey = MailTemplate.LAYOUT_KEY,
                variant = MailTemplate.LAYOUT_VARIANT,
                localeCode = MailTemplate.LAYOUT_LOCALE,
                subject = "",
                body = body
            ),
            sqlClient
        )

        support.service.evictLayout()

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(UpdatedMailLayoutLog(userId, username), sqlClient)

        return Successful(mapOf("body" to body, "customized" to true))
    }
}
