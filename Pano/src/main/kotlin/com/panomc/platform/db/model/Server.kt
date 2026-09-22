package com.panomc.platform.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonObject

data class Server(
    val id: Long = -1,
    var name: String,
    var motd: String,
    var host: String,
    var remoteAddress: String? = null,
    var port: Int,
    var playerCount: Long,
    var maxPlayerCount: Long,
    var type: ServerType,
    var version: String,
    var favicon: String,
    val permissionGranted: Boolean = false,
    var status: ServerStatus,
    val addedTime: Long = System.currentTimeMillis(),
    val acceptedTime: Long = 0,
    var startTime: Long,
    val stopTime: Long = 0,
    val aesKey: String,
    var settings: ServerSettings = ServerSettings(),
    var customName: String? = null,
    /**
     * Protocol version announced by the connected plugin. Plugins older than the capability
     * handshake announce nothing, so they stay on [ServerProtocol.LEGACY_PROTOCOL_VERSION].
     */
    var protocolVersion: Int = ServerProtocol.LEGACY_PROTOCOL_VERSION,
    /** Version of the Pano plugin running on the server, `null` when it never announced one. */
    var pluginVersion: String? = null,
    /** Raw capability ids announced by the plugin, see [ServerCapability]. */
    var capabilities: List<String> = emptyList(),
    /**
     * Whether Pano owns this server's process through a node, or only talks to the plugin inside
     * a server someone else started. Everything below this line is meaningful for
     * [ServerKind.MANAGED] only.
     */
    var kind: ServerKind = ServerKind.LINKED,
    /** Node that owns the process, `null` for a linked server. */
    var nodeId: Long? = null,
    /**
     * Stable id the node knows this server by, and the name of its directory on that node.
     *
     * Every row has one, including linked servers: it is filled in for existing rows by the
     * migration so nothing has to special-case a server that predates managed servers, and it is
     * the only id node traffic is ever allowed to address a server with.
     */
    var uuid: String? = null,
    /** Software the node installed, as a [ServerType] name. Null for linked servers. */
    var software: String? = null,
    var softwareVersion: String? = null,
    var javaVersion: Int? = null,
    var memoryMb: Int? = null,
    /** Extra JVM flags, stored as a JSON array exactly like [capabilities]. */
    var jvmArgs: List<String> = emptyList(),
    /**
     * `server.properties` entries Pano manages, stored as a JSON object in a text column.
     *
     * Only the keys the panel offers ever land here; the node merges them into the real file and
     * leaves everything else in it alone, so a hand-edited `server.properties` is not overwritten
     * by a panel that has never heard of half of it.
     */
    var properties: Map<String, String> = emptyMap(),
    /** Port the node told the server to listen on, which may differ from the announced [port]. */
    var gamePort: Int? = null,
    var autoStart: Boolean = false,
    var crashRestart: Boolean = true,
    /** Exit code of the last run, so a crash can be explained after the fact. */
    var lastExitCode: Int? = null,
    /** Process lifecycle reported by the node; `null` while no node owns this server. */
    var processState: ServerProcessState? = null,
    /**
     * Whether the node inherited this server's process from a daemon that is gone (SM-51,
     * §2.4.16).
     *
     * A server that was running when its node restarted is adopted rather than reported stopped,
     * and the panel says so in the header: the process is real and supervised, but it was not
     * started by the daemon that is now watching it, and the next restart is what makes it an
     * ordinary one again.
     */
    var adopted: Boolean = false,
    /**
     * Whether the node can still write to this server's console.
     *
     * False for adopted processes only -- their stdin belonged to the previous daemon. The
     * console keeps working in the reading direction (the node tails the log file), and commands
     * are routed through the Minecraft plugin where the server has the `commands` capability.
     */
    var stdinAvailable: Boolean = true,
    /**
     * Bytes this server's directory took when it was last measured, or null when nobody has
     * measured it yet (§2.4.18 A).
     *
     * On the row as well as in the live sample because the live sample does not survive a Pano
     * restart and a server that is switched off never sends another one: the size of a directory
     * is a fact about files that outlives every process in the story, and a panel that showed a
     * dash for every offline server until somebody started it would be showing "unknown" for
     * something it knew perfectly well an hour ago. Written only when the figure actually changes,
     * not on every ten second tick.
     */
    var diskUsed: Long? = null,
    /**
     * Total size of the partition that directory is on, last time a reporter read it (§2.4.18,
     * revision of 2026-09-22).
     *
     * Stored beside [diskUsed] for the same reason and written by the same changed-only path: the
     * panel's disk gauge needs the pair, and a server that is off still sits on a disk of a known
     * size.
     */
    var diskTotal: Long? = null,
    /**
     * When the managed server's process started, epoch ms, or null when no process is running.
     *
     * The node's figure, not the plugin's: [startTime] is set when a Pano plugin connects, so a
     * managed server with no plugin had no uptime at all. See [com.panomc.platform.server.ProcessStartTime].
     */
    var processStartedAt: Long? = null,
    /**
     * The server's IANA time zone id, e.g. `Europe/Istanbul`, or null while nobody has reported one
     * (SM-60, §2.4.25). The plugin's (the game JVM's own) wins over the node's (its host's); see
     * [com.panomc.platform.server.ServerTimeZone].
     */
    var timeZone: String? = null,
    /**
     * Whether the node runs this server from a directory that was already there, adopted where it
     * is (`IMPORT_SERVER` mode `IN_PLACE`, the "Pano Agent" link), rather than one it made under
     * its own data directory.
     *
     * It changes what Pano may do to the files: removing the server keeps them (the node only takes
     * back what it put there), and a reinstall or a software change is refused with
     * `IN_PLACE_UNSUPPORTED` because both build a new directory beside the old one. The node is the
     * authority -- it reports the flag in every hello -- and this column is its last word.
     */
    var inPlace: Boolean = false,
    /**
     * The absolute path of the server's directory on its node's host, as the node last reported it;
     * null for a linked server and until a node that reports it (protocol 5) has said. For an
     * in-place server it starts as the path the admin typed and becomes the node's resolved one.
     */
    var directory: String? = null
) : DBEntity() {
    fun hasCapability(capability: ServerCapability) = capabilities.contains(capability.id)

    /** Whether a node owns this server's process. */
    val isManaged get() = kind == ServerKind.MANAGED

    /**
     * Whether this row is a connect request that is still waiting for an admin to answer it.
     *
     * Only a [ServerKind.LINKED] server can be one. A managed server is created by Pano itself on
     * a node it already trusts, so it is written with [permissionGranted] already set and never
     * goes through accept/reject; treating an unapproved managed row as pending would put it in
     * the pending list with a Reject button that deletes the database row while the node happily
     * keeps running the files.
     */
    val isPendingApproval get() = !permissionGranted && kind == ServerKind.LINKED

    /**
     * JSON view of this server that is safe to hand out over the panel API and the panel
     * WebSocket.
     *
     * [aesKey] is the shared secret of this server's plugin connection: anyone holding it can
     * decrypt and forge messages on that socket, so it must never leave the platform. Every panel
     * response that returns a server goes through here instead of serializing the entity directly.
     */
    fun toPublicJsonObject(): JsonObject = JsonObject.mapFrom(this).apply { remove("aesKey") }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other is Server && other.id == this.id
    }

    companion object {
        data class ServerSettings(
            var authIntegration: Boolean = true,
            var authRequireVerified: Boolean = true,
            var authKickAfterRegister: Boolean = true,
            var banIntegration: Boolean = true,
            var permissionIntegration: Boolean = true,
            /**
             * How many backups of this server Pano keeps before deleting the oldest.
             *
             * Lives here rather than in a column because it is a per-server preference with a
             * sensible default, and a row written before backups existed deserialises straight to
             * that default: every constructor parameter has one, so Kotlin gives this class the
             * no-arg constructor Gson needs.
             */
            var backupKeepLast: Int = DEFAULT_BACKUP_KEEP_LAST,
            /**
             * How many incremental snapshots Pano keeps (backups v2), counted apart from the full
             * zips above. Separate because the two are different animals: a snapshot costs only
             * what changed since the last one, so a server can afford hourly ones kept for a day
             * where a full zip of the same world every hour would fill the disk by lunch.
             */
            var snapshotKeepLast: Int = DEFAULT_SNAPSHOT_KEEP_LAST,
            /**
             * Disk cap for the snapshot repository, in bytes of `storedBytes`; null or zero for
             * none. Once the snapshots kept by [snapshotKeepLast] add up to more than this, the
             * oldest unpinned ones go until they fit, but never the newest.
             */
            var snapshotMaxBytes: Long? = null,
            /**
             * Whether Pano checks this server for updates on its own (SM-69, §2.4.34).
             *
             * Today that is the daily plugin sweep ([com.panomc.platform.server.plugins.PluginUpdateSweeper]):
             * with this off the server is skipped, so no `PLUGIN_UPDATES` alert, notification or
             * e-mail is raised about it. The plugins page still shows what is outdated when
             * somebody opens it -- that is the admin looking, not Pano nagging. Named for "updates"
             * rather than "plugin updates" so a later check of the server software itself honours
             * the same switch. On by default, and a row written before the switch existed reads as
             * on for the same no-arg-constructor reason as the fields above.
             */
            var autoUpdateCheck: Boolean = true
        ) {
            fun encode(): String = JsonObject.mapFrom(this).encode()

            companion object {
                const val DEFAULT_BACKUP_KEEP_LAST = 10

                /** Most backups an operator may ask Pano to keep. */
                const val MAX_BACKUP_KEEP_LAST = 100

                const val DEFAULT_SNAPSHOT_KEEP_LAST = 24

                /** Most snapshots an operator may ask Pano to keep. */
                const val MAX_SNAPSHOT_KEEP_LAST = 500
            }
        }
    }
}