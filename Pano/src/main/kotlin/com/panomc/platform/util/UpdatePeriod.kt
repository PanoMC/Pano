package com.panomc.platform.util

enum class UpdatePeriod(val period: String, val value: Int) {
    NEVER("never", 0),
    ONCE_PER_DAY("oncePerDay", 1),
    ONCE_PER_WEEK("oncePerWeek", 2),
    ONCE_PER_MONTH("oncePerMonth", 3);

    override fun toString(): String {
        return period
    }

    companion object {
        fun valueOf(period: String?) = values().find { it.period == period }
        fun valueOf(value: Int) = values().find { it.value == value }
    }
}