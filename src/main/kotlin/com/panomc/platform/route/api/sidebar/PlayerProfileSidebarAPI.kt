package com.panomc.platform.route.api.sidebar

import com.panomc.platform.ErrorCode
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PlayerProfileSidebarAPI(private val databaseManager: DatabaseManager) : Api() {
    override val paths = listOf(Path("/api/sidebar/profile/:username", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .pathParameter(Parameters.param("username", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlConnection = createConnection(context)

        val user = databaseManager.userDao.getByUsername(username, sqlConnection) ?: throw Error(ErrorCode.NOT_EXISTS)

        val userPermissionGroupId = databaseManager.userDao.getPermissionGroupIdFromUserId(user.id, sqlConnection)!!

        var name = ""

        if (userPermissionGroupId != -1L) {
            val userPermissionGroup =
                databaseManager.permissionGroupDao.getPermissionGroupById(userPermissionGroupId, sqlConnection)!!

            name = userPermissionGroup.name
        }

        val response = mutableMapOf<String, Any?>()

        response["lastActivityTime"] = user.lastActivityTime

        response["inGame"] = databaseManager.serverPlayerDao.existsByUsername(user.username, sqlConnection)

        response["permissionGroupName"] = name

        return Successful(response)
    }
}