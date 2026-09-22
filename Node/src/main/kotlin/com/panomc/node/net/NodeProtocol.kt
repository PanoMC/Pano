package com.panomc.node.net

/**
 * The wire protocol version this daemon speaks.
 *
 * Announced in pairing and again in `NODE_HELLO`, so a Pano newer than the node can degrade rather
 * than refuse it -- the same tolerance Pano already extends to older Minecraft plugins. The number
 * moves only when a message changes shape in a way the other side cannot ignore.
 */
object NodeProtocol {
    /**
     * 3 (SM-63): managed Java runtimes -- `JAVA_CATALOG`, `JAVA_INSTALL`, `JAVA_REMOVE`,
     * `NODE_JAVA_RUNTIMES`, `version`/`managed` on every hello runtime, and the hello's
     * `javaAutoDownload` and `capabilities`.
     *
     * 4 (SM-64): `NODE_UNINSTALL`, the retired marker and exit code 78; `DELETE_SERVER` also removes
     * the server's backups. Pano only sends `NODE_UNINSTALL` to a node that announced at least 4.
     * Also (SM-65): `SET_NODE_METRICS_INTERVAL`, host metrics on a lease-driven cadence.
     * Also (SM-66): `REINSTALL_SERVER`'s `spec.keep` (worlds with a `level.dat`, plugins/mods,
     * config files), the old directory kept as `<uuid>.old-<ts>` until DONE and put back on a
     * failure, and an `INSTALL_SERVER` for a registered server treated as a reinstall.
     *
     * 5: `IMPORT_SERVER` mode `IN_PLACE` (adopt a directory where it is, never copied or deleted),
     * `inPlace` and `directory` on every hello server and on `IMPORT_RESULT`, `IN_PLACE_UNSUPPORTED`
     * for a reinstall of such a server; agent mode (`agent`, `agentServer` on the hello) with its
     * `AGENT_SINGLE_SERVER` refusals. Pano sends `IN_PLACE` only to a node that announced at least 5.
     */
    const val VERSION = 5

    /**
     * Feature flags announced in `NODE_HELLO.capabilities`, for what a protocol number alone
     * cannot say (a node can be new enough and still have a feature switched off by its build).
     */
    object Capabilities {
        /** `JAVA_CATALOG` / `JAVA_INSTALL` / `JAVA_REMOVE` are understood (SM-63). */
        const val JAVA_DOWNLOADS = "java-downloads"

        /** `NODE_UNINSTALL` is understood (SM-64). */
        const val NODE_UNINSTALL = "node-uninstall"

        /** `IMPORT_SERVER` mode `IN_PLACE` is understood (protocol 5). */
        const val IN_PLACE_SERVERS = "in-place-servers"

        val ALL = listOf(JAVA_DOWNLOADS, NODE_UNINSTALL, IN_PLACE_SERVERS)
    }

    /** Inbound message names Pano can push down the socket. */
    object Inbound {
        const val INSTALL_SERVER = "INSTALL_SERVER"
        const val REINSTALL_SERVER = "REINSTALL_SERVER"
        const val IMPORT_SERVER = "IMPORT_SERVER"
        const val INSTALL_PANO_PLUGIN = "INSTALL_PANO_PLUGIN"
        const val POWER = "POWER"
        const val SEND_COMMAND = "SEND_COMMAND"
        const val CONSOLE_STREAM = "CONSOLE_STREAM"

        /**
         * Pano asking for the scrollback this node already holds.
         *
         * A request rather than a push, because the panel is waiting on an HTTP response: a server
         * that crashed while nobody was watching printed its reason into the ring buffer here and
         * nowhere else, and streaming only ever starts once somebody opens the console.
         */
        const val CONSOLE_HISTORY = "CONSOLE_HISTORY"

        /**
         * Pano asking for one page of a search through every log file a server has, not only the
         * window `CONSOLE_HISTORY` can page through. A request with a cursor: the panel keeps
         * asking, page after page, newest file first, until the answer says `done`. A node too old
         * to know it never answers, and Pano's timeout is what tells the panel to fall back to the
         * windowed search.
         */
        const val CONSOLE_SEARCH = "CONSOLE_SEARCH"

        /**
         * Pano asking for one server's process sample at a faster rate while somebody watches it
         * (§2.4.23 A). A lease: it lapses after ninety seconds unless Pano says it again.
         */
        const val SET_METRICS_INTERVAL = "SET_METRICS_INTERVAL"

        /**
         * Pano asking for the host's own `NODE_METRICS` at a faster rate while somebody watches the
         * nodes page (SM-65, §2.4.30). A lease like [SET_METRICS_INTERVAL]'s.
         */
        const val SET_NODE_METRICS_INTERVAL = "SET_NODE_METRICS_INTERVAL"
        const val UPDATE_STARTUP = "UPDATE_STARTUP"
        const val DELETE_SERVER = "DELETE_SERVER"
        const val SELF_UPDATE = "SELF_UPDATE"
        const val INSTALL_PLUGIN = "INSTALL_PLUGIN"
        const val SYNC_SCHEDULES = "SYNC_SCHEDULES"

        /**
         * What this node can read out of a server's `plugins` (or `mods`) directory.
         *
         * A request, like `FILE_LIST`: the panel is waiting on an HTTP response. It is the
         * fallback for a server with no Pano plugin in it, so what comes back is what is on disk
         * rather than what the server has loaded, and Pano prefers the plugin's list where it has
         * one (SM-52, §2.4.17 B).
         */
        const val PLUGIN_SCAN = "PLUGIN_SCAN"

        /**
         * Switch one jar off or on by renaming it, for a server that cannot be asked to do it.
         *
         * A request too, because the panel's toggle has to know whether it worked and the answer
         * carries `restartRequired` either way.
         */
        const val PLUGIN_TOGGLE = "PLUGIN_TOGGLE"

        /**
         * Whether the Pano plugin inside a server is connected to Pano right now.
         *
         * Pushed rather than asked, because only Pano can see it: the plugin's socket lands over
         * there, not here. The node uses it for exactly one decision -- whether to ask a running
         * server for its player list with a server list ping -- so a server whose plugin is
         * connected is never pinged, and a node that has not been told anything assumes it is not
         * (§2.4.17 B).
         */
        const val SERVER_PLUGIN_STATE = "SERVER_PLUGIN_STATE"

        const val FILE_LIST = "FILE_LIST"
        const val FILE_READ = "FILE_READ"
        const val FILE_WRITE = "FILE_WRITE"
        const val FILE_MKDIR = "FILE_MKDIR"
        const val FILE_DELETE = "FILE_DELETE"
        const val FILE_RENAME = "FILE_RENAME"
        const val FILE_ARCHIVE = "FILE_ARCHIVE"
        const val FILE_UNARCHIVE = "FILE_UNARCHIVE"
        const val FILE_CHMOD = "FILE_CHMOD"

        /**
         * The hashes of named jars in one directory, for identifying files nobody recorded.
         *
         * Added in protocol 2, which is why Pano asks only nodes that announced at least that:
         * an older daemon answers `UNKNOWN_OPERATION`, and a panel would rather not offer a
         * button that cannot work than find out one request at a time.
         */
        const val FILE_HASHES = "FILE_HASHES"

        const val TRANSFER_PULL = "TRANSFER_PULL"
        const val TRANSFER_PUSH = "TRANSFER_PUSH"

        const val BACKUP_CREATE = "BACKUP_CREATE"
        const val BACKUP_LIST = "BACKUP_LIST"
        const val BACKUP_RESTORE = "BACKUP_RESTORE"
        const val BACKUP_DELETE = "BACKUP_DELETE"

        /**
         * What Java this node has and could download (SM-63). A request: answered on `FILE_RESULT`
         * with the same `eventId`, like every other request, and never with a failure -- a vendor
         * that cannot be reached empties `downloadable` and says so in `catalogError`.
         */
        const val JAVA_CATALOG = "JAVA_CATALOG"

        /** Install or update one Java major; reported as a `JAVA_INSTALL` task. */
        const val JAVA_INSTALL = "JAVA_INSTALL"

        /** Remove a managed Java major (or one version of it); reported as a `JAVA_REMOVE` task. */
        const val JAVA_REMOVE = "JAVA_REMOVE"

        /**
         * Pano deleted this node: stop everything, delete everything, retire (SM-64). Reported as
         * a `NODE_UNINSTALL` task that ends with `removedBytes` and `manualSteps`.
         */
        const val NODE_UNINSTALL = "NODE_UNINSTALL"

        /** Every `FILE_*` request, so one handler can answer them all. */
        val FILE_OPERATIONS = listOf(
            FILE_LIST,
            FILE_READ,
            FILE_WRITE,
            FILE_MKDIR,
            FILE_DELETE,
            FILE_RENAME,
            FILE_ARCHIVE,
            FILE_UNARCHIVE,
            FILE_CHMOD,
            FILE_HASHES
        )
    }

    /** Outbound event names, matching Pano's NodeEvent class names. */
    object Outbound {
        const val NODE_HELLO = "NODE_HELLO"
        const val NODE_METRICS = "NODE_METRICS"
        const val SERVER_STATE = "SERVER_STATE"
        const val SERVER_CONSOLE_LINES = "SERVER_CONSOLE_LINES"
        const val SERVER_PROCESS_METRICS = "SERVER_PROCESS_METRICS"
        const val TASK_PROGRESS = "TASK_PROGRESS"

        /**
         * What an import turned out to be, sent once the directory is registered and before the
         * import task reports DONE, so Pano can fill the row it created blind.
         */
        const val IMPORT_RESULT = "IMPORT_RESULT"

        /**
         * The reply to any request Pano expects an answer to.
         *
         * One name for every operation rather than one per request: Pano pairs a reply with its
         * request by `eventId`, so the name carries no information it does not already have, and a
         * new request kind then needs nothing on this side but a handler.
         */
        const val FILE_RESULT = "FILE_RESULT"

        /**
         * The node had to bind a different port than Pano asked for (`SERVER_PORT_CHANGED`).
         *
         * Only the node can see what is already listening on its host, so this is the one fact
         * about a managed server's address that Pano cannot work out for itself.
         */
        const val SERVER_PORT_CHANGED = "SERVER_PORT_CHANGED"

        /** A backup that finished, so Pano can write the row it will list it from. */
        const val BACKUP_CREATED = "BACKUP_CREATED"

        /** How one scheduled run went, so Pano can show it and work out the next one. */
        const val SCHEDULE_RUN = "SCHEDULE_RUN"

        /**
         * The Java runtimes on this host, in the hello's shape, after every install, removal and
         * boot clean-up that changed them (SM-63), so Pano's stored copy never lags.
         */
        const val NODE_JAVA_RUNTIMES = "NODE_JAVA_RUNTIMES"
    }
}
