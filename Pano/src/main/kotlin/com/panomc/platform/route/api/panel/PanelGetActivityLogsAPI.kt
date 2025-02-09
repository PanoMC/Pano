package com.panomc.platform.route.api.panel

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.AccessActivityLogsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.*
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaParser
import io.vertx.json.schema.common.dsl.Schemas.intSchema

@Endpoint
class PanelGetActivityLogsAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/logs/activity", RouteType.GET))

    override fun getValidationHandler(schemaParser: SchemaParser): ValidationHandler =
        ValidationHandlerBuilder.create(schemaParser)
            .queryParameter(optionalParam("page", intSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val page = parameters.queryParameter("page")?.long ?: 1

        val userId = authProvider.getUserIdFromRoutingContext(context)

        val hasPermission = authProvider.hasPermission(userId, AccessActivityLogsPermission(), context)

        val sqlClient = getSqlClient()

        val totalCount = if (hasPermission)
            databaseManager.panelActivityLogDao.count(sqlClient)
        else
            databaseManager.panelActivityLogDao.count(userId, sqlClient)

        var totalPage = kotlin.math.ceil(totalCount.toDouble() / 10).toLong()

        if (totalPage < 1) {
            totalPage = 1
        }

        if (page > totalPage || page < 1) {
            throw PageNotFound()
        }

        val platforms = if (hasPermission)
            databaseManager.panelActivityLogDao.getAll(page, sqlClient)
        else
            databaseManager.panelActivityLogDao.byUserId(userId, page, sqlClient)

        val response = mutableMapOf(
            "data" to platforms,
            "meta" to mapOf(
                "filteredCount" to totalCount,
                "totalCount" to totalCount,
                "totalPage" to totalPage
            )
        )

        return Successful(
            response,
        )
    }
}