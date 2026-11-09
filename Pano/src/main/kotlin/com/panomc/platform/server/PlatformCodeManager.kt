package com.panomc.platform.server

import io.vertx.core.Vertx
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
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

    private fun generatePlatformCode() = secureRandom.nextInt(900000) + 100000

    private var failureWindowStart = 0L
    private var failures = 0

    /**
     * Checks a code sent by a Minecraft server. Wrong codes are counted for the whole platform, not per address:
     * after [MAX_FAILURES] wrong codes within [FAILURE_WINDOW_MS] every attempt is refused until the window ends,
     * so the 6-digit code cannot be guessed from many addresses at once.
     */
    @Synchronized
    fun verify(code: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (now - failureWindowStart >= FAILURE_WINDOW_MS) {
            failureWindowStart = now
            failures = 0
        }

        if (failures >= MAX_FAILURES) {
            return false
        }

        val matches = code != null && MessageDigest.isEqual(
            code.toByteArray(Charsets.UTF_8),
            mPlatformKey.toString().toByteArray(Charsets.UTF_8)
        )

        if (!matches) {
            failures++
        }

        return matches
    }

    private fun generateCode() {
        mPlatformKey = generatePlatformCode()
        mStartedTime = Date().time
    }

    companion object {
        /** How long one key is shown before the next one replaces it. */
        const val ROTATE_INTERVAL_MS = 30_000L

        const val MAX_FAILURES = 20
        const val FAILURE_WINDOW_MS = 10 * 60_000L

        private val secureRandom = SecureRandom()
    }
}
