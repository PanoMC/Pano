package com.panomc.platform.route.api.panel

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import com.panomc.platform.UIManager
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManageTicketsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.*
import com.panomc.platform.util.DashboardPeriodType
import com.panomc.platform.util.TimeUtil.toGroupGetCountAndDates
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import org.pf4j.PluginState
import java.util.Calendar

@Endpoint
class PanelGetStatisticsAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val uiManager: UIManager,
    private val pluginManager: PluginManager,
    private val permissionManager: PermissionManager
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/statistics", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .queryParameter(
                param(
                    "period",
                    arraySchema()
                        .items(enumSchema(*DashboardPeriodType.entries.map { it.name }.toTypedArray()))
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        val parameters = getParameters(context)

        val periodQueryParam =
            parameters.queryParameter("period")?.jsonArray?.first() as String? ?: DashboardPeriodType.WEEK.name

        val period = DashboardPeriodType.valueOf(periodQueryParam)

        val result = mutableMapOf<String, Any?>(
            "onlinePlayerCount" to 0,
            "registeredPlayerCount" to 0,
            "postCount" to 0,
            "adminCount" to 0,
            "connectedServerCount" to 0,
            "newRegisterCount" to 0,
            "period" to period,
            "websiteActivityDataList" to mutableMapOf<String, Any?>(),
            "ticketCount" to 0,
            "installedThemes" to uiManager.installedThemeList.size,
            "activePlugins" to pluginManager.plugins.filter { it.pluginState == PluginState.STARTED }.size,
            "installedPlugins" to pluginManager.plugins.size
        )

        val sqlClient = getSqlClient()

        result["registeredPlayerCount"] = databaseManager.userDao.count(sqlClient)

        result["onlinePlayerCount"] = databaseManager.userDao.countOfOnline(sqlClient)

        result["postCount"] = databaseManager.postDao.count(sqlClient)

        if (authProvider.hasPermission(ManageTicketsPermission(), context)) {
            val ticketCount = databaseManager.ticketDao.count(sqlClient)

            result["ticketCount"] = ticketCount

            if (ticketCount != 0L) {
                val openTicketCount = databaseManager.ticketDao.countOfOpenTickets(sqlClient)

                result["openTicketCount"] = openTicketCount
            }
        }

        result["adminCount"] = permissionManager.getUserIdsInGroup("admin").size

        result["newRegisterCount"] = databaseManager.userDao.countOfRegisterByPeriod(period, sqlClient)

        val websiteActivityDataList = result["websiteActivityDataList"] as MutableMap<String, Any?>

        if (Main.IS_DEMO) {
            val amountOfDays = if (period == DashboardPeriodType.WEEK) 7 else 30
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = System.currentTimeMillis()
            calendar[Calendar.HOUR_OF_DAY] = 0
            calendar[Calendar.MINUTE] = 0
            calendar[Calendar.SECOND] = 0
            calendar[Calendar.MILLISECOND] = 0

            val newRegisterData = mutableMapOf<Long, Int>()
            val ticketsData = mutableMapOf<Long, Int>()
            val visitorData = mutableMapOf<Long, Int>()
            val viewData = mutableMapOf<Long, Int>()

            for (i in 0..amountOfDays) {
                val time = calendar.timeInMillis
                newRegisterData[time] = kotlin.random.Random.nextInt(5, 15)
                ticketsData[time] = kotlin.random.Random.nextInt(2, 8)
                visitorData[time] = kotlin.random.Random.nextInt(100, 200)
                viewData[time] = kotlin.random.Random.nextInt(500, 1000)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }

            websiteActivityDataList["newRegisterData"] = newRegisterData
            websiteActivityDataList["ticketsData"] = ticketsData
            websiteActivityDataList["visitorData"] = visitorData
            websiteActivityDataList["viewData"] = viewData
        } else {
            val registerDateList = databaseManager.userDao.getRegisterDatesByPeriod(period, sqlClient)
            websiteActivityDataList["newRegisterData"] = registerDateList.toGroupGetCountAndDates()

            val ticketsDateList = databaseManager.ticketDao.getDatesByPeriod(period, sqlClient)
            websiteActivityDataList["ticketsData"] = ticketsDateList.toGroupGetCountAndDates()

            val websiteViewData =
                databaseManager.websiteViewDao.getWebsiteViewListByPeriod(period, sqlClient)

            val viewsDateMap = mutableMapOf<Long, Long>()
            val visitorDateMap = mutableMapOf<Long, Long>()

            websiteViewData.forEach { viewData ->
                if (viewsDateMap.containsKey(viewData.date)) {
                    viewsDateMap[viewData.date] =
                        viewsDateMap[viewData.date]!!.plus(viewData.times)
                } else {
                    viewsDateMap[viewData.date] = viewData.times
                }

                if (visitorDateMap.containsKey(viewData.date)) {
                    visitorDateMap[viewData.date] = visitorDateMap[viewData.date]!!.plus(1)
                } else {
                    visitorDateMap[viewData.date] = 1
                }
            }
            websiteActivityDataList["visitorData"] = visitorDateMap
            websiteActivityDataList["viewData"] = viewsDateMap
        }

        val connectedServerCount = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

        result["connectedServerCount"] = connectedServerCount

        return Successful(result)
    }
}