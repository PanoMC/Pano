package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedMailTemplateLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailTemplate
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** Saves the panel override of one mail × variant × language after validating it. */
@Endpoint
class PanelUpdateMailTemplateAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/:owner/:key/:variant/:locale", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("owner", stringSchema()))
            .pathParameter(param("key", stringSchema()))
            .pathParameter(param("variant", stringSchema()))
            .pathParameter(param("locale", stringSchema()))
            .body(json(PanelMailTemplateSupport.templateSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val parameters = getParameters(context)
        val sqlClient = getSqlClient()

        val target = support.target(
            parameters.pathParameter("owner").string,
            parameters.pathParameter("key").string,
            parameters.pathParameter("variant").string,
            parameters.pathParameter("locale").string,
            sqlClient
        )

        val content = PanelMailTemplateSupport.templateFrom(parameters.body().jsonObject)

        support.requireValid(content, target)

        databaseManager.mailTemplateDao.upsert(
            MailTemplate(
                owner = target.owner,
                mailKey = target.mail.key,
                variant = target.variant.key,
                localeCode = target.locale,
                mode = content.mode.name,
                subject = content.subject,
                preheader = content.preheader,
                body = content.body,
                buttonLabel = content.buttonLabel,
                buttonUrl = content.buttonUrl,
                footer = content.footer
            ),
            sqlClient
        )

        support.service.evict(target.owner, target.mail.key, target.variant.key, target.locale)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMailTemplateLog(userId, username, target.owner, target.mail.key, target.variant.key, target.locale),
            sqlClient
        )

        return Successful(mapOf("template" to content.toMap(), "customized" to true))
    }
}
