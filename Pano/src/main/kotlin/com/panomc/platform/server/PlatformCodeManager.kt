package com.panomc.platform.server

import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PlatformCodeManager(
    vertx: Vertx
) {
    private var mPlatformKey = 0
    private var mStartedTime = 0L

    /**
     * Called after every rotation, e.g. by the panel hub to push the new key to the open connect
     * dialogs instead of having them poll for it.
     */
    private val rotationListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        generateCode()

        vertx.setPeriodic(ROTATE_INTERVAL_MS) {
            rotate()
        }
    }

    fun getPlatformKey() = mPlatformKey
    fun getTimeStarted() = mStartedTime

    fun addRotationListener(listener: () -> Unit) {
        rotationListeners.add(listener)
    }

    internal fun rotate() {
        generateCode()

        rotationListeners.forEach {
            try {
                it()
            } catch (_: Exception) {
            }
        }
    }

    private fun generatePlatformCode() = Random().nextInt(900000) + 100000

    private fun generateCode() {
        mPlatformKey = generatePlatformCode()
        mStartedTime = Date().time
    }

    companion object {
        /** How long one key is shown before the next one replaces it. */
        const val ROTATE_INTERVAL_MS = 30_000L
    }
}
