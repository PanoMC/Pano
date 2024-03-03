package com.panomc.platform.util

import java.lang.management.ManagementFactory
import java.util.*


object TimeUtil {
    private fun secondsWithPrecision(time: Long): Double = time / 1000.0

    private fun calculateStartTime() = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun getStartupTime() = secondsWithPrecision(calculateStartTime())

    fun getCalendarOfToday(): Calendar {
        val calendar = Calendar.getInstance()

        calendar[Calendar.HOUR_OF_DAY] = 0 // ! clear would not reset the hour of day !

        calendar.firstDayOfWeek = Calendar.MONDAY

        calendar.clear(Calendar.MINUTE)
        calendar.clear(Calendar.SECOND)
        calendar.clear(Calendar.MILLISECOND)

        return calendar
    }

    fun getStartOfWeekInMillis(): Long {
        val calendar = getCalendarOfToday()

        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)

        return calendar.timeInMillis
    }

    fun getStartOfMonthInMillis(): Long {
        val calendar = getCalendarOfToday()

        calendar.set(Calendar.DAY_OF_MONTH, 1)

        return calendar.timeInMillis
    }


    fun getTimeToCompareByDashboardPeriodType(dashboardPeriodType: DashboardPeriodType) =
        if (dashboardPeriodType == DashboardPeriodType.WEEK) {
            getStartOfWeekInMillis()
        } else {
            getStartOfMonthInMillis()
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