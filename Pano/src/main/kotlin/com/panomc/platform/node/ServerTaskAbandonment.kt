package com.panomc.platform.node

import com.panomc.platform.db.model.ServerTask

/**
 * Which of a node's tasks it gave up without saying so, judged from its hello.
 *
 * A node lists the tasks it is working on in every hello (`runningTasks`); a daemon that just
 * started lists none. A task Pano still has RUNNING on that node and the node does not list is one
 * no process there is working on: the daemon restarted under it (an update, a crash, a service
 * restart) and the FAILED frame never made it out. Before this, such a row stayed RUNNING until
 * the ten-minute sweep ([ServerTaskTimeout]) and the panel showed a spinner for it.
 *
 * Deliberately narrow:
 *
 * - only RUNNING. A PENDING task may have been sent on this very socket and not be picked up yet,
 *   and PENDING_RESTART is a restore armed for the server's next start, which a node restart
 *   leaves armed;
 * - only rows last touched before the hello. The node built its list when it sent the hello, so a
 *   task it started after that (its first RUNNING frame can be handled while the hello still is)
 *   is not in the list and is not abandoned either;
 * - nothing for a node too old to send the list (null), which is left to the sweep.
 *
 * A task that finished while the socket was down is failed here too, since its DONE frame was
 * dropped with the socket: the sweep would have failed it as well, only ten minutes later.
 */
object ServerTaskAbandonment {
    /** Written into the task's `error`; the panel shows it as it is. */
    const val NODE_RESTARTED_ERROR = "Interrupted: the node restarted before this task finished."

    fun isAbandoned(task: ServerTask, nodeId: Long, runningOnNode: Set<String>, helloAt: Long): Boolean =
        task.nodeId == nodeId &&
            task.status == ServerTaskStatus.RUNNING &&
            task.uuid !in runningOnNode &&
            task.updatedAt < helloAt
}
