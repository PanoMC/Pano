package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.UpdatedMailLayoutLog
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.MailLayout
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
 * Saves a layout: the override of a bundled one, or a custom one (which may also be renamed). The body must keep
 * `{{{content}}}` and compile. Answers like [PanelGetMailLayoutAPI].
 */
@Endpoint
class PanelUpdateMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val configManager: ConfigManager,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layouts/:id", RouteType.PUT))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("body", stringSchema())
                        .optionalProperty("name", stringSchema().nullable())
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").string
        val json = parameters.body().jsonObject
        val sqlClient = getSqlClient()

        val info = support.service.layoutInfo(id, sqlClient) ?: throw NotExists()

        val name = if (info.builtIn) "" else PanelCreateMailLayoutAPI.requireName(
            json.getString("name") ?: info.name(configManager.config.locale)
        )

        val body = json.getString("body").orEmpty()
        val result = support.service.validateLayout(body, support.previewSystemVariables(configManager.config.locale))

        if (!result.valid) throw PanelMailTemplateSupport.invalid(result.fields, result.message)

        databaseManager.mailLayoutDao.upsert(
            MailLayout(owner = info.owner, layoutKey = info.key, name = name, body = body),
            sqlClient
        )

        support.service.evictLayout(id)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(
            UpdatedMailLayoutLog(userId, username, id, support.layoutName(id, sqlClient)),
            sqlClient
        )

        return Successful(support.layoutDetails(id, userId, sqlClient))
    }
}
