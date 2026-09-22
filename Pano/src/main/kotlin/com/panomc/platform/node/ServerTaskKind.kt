package com.panomc.platform.node

/**
 * The long-running jobs a node performs for one server (or for itself).
 *
 * A task exists because these outlive a single request: an install downloads hundreds of
 * megabytes and a backup can run for minutes, so the panel gets a task id immediately and follows
 * `TASK_PROGRESS` frames instead of holding a connection open.
 */
enum class ServerTaskKind {
    INSTALL,
    REINSTALL,
    DELETE,
    BACKUP,
    RESTORE,
    IMPORT,
    SELF_UPDATE,

    /** One plugin or mod jar being downloaded into a managed server's plugin directory. */
    PLUGIN_INSTALL,

    /**
     * Pano installing the daemon on a host that is not a node yet.
     *
     * The odd one out: it belongs to no server and to no node — there is nothing to attach it to
     * until the daemon it installs pairs — so it is the reason a task's `nodeId` is nullable.
     */
    NODE_BOOTSTRAP,

    /**
     * A Java runtime being downloaded onto a node (SM-63, §2.4.28).
     *
     * Node-scoped: `serverId` is null when an admin asked for it from the node's Java card. The
     * one exception is the node deciding on its own that a start needs a Java it does not have —
     * then the node opens the task itself, with the server's uuid on it, and Pano learns of it from
     * the first progress frame (see [mayBeOpenedByNode]).
     */
    JAVA_INSTALL,

    /** A managed Java runtime being deleted from a node. Node-scoped, never has a server. */
    JAVA_REMOVE,

    /**
     * A node removing itself from its host because it is being deleted (SM-64, §2.4.29 B): every
     * server stopped and deleted, backups, Java runtimes and caches gone, the service removed or
     * the commands for it handed back. Node-scoped; the delete request waits for it.
     */
    NODE_UNINSTALL;

    /**
     * Whether a node may report progress for a task of this kind that Pano never created.
     *
     * Every other kind is Pano's to open, and a frame for an unknown task id of those kinds is
     * dropped: accepting it would let a node write arbitrary rows into the task list. A Java
     * download triggered by a start is the one job the node starts without being told to.
     */
    val mayBeOpenedByNode: Boolean
        get() = this == JAVA_INSTALL

    companion object {
        fun fromId(id: String?): ServerTaskKind? = entries.firstOrNull { it.name == id?.uppercase() }
    }
}
