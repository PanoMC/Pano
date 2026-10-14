package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.mail.template.MailTemplateContent
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** One mail × variant × language: the template in use, the default under it, and the variables it may use. */
@Endpoint
class PanelGetMailTemplateAPI(
    private val authProvider: AuthProvider,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/:owner/:key/:variant/:locale", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("owner", stringSchema()))
            .pathParameter(param("key", stringSchema()))
            .pathParameter(param("variant", stringSchema()))
            .pathParameter(param("locale", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val parameters = getParameters(context)
        val sqlClient = getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val target = support.target(
            parameters.pathParameter("owner").string,
            parameters.pathParameter("key").string,
            parameters.pathParameter("variant").string,
            parameters.pathParameter("locale").string,
            sqlClient
        )

        val service = support.service
        val current = service.resolve(target.owner, target.mail.key, target.variant.key, target.locale, sqlClient)
        val default = service.resolve(
            target.owner, target.mail.key, target.variant.key, target.locale, sqlClient, skipOwnOverride = true
        )

        val customized = current != null && current.customized && current.locale == target.locale
        val empty = MailTemplateContent(subject = "", body = "")
        val adminLocale = support.adminLocale(userId, sqlClient)
        val layout = service.layout(sqlClient)

        return Successful(
            mapOf(
                "template" to (current?.content ?: empty).toMap(),
                "default" to (default?.content ?: empty).toMap(),
                // The same two as one complete HTML template, for the advanced mode.
                "templateHtml" to service.compose(current?.content ?: empty, layout),
                "defaultHtml" to service.compose(default?.content ?: empty, layout),
                "customized" to customized,
                "defaultLocale" to default?.locale,
                "variables" to target.variant.variables.map { it.toMap(adminLocale) }
            )
        )
    }
}
