package com.panomc.platform.util

enum class DashboardPeriodType(val period: String, val value: Int) {
    WEEK("week", 1),
    MONTH("month", 2);

    override fun toString(): String {
        return period
    }

    companion object {
        fun valueOf(period: String) = DashboardPeriodType.values().find { it.period == period }

        fun valueOf(value: Int) = DashboardPeriodType.values().find { it.value == value }
    }
}