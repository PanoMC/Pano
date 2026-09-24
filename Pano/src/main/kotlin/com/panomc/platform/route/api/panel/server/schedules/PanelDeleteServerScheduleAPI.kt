package com.panomc.platform.route.api.panel.server.schedules

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerScheduleActionLog
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.ServerScheduleService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import com.panomc.platform.util.UsageMode

/** Deletes one schedule and tells the node to forget it. */
@Endpoint
class PanelDeleteServerScheduleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val scheduleService: ServerScheduleService
) : PanelApi() {
    override val usageModes = UsageMode.WITH_SERVERS

    override val paths = listOf(Path("/api/panel/servers/:id/schedules/:sid/delete", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .pathParameter(param("sid", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long
        val scheduleId = parameters.pathParameter("sid").long

        authProvider.requirePermission(ManageServerSchedulesPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val schedule = databaseManager.serverScheduleDao.getById(scheduleId, sqlClient) ?: throw NotExists()

        if (schedule.serverId != id) {
            throw NotExists()
        }

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient) ?: throw NotExists()

        scheduleService.delete(server, schedule, sqlClient)

        databaseManager.panelActivityLogDao.add(
            ServerScheduleActionLog(
                userId,
                username,
                id,
                ServerScheduleActionLog.ACTION_DELETE,
                schedule.name,
                schedule.cron
            ),
            sqlClient
        )

        return Successful()
    }
}
