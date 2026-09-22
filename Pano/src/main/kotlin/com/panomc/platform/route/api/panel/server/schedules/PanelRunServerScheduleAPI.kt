package com.panomc.platform.route.api.panel.server.schedules

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.log.ServerScheduleActionLog
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.ScheduleExecutor
import com.panomc.platform.server.schedule.ScheduleRunStatus
import com.panomc.platform.server.schedule.ServerScheduleService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * Runs one schedule right now, countdown skipped.
 *
 * Pano performs the steps itself even for a managed server, rather than asking its node to fire
 * the schedule early: the node's copy is a timetable, and making it runnable out of band would
 * mean a second code path that could disagree with the timed one. The result is recorded exactly
 * as a timed run would be, so the panel's "last run" reflects what actually happened.
 */
@Endpoint
class PanelRunServerScheduleAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val scheduleService: ServerScheduleService,
    private val scheduleExecutor: ScheduleExecutor
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/schedules/:sid/run", RouteType.POST))

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

        val startedAt = System.currentTimeMillis()

        val tasks = databaseManager.serverScheduleTaskDao.getAllByScheduleId(scheduleId, sqlClient)

        val outcome = scheduleExecutor.run(server, schedule.name, tasks, userId, sqlClient)

        scheduleService.recordRun(server, schedule, outcome.status, outcome.error, startedAt, sqlClient)

        databaseManager.panelActivityLogDao.add(
            ServerScheduleActionLog(
                userId,
                username,
                id,
                ServerScheduleActionLog.ACTION_RUN,
                schedule.name,
                schedule.cron
            ),
            sqlClient
        )

        return Successful(
            mapOf(
                "ok" to (outcome.status == ScheduleRunStatus.OK),
                "status" to outcome.status.name,
                "error" to outcome.error
            )
        )
    }
}
