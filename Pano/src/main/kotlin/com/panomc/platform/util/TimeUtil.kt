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

    fun getStartOfLastWeekAtMidnightInMillis(): Long {
        val oneWeekAgo = LocalDate.now(ZoneId.systemDefault()).minusWeeks(1).minusDays(1)
        return oneWeekAgo
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun getStartOfLastMonthAtMidnightInMillis(): Long {
        val oneMonthAgo = LocalDate.now(ZoneId.systemDefault()).minusMonths(1).minusDays(1)
        return oneMonthAgo
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun getTimeToCompareByDashboardPeriodType(dashboardPeriodType: DashboardPeriodType) =
        if (dashboardPeriodType == DashboardPeriodType.WEEK) {
            getStartOfLastWeekAtMidnightInMillis()
        } else {
            getStartOfLastMonthAtMidnightInMillis()
        }

    /**
     * Returns the start of the previous equivalent period relative to the current period start.
     * For WEEK: returns the start of the week before the current week window.
     * For MONTH: returns the start of the month before the current month window.
     */
    fun getPreviousPeriodStart(dashboardPeriodType: DashboardPeriodType): Long {
        val currentStart = getTimeToCompareByDashboardPeriodType(dashboardPeriodType)

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = currentStart

        if (dashboardPeriodType == DashboardPeriodType.WEEK) {
            calendar.add(Calendar.DAY_OF_YEAR, -7)
        } else {
            calendar.add(Calendar.DAY_OF_YEAR, -30)
        }

        return calendar.timeInMillis
    }

    /**
     * Rounds a timestamp down to the start of its day (00:00:00.000) in the system timezone.
     */
    fun startOfDay(time: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time
        calendar[Calendar.HOUR_OF_DAY] = 0
        calendar[Calendar.MINUTE] = 0
        calendar[Calendar.SECOND] = 0
        calendar[Calendar.MILLISECOND] = 0
        return calendar.timeInMillis
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