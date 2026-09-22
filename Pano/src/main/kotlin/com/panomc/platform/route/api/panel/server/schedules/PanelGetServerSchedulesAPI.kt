package com.panomc.platform.route.api.panel.server.schedules

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManageServerSchedulesPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.NotExists
import com.panomc.platform.model.*
import com.panomc.platform.server.schedule.CronSchedules
import com.panomc.platform.server.schedule.ServerScheduleService
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

/**
 * One server's schedules, each with its steps and the next time it is due.
 *
 * `nextRunAt` is recomputed on read rather than served from the column: the stored value is
 * whatever was true when the schedule was last saved or last ran, and a panel opened days later
 * would otherwise show a time in the past.
 *
 * `description` is the same plain-English sentence the cron preview shows while the schedule is
 * being written, repeated here so the list answers "what does this one do" without making anyone
 * re-read cron fields they only understood once.
 */
@Endpoint
class PanelGetServerSchedulesAPI(
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val scheduleService: ServerScheduleService
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/servers/:id/schedules", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val id = parameters.pathParameter("id").long

        authProvider.requirePermission(ManageServerSchedulesPermission(), context, id)

        val sqlClient = getSqlClient()

        val server = databaseManager.serverDao.getById(id, sqlClient) ?: throw NotExists()

        if (!server.permissionGranted) {
            throw NotExists()
        }

        val now = System.currentTimeMillis()

        val schedules = scheduleService.listDetailed(id, sqlClient).map { detailed ->
            detailed.toJsonObject()
                .put("description", CronSchedules.describe(detailed.schedule.cron))
                .put(
                    "nextRunAt",
                    if (detailed.schedule.enabled) {
                        CronSchedules.nextRun(detailed.schedule.cron, detailed.schedule.timezone, now)
                    } else {
                        null
                    }
                )
        }

        return Successful(
            mapOf(
                "schedules" to JsonArray(schedules),
                // A linked server has no directory Pano can reach, so the panel hides the backup
                // step rather than offering one that would be refused on save.
                "backupsSupported" to server.isManaged,
                "timezone" to java.time.ZoneId.systemDefault().id
            )
        )
    }
}
