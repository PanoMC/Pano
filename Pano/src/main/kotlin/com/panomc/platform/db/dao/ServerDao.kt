package com.panomc.platform.db.dao

import com.panomc.platform.db.Dao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.sqlclient.SqlClient

abstract class ServerDao : Dao<Server>(Server::class.java) {
    abstract suspend fun add(
        server: Server,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getById(
        id: Long,
        sqlClient: SqlClient
    ): Server?

    abstract suspend fun getAllByPermissionGranted(
        sqlClient: SqlClient
    ): List<Server>

    abstract suspend fun countOfPermissionGranted(
        sqlClient: SqlClient
    ): Long

    /**
     * Servers that asked to connect and are still waiting for someone to approve them.
     *
     * Linked servers only, see [Server.isPendingApproval].
     */
    abstract suspend fun getAllPending(
        sqlClient: SqlClient
    ): List<Server>

    abstract suspend fun count(
        sqlClient: SqlClient
    ): Long

    abstract suspend fun updateStatusById(
        id: Long,
        status: ServerStatus,
        sqlClient: SqlClient
    )

    abstract suspend fun updateRemoteAddressById(
        id: Long,
        remoteAddress: String?,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlClient: SqlClient
    )

    abstract suspend fun existsById(
        id: Long,
        sqlClient: SqlClient
    ): Boolean

    abstract suspend fun deleteById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updatePlayerCountById(
        id: Long,
        playerCount: Int,
        sqlClient: SqlClient
    )

    /**
     * Stores the size of the server's directory and of the disk it sits on, as the node or the
     * plugin last measured them (§2.4.18 A).
     *
     * Callers write only when a figure actually changed: a measurement arrives with every metrics
     * tick, and a row rewritten every ten seconds per server would be the busiest statement in
     * the platform for numbers that move a few times an hour.
     */
    abstract suspend fun updateDiskUsageById(
        id: Long,
        diskUsed: Long?,
        diskTotal: Long?,
        sqlClient: SqlClient
    )

    /** Stores the server's icon as a PNG data URL, the shape the plugin's own favicon has. */
    abstract suspend fun updateFaviconById(
        id: Long,
        favicon: String,
        sqlClient: SqlClient
    )

    /**
     * Stores why the server's install failed, or clears it with null
     * ([com.panomc.platform.node.ServerInstallFailure]).
     */
    abstract suspend fun updateInstallErrorById(
        id: Long,
        installError: String?,
        sqlClient: SqlClient
    )

    /** Stores the server's IANA time zone id, already validated (SM-60, §2.4.25). */
    abstract suspend fun updateTimeZoneById(
        id: Long,
        timeZone: String?,
        sqlClient: SqlClient
    )

    /**
     * Records when a managed server's process started, or clears it with null (SM-57's Uptime).
     *
     * A move out of a running state clears it on its own inside [updateProcessStateById]; this is
     * the write for the one moment it gets a value, when the node reports RUNNING.
     */
    abstract suspend fun updateProcessStartedAtById(
        id: Long,
        processStartedAt: Long?,
        sqlClient: SqlClient
    )

    abstract suspend fun updateStartTimeById(
        id: Long,
        startTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateStopTimeById(
        id: Long,
        stopTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateAcceptedTimeById(
        id: Long,
        acceptedTime: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateServerForOfflineById(
        id: Long,
        sqlClient: SqlClient
    )

    abstract suspend fun updateCustomNameById(
        id: Long,
        customName: String?,
        sqlClient: SqlClient
    )

    abstract suspend fun update(
        server: Server,
        sqlClient: SqlClient
    )

    abstract suspend fun updateSettingsById(
        serverSettings: Server.Companion.ServerSettings,
        id: Long,
        sqlClient: SqlClient
    )

    /**
     * Looks a server up by the id nodes address it with.
     *
     * Node traffic never carries a database id, so this is the only entry point for it; callers
     * must still check that the row they get back belongs to the node that asked.
     */
    abstract suspend fun getByUuid(
        uuid: String,
        sqlClient: SqlClient
    ): Server?

    /** How many servers of one kind exist, for the usage counters telemetry reports. */
    abstract suspend fun countByKind(
        kind: ServerKind,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun getAllByNodeId(
        nodeId: Long,
        sqlClient: SqlClient
    ): List<Server>

    abstract suspend fun countByNodeId(
        nodeId: Long,
        sqlClient: SqlClient
    ): Long

    abstract suspend fun updateProcessStateById(
        id: Long,
        processState: ServerProcessState?,
        lastExitCode: Int?,
        sqlClient: SqlClient
    )

    /**
     * Where the server's directory is on its node, and whether it was adopted there in place
     * (`server.inPlace`, `server.directory`). Its own statement because the node reports it in every
     * hello and on `IMPORT_RESULT`, where nothing else about the row is being written.
     */
    abstract suspend fun updateLocationById(
        id: Long,
        inPlace: Boolean,
        directory: String?,
        sqlClient: SqlClient
    )

    /** Whether the node inherited this server's process, and whether it can still type into it. */
    abstract suspend fun updateAdoptionById(
        id: Long,
        adopted: Boolean,
        stdinAvailable: Boolean,
        sqlClient: SqlClient
    )

    /** Writes the startup settings a node needs to (re)launch this server. */
    abstract suspend fun updateStartupById(
        id: Long,
        javaVersion: Int?,
        memoryMb: Int?,
        jvmArgs: List<String>,
        properties: Map<String, String>,
        gamePort: Int?,
        autoStart: Boolean,
        crashRestart: Boolean,
        sqlClient: SqlClient
    )

    abstract suspend fun updateSoftwareById(
        id: Long,
        software: String?,
        softwareVersion: String?,
        sqlClient: SqlClient
    )

    /**
     * Moves a managed server onto the port its node actually gave it.
     *
     * Both columns, because they are two views of one number: `gamePort` is what the node was told
     * to bind and `port` is the address the panel and the themes hand to players. A node that had
     * to pick a different port makes both of them wrong at once.
     */
    abstract suspend fun updateGamePortById(
        id: Long,
        gamePort: Int,
        sqlClient: SqlClient
    )

    /**
     * Writes back what a node discovered while importing a directory.
     *
     * One statement rather than four because it is one fact: the row was created before anybody
     * knew what was being imported, and this is the moment it stops being a guess. [type] and
     * [version] are the display pair the panel and the themes read, [software] and
     * [softwareVersion] the managed pair the node reads, and they must never disagree.
     */
    abstract suspend fun updateImportedSoftwareById(
        id: Long,
        type: ServerType,
        software: String?,
        softwareVersion: String?,
        version: String,
        javaVersion: Int?,
        gamePort: Int?,
        sqlClient: SqlClient
    )
}
