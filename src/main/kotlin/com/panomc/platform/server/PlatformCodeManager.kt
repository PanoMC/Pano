package com.panomc.platform.server

import io.vertx.core.Vertx
import java.util.*

class PlatformCodeManager(
    vertx: Vertx
) {
    private var mPlatformKey = 0
    private var mStartedTime = 0L

    init {
        generateCode()

        vertx.setPeriodic(1000 * 30) { // 30 seconds to refresh the key
            generateCode()
        }
    }

    fun getPlatformKey() = mPlatformKey
    fun getTimeStarted() = mStartedTime

    private fun generatePlatformCode() = Random().nextInt(900000) + 100000

    private fun generateCode() {
        mPlatformKey = generatePlatformCode()
        mStartedTime = Date().time
    }
}