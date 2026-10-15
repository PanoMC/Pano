package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ChangedMailTemplateLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailSetting
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Bodies.json
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * Chooses the layout of one mail (all its variants and languages): `{ layout: "none" | "<layoutId>" | null }`, null
 * going back to the mail's own default. Answers `{ layout (effective), layoutDefault }`.
 */
@Endpoint
class PanelUpdateMailTemplateLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/:owner/:key/layout", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("owner", stringSchema()))
            .pathParameter(param("key", stringSchema()))
            .body(json(objectSchema().optionalProperty("layout", stringSchema().nullable())))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val parameters = getParameters(context)
        val owner = parameters.pathParameter("owner").string
        val mail = support.registry.mail(owner, parameters.pathParameter("key").string) ?: throw NotExists()
        val sqlClient = getSqlClient()

        val choice = support.requireLayoutChoice(
            parameters.body().jsonObject.getString("layout")?.takeIf { it.isNotBlank() },
            sqlClient
        )

        val before = support.service.effectiveLayoutId(owner, mail.key, sqlClient)

        if (choice == null) {
            databaseManager.mailSettingDao.delete(owner, mail.key, sqlClient)
        } else {
            databaseManager.mailSettingDao.upsert(MailSetting(owner = owner, mailKey = mail.key, layout = choice), sqlClient)
        }

        val effective = support.service.effectiveLayoutId(owner, mail.key, sqlClient)

        if (effective != before) {
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(
                ChangedMailTemplateLayoutLog(userId, username, owner, mail.key, effective),
                sqlClient
            )
        }

        return Successful(mapOf("layout" to effective, "layoutDefault" to mail.layout))
    }
}
