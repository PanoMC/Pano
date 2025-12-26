package com.panomc.platform.route.api.sidebar


import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.util.BanUtil
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas

@Endpoint
class PlayerProfileSidebarAPI(private val databaseManager: DatabaseManager,
                              private val permissionManager: PermissionManager
) : Api() {
    override val paths = listOf(Path("/api/sidebars/profile/:username", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(Parameters.param("username", Schemas.stringSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val username = parameters.pathParameter("username").string

        val sqlClient = getSqlClient()

        val user = databaseManager.userDao.getByUsername(username, sqlClient) ?: throw NotExists()

        val response = mutableMapOf<String, Any?>()

        response["lastActivityTime"] = user.lastActivityTime

        response["inGame"] = databaseManager.serverPlayerDao.existsByUsername(user.username, sqlClient)

        response["permissionGroupName"] = permissionManager.getPermissionGroup(user.id)?.displayName
        response["banned"] = BanUtil.isBanned(user)

        return Successful(response)
    }
}