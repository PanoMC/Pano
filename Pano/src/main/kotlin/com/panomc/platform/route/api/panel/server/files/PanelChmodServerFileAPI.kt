package com.panomc.platform.route.api.panel.server.files

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerFileChangedLog
import com.panomc.platform.auth.panel.permission.ManageServerFilesPermission
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.node.message.FileChmodMessage
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies.json
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import com.panomc.platform.util.UsageMode

/**
 * Sets POSIX permissions on a file inside a managed server.
 *
 * Only three octal digits are accepted — no setuid, no sticky bit. Those exist to make a file
 * behave differently from the account that runs it, which is not something a web panel should be
 * able to arrange on a machine it only supervises.
 */
@Endpoint
class PanelChmodServerFileAPI(
    private val authProvider: AuthProvider,
    private val fileClient: ManagedServerFileClient
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/files/chmod", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .body(
                json(
                    objectSchema()
                        .requiredProperty("path", stringSchema())
                        .requiredProperty("mode", stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerFilesPermission(), context, id)

        val data = parameters.body().jsonObject
        val path = ManagedServerFileClient.normalisePath(data.getString("path"))
        val mode = data.getString("mode")?.trim().orEmpty()

        if (!mode.matches(MODE_PATTERN)) {
            throw BadRequest()
        }

        val sqlClient = getSqlClient()
        val target = fileClient.resolve(id, sqlClient)

        fileClient.request(target, FileChmodMessage(target.serverUuid, path, mode))

        fileClient.log(context, id, ServerFileChangedLog.ACTION_CHMOD, path, mode, sqlClient)

        return Successful(mapOf("mode" to mode))
    }

    companion object {
        private val MODE_PATTERN = Regex("^[0-7]{3}$")
    }
}
