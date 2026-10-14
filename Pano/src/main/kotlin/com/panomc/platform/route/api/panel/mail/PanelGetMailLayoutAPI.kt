package com.panomc.platform.route.api.panel.mail

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageMailTemplatesPermission
import com.panomc.platform.model.*
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * One layout: `{ id, owner, pluginId, pluginName, key, name, builtIn, customized, deletable, usedBy, body, default,
 * websiteLogo, websiteName }`; `default` is the bundled body (null for a custom layout).
 */
@Endpoint
class PanelGetMailLayoutAPI(
    private val authProvider: AuthProvider,
    private val support: PanelMailTemplateSupport
) : PanelApi() {
    override val paths = listOf(Path("/mail-templates/layouts/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMailTemplatesPermission(), context)

        val id = getParameters(context).pathParameter("id").string
        val userId = authProvider.getUserIdFromRoutingContext(context)

        return Successful(support.layoutDetails(id, userId, getSqlClient()))
    }
}
