package com.panomc.platform.server

import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Why each managed server last stopped, when the node said (SM-63, §2.4.28).
 *
 * The reason used to travel on the `serverState` frame and nowhere else, which was enough for a
 * crash — the crash notification repeats it — and not enough for a start that never happened: a
 * start refused for want of Java ends STOPPED, sends no notification, and a panel opened a minute
 * later had no way to offer "Download Java 21 and start". This keeps the last one per server and
 * [com.panomc.platform.server.feature.ServerFeatureResolver] puts it on the server JSON as
 * `lastStopReason`.
 *
 * In memory on purpose: it describes the last transition the node reported to *this* process, and
 * a node reports every server's state again on its next hello anyway. Nothing is lost by a restart
 * that the next start attempt would not say again.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerStopReasonStore {
    /** One stop, as the panel reads it. */
    data class StopReason(
        val reason: String?,
        /** Machine-readable cause, e.g. [REASON_JAVA_MISSING]; null when the node gave none. */
        val reasonCode: String?,
        /** The Java major the start needed, sent with [REASON_JAVA_MISSING]. */
        val javaMajor: Int?,
        val at: Long
    ) {
        fun toJsonObject(): JsonObject = JsonObject()
            .put("reason", reason)
            .put("reasonCode", reasonCode)
            .put("javaMajor", javaMajor)
            .put("at", at)
    }

    private val reasons = ConcurrentHashMap<Long, StopReason>()

    /** Records [state] for [serverId]: keeps a reason for a stop that has one, forgets it otherwise. */
    fun onState(
        serverId: Long,
        state: ServerProcessState,
        reason: String?,
        reasonCode: String?,
        javaMajor: Int?,
        now: Long = System.currentTimeMillis()
    ) {
        val next = resolve(state, reason, reasonCode, javaMajor, now)

        if (next == null) {
            reasons.remove(serverId)
        } else {
            reasons[serverId] = next
        }
    }

    fun get(serverId: Long): StopReason? = reasons[serverId]

    fun remove(serverId: Long) {
        reasons.remove(serverId)
    }

    companion object {
        /** The node could not find (or download) the Java a start needed. */
        const val REASON_JAVA_MISSING = "JAVA_MISSING"

        private val REASON_CODE_PATTERN = Regex("^[A-Z0-9_]{1,32}$")

        private val JAVA_MAJOR_RANGE = 1..99

        /**
         * What a transition to [state] leaves behind, or null for nothing.
         *
         * Any state other than STOPPED or CRASHED clears it: once a server is starting again, the
         * reason it did not start last time is no longer the thing to show. A stop without a
         * reason clears it as well — somebody pressed Stop, and there is nothing to explain.
         * Everything the node sent is untrusted, so a code that is not a plain constant and a
         * major outside `1..99` are dropped rather than passed to the panel.
         */
        fun resolve(
            state: ServerProcessState,
            reason: String?,
            reasonCode: String?,
            javaMajor: Int?,
            now: Long
        ): StopReason? {
            if (state != ServerProcessState.STOPPED && state != ServerProcessState.CRASHED) {
                return null
            }

            val code = cleanCode(reasonCode)
            val major = cleanMajor(javaMajor)

            if (reason == null && code == null) {
                return null
            }

            return StopReason(reason = reason, reasonCode = code, javaMajor = major, at = now)
        }

        /** [reasonCode] as it may go out on a frame: a plain constant, or nothing. */
        fun cleanCode(reasonCode: String?): String? = reasonCode?.trim()?.takeIf { REASON_CODE_PATTERN.matches(it) }

        fun cleanMajor(javaMajor: Int?): Int? = javaMajor?.takeIf { it in JAVA_MAJOR_RANGE }
    }
}
