package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.mail.template.MailTemplateContent
import com.panomc.platform.mail.template.MailTemplateService
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Renders an unsaved template with the variant's sample variables: `{ subject, html, text, templateHtml }`
 * (`templateHtml` = the unsaved fields composed with the layout, where the advanced mode starts). An optional
 * `variables` object (the preview window's test data) overrides the samples; keys the mail does not declare are kept.
 * An optional `layout` (an id or `none`) previews an unsaved layout choice.
 */
@Endpoint
class PanelPreviewMailTemplateAPI(
    private val authProvider: AuthProvider,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/preview", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(json(draftSchema().optionalProperty("variables", objectSchema())))
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

        val layoutChoice = support.requireLayoutChoice(body.getString("layout"), sqlClient)
        val layout = support.service.layoutForMail(target.owner, target.mail.key, sqlClient, layoutChoice)

        val rendered = try {
            support.service.render(
                content,
                layout.takeIf { content.mode == MailTemplateContent.Mode.FIELDS },
                PanelMailTemplateSupport.previewVariables(target.variant.sampleVariables(), body.getJsonObject("variables")),
                support.previewSystemVariables(target.locale)
            )
        } catch (e: Exception) {
            throw PanelMailTemplateSupport.invalid(
                mapOf(MailTemplateContent.FIELD_BODY to MailTemplateService.TEMPLATE_ERROR), e.message
            )
        }

        return Successful(
            mapOf(
                "subject" to rendered.subject,
                "html" to rendered.html,
                "text" to rendered.text,
                "templateHtml" to support.service.compose(content, layout)
            )
        )
    }

    companion object {
        /** `{ owner, key, variant, locale, template, layout? }`, shared with the test send. */
        fun draftSchema() = objectSchema()
            .optionalProperty("layout", stringSchema().nullable())
            .requiredProperty("owner", stringSchema())
            .requiredProperty("key", stringSchema())
            .requiredProperty("variant", stringSchema())
            .requiredProperty("locale", stringSchema())
            .requiredProperty("template", PanelMailTemplateSupport.templateSchema())
    }
}
