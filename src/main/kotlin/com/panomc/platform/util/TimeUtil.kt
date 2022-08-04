package com.panomc.platform.util

import java.lang.management.ManagementFactory
import java.util.*
import java.util.concurrent.TimeUnit


object TimeUtil {
    private fun secondsWithPrecision(time: Long): Double = time / 1000.0

    private fun calculateStartTime() = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun getStartupTime() = secondsWithPrecision(calculateStartTime())

    fun getTimeToCompareByDashboardPeriodType(dashboardPeriodType: DashboardPeriodType) =
        if (dashboardPeriodType == DashboardPeriodType.WEEKLY) {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        } else {
            val calendar = Calendar.getInstance()

            calendar.add(Calendar.MONTH, -1)

            calendar.timeInMillis
        }

    fun List<Long>.toGroupGetCountAndDates() = this.map { time ->
        val calendar = Calendar.getInstance()

        calendar.timeInMillis = time

        calendar[Calendar.HOUR_OF_DAY] = 0
        calendar[Calendar.MINUTE] = 0
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0

        calendar.timeInMillis
    }.groupingBy { it }.eachCount()
}