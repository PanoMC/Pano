package com.panomc.platform.route.api.panel.server

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PanelPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.CurrentPasswordNotCorrect
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.ServerManager
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import org.apache.commons.codec.digest.DigestUtils

@Endpoint
class PanelDeleteServerAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val serverManager: ServerManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/delete", RouteType.POST))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(param("id", numberSchema()))
            .body(
                Bodies.json(
                    Schemas.objectSchema()
                        .property("currentPassword", Schemas.stringSchema())
                )
            )
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(PanelPermission.MANAGE_SERVERS, context)

        val parameters = getParameters(context)
        val data = parameters.body().jsonObject

        val id = parameters.pathParameter("id").long
        val currentPassword = data.getString("currentPassword")
        val userId = authProvider.getUserIdFromRoutingContext(context)

        val sqlClient = getSqlClient()

        val exists = databaseManager.serverDao.existsById(id, sqlClient)

        if (!exists) {
            throw NotExists()
        }

        val isCurrentPasswordCorrect =
            databaseManager.userDao.isPasswordCorrectWithId(userId, DigestUtils.md5Hex(currentPassword), sqlClient)

        if (!isCurrentPasswordCorrect) {
            throw CurrentPasswordNotCorrect()
        }

        serverManager.getConnectedServers()
            .filter { it.key.id == id }
            .forEach {
                it.value.close()
            }

        val mainServerId = databaseManager.systemPropertyDao.getByOption(
            "main_server",
            sqlClient
        )!!.value.toLong()

        if (mainServerId == id) {
            databaseManager.systemPropertyDao.update(
                "main_server",
                "-1",
                sqlClient
            )
        }

        databaseManager.serverPlayerDao.deleteByServerId(id, sqlClient)

        databaseManager.serverDao.deleteById(id, sqlClient)

        return Successful()
    }
}