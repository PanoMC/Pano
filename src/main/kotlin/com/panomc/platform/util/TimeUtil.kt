package com.panomc.platform.util

import java.lang.management.ManagementFactory


object TimeUtil {
    private fun secondsWithPrecision(time: Long): Double = time / 1000.0

    private fun calculateStartTime() = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime

    fun getStartupTime() = secondsWithPrecision(calculateStartTime())
}