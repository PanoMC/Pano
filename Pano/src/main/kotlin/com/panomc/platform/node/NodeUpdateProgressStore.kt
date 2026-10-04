package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * How far each node (or Pano Agent) is with replacing its own daemon (SM-77).
 *
 * A daemon update is not a server task: there is no row behind it, the node keys its `SELF_UPDATE`
 * progress frames on the version it is installing, and the one fact that says it worked -- the
 * next hello, from the new jar -- arrives on a different connection from the one that asked. Until
 * this existed Pano dropped those frames, so the only person who knew an update was running was
 * the one who pressed the button, and only until the node disconnected. This follows it from the
 * click to the hello so every panel shows the same thing: the node JSON's `updateProgress` (nodes
 * page) and the server JSON's `daemonUpdate` (every server on that node, a Pano Agent's header).
 *
 * One entry per node, a small state machine:
 *
 * ```
 * start ──> RUNNING 0% ──frames──> RUNNING n% ──DONE frame──> RUNNING 100% (staged)
 *              │                       │                              │
 *              │ FAILED frame          │ disconnect                   │ disconnect
 *              v                       v                              v
 *           FAILED                 RESTARTING <───────────────────────┘
 *                                      │ next hello
 *                                      v
 *                        DONE (or FAILED: came back on the old daemon)
 * ```
 *
 * plus the clock, applied by [sweep]: DONE is dropped [DONE_KEEP_MS] after it was reached, FAILED
 * after [FAILED_KEEP_MS], a staged update that never disconnected becomes DONE after
 * [STAGED_RESTART_WAIT_MS] (the node found it already runs that jar), and anything left untouched
 * for [MAX_IDLE_MS] is dropped -- a node that never came back is shown as offline, not as updating
 * forever. A hello while still RUNNING ends it as DONE too, when the node reports the very jar it
 * was sent (its disconnect was missed).
 *
 * In memory and pure: every method takes the time, nothing here pushes or schedules. The pushes and
 * the sweep timer live in [NodeUpdateProgressService]; a restart of Pano forgets every entry, which
 * is right, because every node reconnects and says hello.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeUpdateProgressStore {
    enum class Status {
        RUNNING,
        RESTARTING,
        DONE,
        FAILED
    }

    /** One node's update, as the panel reads it. */
    data class Progress(
        val nodeId: Long,
        val nodeName: String?,
        /** Whether the node is a Pano Agent, which the panel shows as its server, never as a node. */
        val agent: Boolean,
        /** The version being installed; null when the node never said and Pano did not know. */
        val version: String?,
        /** The checksum of the jar that was sent, when Pano sent it; decides a hello while RUNNING. */
        val sha256: String?,
        val status: Status,
        val percent: Int,
        val message: String?,
        /** The node reported the new jar downloaded and verified: what is left is its restart. */
        val staged: Boolean,
        val startedAt: Long,
        val updatedAt: Long
    ) {
        /** The node JSON's `updateProgress`: `{ version, status, percent, message }`. */
        fun toNodeJsonObject(): JsonObject = JsonObject()
            .put("version", version)
            .put("status", status.name)
            .put("percent", percent)
            .put("message", message)

        /**
         * The server JSON's `daemonUpdate`: `{ kind: "agent" | "node", nodeId, nodeName, version,
         * status, percent, message }`.
         */
        fun toServerJsonObject(): JsonObject = JsonObject()
            .put("kind", if (agent) KIND_AGENT else KIND_NODE)
            .put("nodeId", nodeId)
            .put("nodeName", nodeName)
            .put("version", version)
            .put("status", status.name)
            .put("percent", percent)
            .put("message", message)
    }

    private val lock = Any()

    /** nodeId -> its update. Written only under [lock]; read freely. */
    private val entries = ConcurrentHashMap<Long, Progress>()

    /** [nodeId]'s update, or null when it is not updating (or finished a while ago). */
    fun get(nodeId: Long?): Progress? = nodeId?.let { entries[it] }

    fun isEmpty() = entries.isEmpty()

    /**
     * An update of [nodeId] to [version] was just sent (by hand or automatically): RUNNING at 0 %.
     * Replaces whatever was there, a FAILED from an earlier attempt included.
     */
    fun start(
        nodeId: Long,
        nodeName: String?,
        agent: Boolean,
        version: String?,
        sha256: String?,
        now: Long
    ): Progress = synchronized(lock) {
        Progress(
            nodeId = nodeId,
            nodeName = nodeName,
            agent = agent,
            version = version,
            sha256 = sha256,
            status = Status.RUNNING,
            percent = 0,
            message = null,
            staged = false,
            startedAt = now,
            updatedAt = now
        ).also { entries[nodeId] = it }
    }

    /**
     * One `TASK_PROGRESS` frame of kind `SELF_UPDATE` from [nodeId]; [version] is its `taskId`.
     *
     * RUNNING (or PENDING) moves the percentage -- never backwards -- and the message; DONE means
     * staged (100 %, the restart is next); FAILED ends it with the node's error. A frame for an
     * update that already went past RUNNING is late and ignored. A frame with no entry at all (the
     * update was sent before this Pano started) opens one. Returns the entry when it changed, null
     * when the frame changed nothing.
     */
    fun onFrame(
        nodeId: Long,
        nodeName: String?,
        agent: Boolean,
        version: String?,
        status: String?,
        percent: Int?,
        message: String?,
        error: String?,
        now: Long
    ): Progress? = synchronized(lock) {
        val reported = ServerTaskStatus.fromId(status)

        val base = entries[nodeId]
            ?: Progress(
                nodeId = nodeId,
                nodeName = nodeName,
                agent = agent,
                version = version,
                sha256 = null,
                status = Status.RUNNING,
                percent = 0,
                message = null,
                staged = false,
                startedAt = now,
                updatedAt = now
            )

        if (base.status != Status.RUNNING) {
            return null
        }

        val reportedMessage = message?.take(MAX_MESSAGE_LENGTH)

        val next = when (reported) {
            ServerTaskStatus.PENDING, ServerTaskStatus.RUNNING -> {
                // Staged is the end of what the node reports; a RUNNING after it is a stray.
                if (base.staged) {
                    return null
                }

                base.copy(
                    percent = maxOf(base.percent, (percent ?: base.percent).coerceIn(0, 100)),
                    message = reportedMessage ?: base.message
                )
            }

            ServerTaskStatus.DONE -> base.copy(
                percent = 100,
                message = reportedMessage ?: base.message,
                staged = true
            )

            ServerTaskStatus.FAILED -> base.copy(
                status = Status.FAILED,
                message = error?.take(MAX_MESSAGE_LENGTH) ?: reportedMessage ?: base.message ?: DEFAULT_FAILURE
            )

            else -> return null
        }.copy(version = base.version ?: version, updatedAt = now)

        entries[nodeId] = next

        next
    }

    /**
     * [nodeId]'s connection closed. While it is RUNNING -- staged or not -- that is the daemon
     * exiting to swap the jar in, so the entry says RESTARTING. Null when nothing changed.
     */
    fun onDisconnect(nodeId: Long, now: Long): Progress? = synchronized(lock) {
        val current = entries[nodeId]?.takeIf { it.status == Status.RUNNING } ?: return null

        current.copy(status = Status.RESTARTING, message = null, updatedAt = now)
            .also { entries[nodeId] = it }
    }

    /**
     * [nodeId] said hello, reporting [version] and [jarSha256]. After RESTARTING that is the new
     * daemon: DONE -- unless both the checksum and the version it reports are known and are not
     * what was sent, which is a daemon that came back on its old jar (FAILED). While still RUNNING
     * only a hello from exactly the jar that was sent ends it (DONE): the disconnect was missed.
     * Null when nothing changed.
     */
    fun onHello(nodeId: Long, version: String?, jarSha256: String?, now: Long): Progress? = synchronized(lock) {
        val current = entries[nodeId] ?: return null

        val next = when (current.status) {
            Status.RESTARTING -> if (cameBackOnOldDaemon(current, version, jarSha256)) {
                current.copy(status = Status.FAILED, message = OLD_DAEMON_MESSAGE, updatedAt = now)
            } else {
                done(current, now)
            }

            Status.RUNNING -> if (isSentJar(current, jarSha256)) done(current, now) else return null

            else -> return null
        }

        entries[nodeId] = next

        next
    }

    /**
     * Applies the clock (see the class comment) and returns the nodes whose entry changed or went
     * away, so the caller can tell the panels.
     */
    fun sweep(now: Long): Set<Long> = synchronized(lock) {
        val changed = mutableSetOf<Long>()

        entries.values.toList().forEach { entry ->
            val idle = now - entry.updatedAt

            when {
                idle >= MAX_IDLE_MS -> entries.remove(entry.nodeId)

                entry.status == Status.DONE && idle >= DONE_KEEP_MS -> entries.remove(entry.nodeId)

                entry.status == Status.FAILED && idle >= FAILED_KEEP_MS -> entries.remove(entry.nodeId)

                entry.status == Status.RUNNING && entry.staged && idle >= STAGED_RESTART_WAIT_MS ->
                    entries[entry.nodeId] = done(entry, now)

                else -> return@forEach
            }

            changed.add(entry.nodeId)
        }

        changed
    }

    /** Forgets [nodeId]; for a node that was deleted. */
    fun forget(nodeId: Long) {
        synchronized(lock) {
            entries.remove(nodeId)
        }
    }

    private fun done(entry: Progress, now: Long) =
        entry.copy(status = Status.DONE, percent = 100, message = null, updatedAt = now)

    companion object {
        const val KIND_AGENT = "agent"
        const val KIND_NODE = "node"

        /** How long DONE stays on show ("Updated to x") before the entry goes. */
        const val DONE_KEEP_MS = 10_000L

        /** How long FAILED stays on show. */
        const val FAILED_KEEP_MS = 60_000L

        /**
         * How long a staged update may go without the node disconnecting before it counts as DONE.
         * A node exits a second after staging; one that does not found it already runs that jar.
         */
        const val STAGED_RESTART_WAIT_MS = 2 * 60_000L

        /** An entry nothing has touched for this long is dropped, whatever it says. */
        const val MAX_IDLE_MS = 10 * 60_000L

        /** FAILED's message for a daemon whose hello says it still runs the old jar. */
        const val OLD_DAEMON_MESSAGE = "The daemon restarted on its old version."

        private const val DEFAULT_FAILURE = "FAILED"

        private const val MAX_MESSAGE_LENGTH = 2000

        /** Whether [jarSha256] is the jar that was sent; false when either side is unknown. */
        fun isSentJar(entry: Progress, jarSha256: String?): Boolean {
            val sent = entry.sha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val reported = jarSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false

            return sent.equals(reported, ignoreCase = true)
        }

        /**
         * Whether a hello after RESTARTING proves the old daemon came back: its checksum and its
         * version are both known and both differ from what was sent. Anything short of that is
         * taken as the new one -- development builds all share one version, and an older node
         * reports no checksum.
         */
        fun cameBackOnOldDaemon(entry: Progress, version: String?, jarSha256: String?): Boolean {
            val sent = entry.sha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val reported = jarSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val sentVersion = entry.version?.takeIf { it.isNotBlank() } ?: return false
            val reportedVersion = version?.takeIf { it.isNotBlank() } ?: return false

            return !sent.equals(reported, ignoreCase = true) && sentVersion != reportedVersion
        }
    }
}
