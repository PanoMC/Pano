package com.panomc.platform.util

import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*


object TimeUtil {
    private fun secondsWithPrecision(time: Long): Double = time / 1000.0

    private fun calculateStartTime() = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun getStartupTime() = secondsWithPrecision(calculateStartTime())

    fun getStartOfLast2WeekAtMidnightInMillis(): Long {
        val oneWeekAgo = LocalDate.now(ZoneId.systemDefault()).minusWeeks(2)
        return oneWeekAgo
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun getStartOfLastMonthAtMidnightInMillis(): Long {
        val oneMonthAgo = LocalDate.now(ZoneId.systemDefault()).minusMonths(2)
        return oneMonthAgo
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun getTimeToCompareByDashboardPeriodType(dashboardPeriodType: DashboardPeriodType) =
        if (dashboardPeriodType == DashboardPeriodType.WEEK) {
            getStartOfLast2WeekAtMidnightInMillis()
        } else {
            getStartOfLastMonthAtMidnightInMillis()
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

    fun getCurrentTimeStamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
}