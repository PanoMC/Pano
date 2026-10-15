package com.panomc.platform.route.api.sidebar

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.json.schema.SchemaRepository
import com.panomc.platform.schema.EndpointDoc
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

@Endpoint
class SupportSidebarAPI(private val databaseManager: DatabaseManager) : Api() {
    override val paths = listOf(Path("/sidebars/support", RouteType.GET))

    override val doc = EndpointDoc(
        summary = "The support page sidebar: the staff members who are online.",
        tag = "sidebars",
        response = objectSchema().requiredProperty("items", arraySchema().items(stringSchema()))
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        val response = mutableMapOf<String, Any?>()

        val sqlClient = getSqlClient()

        response["items"] = databaseManager.userDao.getOnlineAdmins(-1, sqlClient).map { it.username }

        return Successful(response)
    }
}