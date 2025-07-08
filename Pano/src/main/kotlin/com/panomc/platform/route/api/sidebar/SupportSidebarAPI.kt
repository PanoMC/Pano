package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository

@Endpoint
class SupportSidebarAPI(private val databaseManager: DatabaseManager) : Api() {
    override val paths = listOf(Path("/api/sidebars/support", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        val sqlClient = getSqlClient()

        response["onlineAdmins"] = databaseManager.userDao.getOnlineAdmins(-1, sqlClient).map { it.username }

        return Successful(response)
    }
}