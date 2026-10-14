package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.MailManager
import com.panomc.platform.mail.MailOptions
import com.panomc.platform.mail.TemplateMail
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository

/**
 * Sends an unsaved template, rendered with the variant's sample variables, to the admin's own address. Strict:
 * `MAIL_DISABLED`, `PANO_MAIL_UNAVAILABLE` and `PANO_MAIL_CONNECT_REQUIRED` surface as errors.
 */
@Endpoint
class PanelSendTestMailTemplateAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val mailManager: MailManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/test", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(PanelPreviewMailTemplateAPI.draftSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val body = getParameters(context).body().jsonObject
        val sqlClient = getSqlClient()

        val target = support.target(
            body.getString("owner"), body.getString("key"), body.getString("variant"), body.getString("locale"), sqlClient
        )

        val content = PanelMailTemplateSupport.templateFrom(body.getJsonObject("template"))

        support.requireValid(content, target, templateErrorsOnly = true)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val email = databaseManager.userDao.getById(userId, sqlClient)?.email?.takeIf { it.isNotBlank() }
            ?: throw NotExists()

        mailManager.sendRequiredDraft(
            sqlClient,
            userId,
            TemplateMail.of(target.owner, target.mail.key, target.variant.key, target.variant.sampleVariables()),
            content,
            MailOptions(email = email, locale = target.locale)
        )

        return Successful(mapOf("email" to email))
    }
}
