package com.panomc.platform.server.event

import com.panomc.platform.annotation.Event
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.ServerTaskKind
import com.panomc.platform.node.ServerTaskStatus
import com.panomc.platform.node.event.request.TaskProgressEventRequest
import com.panomc.platform.panel.PanelRealtimeHub
import com.panomc.platform.server.ServerEvent
import com.panomc.platform.server.ServerEventResponse
import com.panomc.platform.server.event.request.PanoPluginUpdateResultEventRequest
import org.slf4j.Logger

/**
 * `PANO_PLUGIN_UPDATE_RESULT`: the plugin has staged its successor, or has given up on it.
 *
 * The plugin also reports the end of the job as an ordinary terminal `TASK_PROGRESS`, and whichever
 * of the two arrives first is the one that ends the task: both are handed to the same
 * implementation, whose transition rules drop the second as a duplicate. The result exists
 * because it is the one frame that says *what* is now waiting for the restart — the version and
 * the mechanism — and because a plugin whose progress frame was lost on a reconnect must still be
 * able to close its task.
 *
 * No reply. The task row is what the panel follows, and a staged update changes nothing about the
 * running server until it restarts.
 */
@Event
class PanoPluginUpdateResultEvent(
    private val nodeTaskProgressEvent: com.panomc.platform.node.event.TaskProgressEvent,
    private val panelRealtimeHub: PanelRealtimeHub,
    private val logger: Logger
) : ServerEvent<PanoPluginUpdateResultEventRequest, ServerEventResponse>() {
    override suspend fun handle(request: PanoPluginUpdateResultEventRequest, server: Server): ServerEventResponse? {
        val taskId = request.taskId?.takeIf { it.isNotBlank() } ?: return null
        val ok = request.ok == true

        if (!ok) {
            logger.warn("The Pano plugin on server ${server.id} could not stage its update: ${request.error ?: "no reason given"}")
        }

        // The plugin's own ownership rule applies here as everywhere: a task that is not this
        // server's is ignored by handleFromServer, whoever claims it.
        nodeTaskProgressEvent.handleFromServer(
            TaskProgressEventRequest(
                taskId = taskId,
                serverUuid = server.uuid,
                kind = ServerTaskKind.PLUGIN_INSTALL.name,
                status = if (ok) ServerTaskStatus.DONE.name else ServerTaskStatus.FAILED.name,
                percent = 100,
                message = if (ok) describe(request) else null,
                error = if (ok) null else (request.error?.take(MAX_ERROR_LENGTH) ?: DEFAULT_ERROR)
            ),
            server.id
        )

        panelRealtimeHub.notifyServerUpdated(server.id)

        return null
    }

    private fun describe(request: PanoPluginUpdateResultEventRequest): String {
        // The plugin found it is already running these exact bytes and staged nothing.
        if (request.mode == MODE_UP_TO_DATE) {
            return "The Pano plugin is already running this build"
        }

        val version = request.stagedVersion?.takeIf { it.isNotBlank() }?.take(MAX_VERSION_LENGTH)

        return if (version == null) {
            "The Pano plugin update is staged; restart the server to load it"
        } else {
            "Pano $version is staged; restart the server to load it"
        }
    }

    companion object {
        /** `mode` of a result for an update that turned out to be the build already running. */
        const val MODE_UP_TO_DATE = "up-to-date"

        /** `mode` of a result staged into Bukkit's `plugins/update/`, applied by the server at boot. */
        const val MODE_UPDATE_FOLDER = "update-folder"

        /** `mode` of a result the plugin swaps in itself as the server stops. */
        const val MODE_SWAP_ON_SHUTDOWN = "swap-on-shutdown"

        private const val DEFAULT_ERROR = "FAILED"
        private const val MAX_ERROR_LENGTH = 2000
        private const val MAX_VERSION_LENGTH = 64
    }
}
