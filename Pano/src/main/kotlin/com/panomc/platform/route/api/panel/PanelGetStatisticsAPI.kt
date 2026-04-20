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
import com.panomc.platform.util.TimeUtil
import com.panomc.platform.util.TimeUtil.toGroupGetCountAndDates
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.enumSchema
import org.pf4j.PluginState
import java.util.*

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

        val totalCount = databaseManager.userDao.count(sqlClient)
        result["registeredPlayerCount"] = totalCount

        val onlinePlayerCount = databaseManager.userDao.countOfOnline(sqlClient)
        result["onlinePlayerCount"] = onlinePlayerCount

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

        // Period bounds
        val currentPeriodStart = TimeUtil.getTimeToCompareByDashboardPeriodType(period)
        val previousPeriodStart = TimeUtil.getPreviousPeriodStart(period)
        val now = System.currentTimeMillis()

        // Build list of day-buckets (midnight of each day) covering the current period
        val amountOfDays = if (period == DashboardPeriodType.WEEK) 7 else 30
        val dayBuckets = mutableListOf<Long>()
        run {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = now
            calendar[Calendar.HOUR_OF_DAY] = 0
            calendar[Calendar.MINUTE] = 0
            calendar[Calendar.SECOND] = 0
            calendar[Calendar.MILLISECOND] = 0
            for (i in 0..amountOfDays) {
                dayBuckets.add(0, calendar.timeInMillis)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }
        }

        if (Main.IS_DEMO) {
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
            val onlinePlayerData = mutableMapOf<Long, Int>()
            val totalPlayerData = mutableMapOf<Long, Long>()

            var runningTotal = totalCount - (amountOfDays * 3L).coerceAtLeast(0L)
            if (runningTotal < 0) runningTotal = 0

            for (i in 0..amountOfDays) {
                val time = calendar.timeInMillis
                newRegisterData[time] = kotlin.random.Random.nextInt(5, 15)
                ticketsData[time] = kotlin.random.Random.nextInt(2, 8)
                visitorData[time] = kotlin.random.Random.nextInt(100, 200)
                viewData[time] = kotlin.random.Random.nextInt(500, 1000)
                onlinePlayerData[time] = kotlin.random.Random.nextInt(3, 20)
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            }

            // Fill totalPlayerData as a cumulative running count (ascending by date)
            val sortedDates = newRegisterData.keys.sorted()
            var cumulative = runningTotal
            for (date in sortedDates) {
                cumulative += (newRegisterData[date] ?: 0).toLong()
                totalPlayerData[date] = cumulative
            }

            websiteActivityDataList["newRegisterData"] = newRegisterData
            websiteActivityDataList["ticketsData"] = ticketsData
            websiteActivityDataList["visitorData"] = visitorData
            websiteActivityDataList["viewData"] = viewData
            websiteActivityDataList["onlinePlayerData"] = onlinePlayerData
            websiteActivityDataList["totalPlayerData"] = totalPlayerData

            // Previous period demo figures (comparable with current period metrics)
            result["previousOnlinePlayerCount"] = kotlin.random.Random.nextInt(3, 20)
            result["previousNewRegisterCount"] = newRegisterData.values.sum() + kotlin.random.Random.nextInt(-10, 10)
            result["previousRegisteredPlayerCount"] = runningTotal
        } else {
            val registerDateList = databaseManager.userDao.getRegisterDatesByPeriod(period, sqlClient)
            val newRegisterData = registerDateList.toGroupGetCountAndDates().toMutableMap()
            for (bucket in dayBuckets) {
                newRegisterData.putIfAbsent(bucket, 0)
            }
            websiteActivityDataList["newRegisterData"] = newRegisterData

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

            // Online player history: read the daily snapshots written by OnlinePlayerTracker.
            val currentOnlineHistory =
                databaseManager.onlinePlayerHistoryDao.getByTimeRange(currentPeriodStart, now, sqlClient)
            val onlinePlayerData = mutableMapOf<Long, Long>()
            for (bucket in dayBuckets) {
                onlinePlayerData[bucket] = 0L
            }
            currentOnlineHistory.forEach { entry ->
                onlinePlayerData[entry.date] = entry.maxCount
            }
            // Ensure today's bucket reflects the freshest value even if the tracker hasn't
            // sampled recently (e.g. right after a server restart).
            val todayBucket = TimeUtil.startOfDay(now)
            val currentMax = onlinePlayerData[todayBucket] ?: 0L
            if (onlinePlayerCount > currentMax) {
                onlinePlayerData[todayBucket] = onlinePlayerCount
            }
            websiteActivityDataList["onlinePlayerData"] = onlinePlayerData

            // Cumulative total players per day of the period.
            val totalAtPeriodStart = databaseManager.userDao.countBeforeTime(currentPeriodStart, sqlClient)
            val totalPlayerData = mutableMapOf<Long, Long>()
            var running = totalAtPeriodStart
            for (bucket in dayBuckets.sorted()) {
                val added = (newRegisterData[bucket] ?: 0).toLong()
                running += added
                totalPlayerData[bucket] = running
            }
            websiteActivityDataList["totalPlayerData"] = totalPlayerData

            // Previous period comparable counts.
            result["previousNewRegisterCount"] =
                databaseManager.userDao.countOfRegisterByTimeRange(previousPeriodStart, currentPeriodStart, sqlClient)
            // Previous-period "online" proxy: average daily peak online players in the previous
            // period. Falls back to 0 when there is no history yet.
            val previousOnlineHistory =
                databaseManager.onlinePlayerHistoryDao.getByTimeRange(previousPeriodStart, currentPeriodStart, sqlClient)
            val previousOnlineAverage = if (previousOnlineHistory.isEmpty()) {
                0L
            } else {
                previousOnlineHistory.sumOf { it.maxCount } / previousOnlineHistory.size
            }
            result["previousOnlinePlayerCount"] = previousOnlineAverage
            result["previousRegisteredPlayerCount"] = totalAtPeriodStart
        }

        val connectedServerCount = databaseManager.serverDao.countOfPermissionGranted(sqlClient)

        result["connectedServerCount"] = connectedServerCount

        return Successful(result)
    }
}
