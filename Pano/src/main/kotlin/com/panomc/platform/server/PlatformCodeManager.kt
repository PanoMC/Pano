package com.panomc.platform.server

import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.*

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
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