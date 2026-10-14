package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.CreatedMailLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailLayout
import com.panomc.platform.error.InvalidFields
import com.panomc.platform.error.NotExists
import com.panomc.platform.mail.template.MailTemplateIndex
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
 * Makes a custom layout (`custom:<slug of the name>`) as a copy of `copyFrom` (any layout, as it is used now) or of
 * the main layout. Answers like [PanelGetMailLayoutAPI].
 */
@Endpoint
class PanelCreateMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layouts", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                json(
                    objectSchema()
                        .requiredProperty("name", stringSchema())
                        .optionalProperty("copyFrom", stringSchema().nullable())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val body = getParameters(context).body().jsonObject
        val sqlClient = getSqlClient()

        val name = requireName(body.getString("name"))
        val copyFrom = body.getString("copyFrom")?.takeIf { it.isNotBlank() } ?: MailTemplateIndex.DEFAULT_LAYOUT
        val source = support.service.layout(copyFrom, sqlClient) ?: throw NotExists()

        val key = support.service.newCustomLayoutKey(name, sqlClient)
        val id = "${MailTemplateService.CUSTOM_OWNER}:$key"

        databaseManager.mailLayoutDao.upsert(
            MailLayout(owner = MailTemplateService.CUSTOM_OWNER, layoutKey = key, name = name, body = source.body),
            sqlClient
        )

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(CreatedMailLayoutLog(userId, username, id, name), sqlClient)

        return Successful(support.layoutDetails(id, userId, sqlClient))
    }

    companion object {
        /** A custom layout's name: not blank, at most [MailTemplateService.MAX_LAYOUT_NAME] characters. */
        fun requireName(value: String?): String {
            val name = value.orEmpty().trim()

            if (name.isEmpty()) throw InvalidFields(mapOf("name" to MailTemplateService.REQUIRED))
            if (name.length > MailTemplateService.MAX_LAYOUT_NAME) throw InvalidFields(mapOf("name" to MailTemplateService.TOO_LONG))

            return name
        }
    }
}
