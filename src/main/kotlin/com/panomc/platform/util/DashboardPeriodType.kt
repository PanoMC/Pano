package com.panomc.platform.util

enum class DashboardPeriodType(val period: String, val value: Int) {
    WEEKLY("weekly", 1),
    MONTHLY("monthly", 2);

    override fun toString(): String {
        return period
    }

    companion object {
        fun valueOf(period: String) = DashboardPeriodType.values().find { it.period == period }

        fun valueOf(value: Int) = DashboardPeriodType.values().find { it.value == value }
    }
}