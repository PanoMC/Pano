package com.panomc.platform.server

import com.panomc.platform.db.model.ServerTask
import com.panomc.platform.node.ServerTaskStatus
import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The task each server is busy with right now, as the server JSON's `activeTask` (SM-68, §2.4.33).
 *
 * A BuildTools compile runs for five to ten minutes, and until this existed the only place that
 * knew was the server detail header of the person who pressed the button: the Servers modal and
 * the node page showed a plain "Offline" server for the whole build. Every task update already
 * passes through [com.panomc.platform.panel.PanelRealtimeHub.pushTaskProgress], so that is where
 * this is fed, and [com.panomc.platform.server.feature.ServerFeatureResolver] puts the newest one
 * on every server JSON.
 *
 * In memory, like [ServerStopReasonStore]: the unfinished rows are read back once when the task
 * sweep starts ([seed]), so a restart of Pano in the middle of a build loses nothing the next
 * progress frame would not have said anyway.
 *
 * `panoPluginUpdate` (SM-77) tells the panel that a `PLUGIN_INSTALL` is the Pano plugin replacing
 * itself, so the header can say "Updating the Pano plugin" instead of "Installing a plugin". The
 * task kind cannot tell and the node overwrites the message with its own, so
 * `PanoPluginUpdateService` marks the task here ([markPanoPluginUpdate]) before its first push.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerActiveTaskStore {
    /** One running task, as the panel reads it. */
    data class ActiveTask(
        val id: Long,
        val uuid: String,
        val kind: String,
        val status: String,
        val percent: Int,
        val message: String?,
        val startedAt: Long,
        /** Whether this is an update of the Pano plugin itself; see [markPanoPluginUpdate]. */
        val panoPluginUpdate: Boolean = false
    ) {
        fun toJsonObject(): JsonObject = JsonObject()
            .put("id", id)
            .put("uuid", uuid)
            .put("kind", kind)
            .put("status", status)
            .put("percent", percent)
            .put("message", message)
            .put("startedAt", startedAt)
            .put("panoPluginUpdate", panoPluginUpdate)
    }

    /** serverId -> (task uuid -> task); more than one only while tasks overlap. */
    private val tasks = ConcurrentHashMap<Long, ConcurrentHashMap<String, ActiveTask>>()

    /**
     * Task uuids that ended, with when, so a [seed] that read the table before a task finished
     * cannot put it back afterwards. Pruned on every write; minutes are plenty.
     */
    private val ended = ConcurrentHashMap<String, Long>()

    /** Uuids of the tasks that update the Pano plugin, to when they were marked. */
    private val panoPluginUpdates = ConcurrentHashMap<String, Long>()

    /**
     * Marks the task [uuid] as an update of the Pano plugin, so its `activeTask` carries
     * `panoPluginUpdate: true` from its first push to its last. Forgotten when the task ends.
     */
    fun markPanoPluginUpdate(uuid: String, now: Long = System.currentTimeMillis()) {
        panoPluginUpdates[uuid] = now
    }

    /** Whether the task [uuid] was marked with [markPanoPluginUpdate] and has not ended yet. */
    fun isPanoPluginUpdate(uuid: String): Boolean = panoPluginUpdates.containsKey(uuid)

    /** Records the state [task] was just written in: kept while it runs, forgotten once it ends. */
    fun onTask(task: ServerTask, now: Long = System.currentTimeMillis()) {
        val serverId = task.serverId ?: return

        prune(now)

        if (!isActive(task.status)) {
            ended[task.uuid] = now

            panoPluginUpdates.remove(task.uuid)

            forget(serverId, task.uuid)

            return
        }

        if (ended.containsKey(task.uuid)) {
            return
        }

        tasks.computeIfAbsent(serverId) { ConcurrentHashMap() }[task.uuid] = of(task)
    }

    /**
     * Fills in the unfinished tasks read from the table, without overwriting anything a frame
     * already said: a live frame is always newer than a row read at boot.
     */
    fun seed(unfinished: List<ServerTask>, now: Long = System.currentTimeMillis()) {
        prune(now)

        unfinished
            .filter { it.serverId != null && isActive(it.status) && !ended.containsKey(it.uuid) }
            .forEach { task ->
                // After a restart the mark is gone; a row that still carries the message the
                // update was opened with is recognised by it, and stays marked from here on.
                if (isPanoPluginUpdateMessage(task.message)) {
                    panoPluginUpdates.putIfAbsent(task.uuid, now)
                }

                tasks.computeIfAbsent(task.serverId!!) { ConcurrentHashMap() }.putIfAbsent(task.uuid, of(task))
            }
    }

    /** The newest task [serverId] is busy with, or null. */
    fun get(serverId: Long): ActiveTask? =
        tasks[serverId]?.values?.maxWithOrNull(compareBy<ActiveTask> { it.startedAt }.thenBy { it.id })

    fun remove(serverId: Long) {
        tasks.remove(serverId)
    }

    private fun forget(serverId: Long, uuid: String) {
        tasks.computeIfPresent(serverId) { _, byUuid ->
            byUuid.remove(uuid)

            byUuid.takeIf { it.isNotEmpty() }
        }
    }

    private fun of(task: ServerTask) = ActiveTask(
        id = task.id,
        uuid = task.uuid,
        kind = task.kind.name,
        status = task.status.name,
        percent = task.percent,
        message = task.message,
        startedAt = task.createdAt,
        panoPluginUpdate = panoPluginUpdates.containsKey(task.uuid)
    )

    private fun prune(now: Long) {
        ended.entries.removeIf { now - it.value > ENDED_MEMORY_MS }

        // A mark whose task never reached this store as ended (its row was deleted with its node).
        panoPluginUpdates.entries.removeIf { now - it.value > PANO_PLUGIN_UPDATE_MEMORY_MS }
    }

    companion object {
        private const val ENDED_MEMORY_MS = 10 * 60_000L

        /** Far longer than any Pano plugin update runs; the timeout sweep fails one after minutes. */
        private const val PANO_PLUGIN_UPDATE_MEMORY_MS = 6 * 60 * 60_000L

        /** How `PanoPluginUpdateService` opens its tasks' message; see [seed]. */
        const val PANO_PLUGIN_UPDATE_MESSAGE_PREFIX = "Updating the Pano plugin"

        fun isPanoPluginUpdateMessage(message: String?) =
            message?.startsWith(PANO_PLUGIN_UPDATE_MESSAGE_PREFIX) == true

        /**
         * Whether a task in [status] shows as busy. PENDING_RESTART does not: a restore waiting
         * for the next start can wait for weeks, and a progress bar that sits there that long is a
         * lie about something happening.
         */
        fun isActive(status: ServerTaskStatus) =
            status == ServerTaskStatus.PENDING || status == ServerTaskStatus.RUNNING
    }
}
