package com.panomc.platform.db.implementation

import com.panomc.platform.annotation.Dao
import com.panomc.platform.db.dao.ServerDao
import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ProcessStartTime
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

@Dao
class ServerDaoImpl : ServerDao() {

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `name` varchar(255) NOT NULL,
                              `motd` text NOT NULL,
                              `host` varchar(255) NOT NULL,
                              `remoteAddress` varchar(255),
                              `port` int(5) NOT NULL,
                              `playerCount` bigint NOT NULL,
                              `maxPlayerCount` bigint NOT NULL,
                              `type` varchar(255) NOT NULL,
                              `version` varchar(255) NOT NULL,
                              `favicon` text NOT NULL,
                              `permissionGranted` TINYINT(1) default 0,
                              `status` VARCHAR(255) NOT NULL,
                              `addedTime` bigint NOT NULL,
                              `acceptedTime` bigint NOT NULL,
                              `startTime` bigint NOT NULL,
                              `stopTime` bigint NOT NULL,
                              `aesKey` text NOT NULL,
                              `settings` text NOT NULL,
                              `customName` varchar(255),
                              `protocolVersion` int NOT NULL DEFAULT 1,
                              `pluginVersion` varchar(64) NULL,
                              `capabilities` text NULL,
                              `kind` varchar(16) NOT NULL DEFAULT 'LINKED',
                              `nodeId` bigint NULL,
                              `uuid` varchar(36) NULL,
                              `software` varchar(32) NULL,
                              `softwareVersion` varchar(64) NULL,
                              `javaVersion` int NULL,
                              `memoryMb` int NULL,
                              `jvmArgs` text NULL,
                              `properties` text NULL,
                              `gamePort` int NULL,
                              `autoStart` TINYINT(1) NOT NULL DEFAULT 0,
                              `crashRestart` TINYINT(1) NOT NULL DEFAULT 1,
                              `lastExitCode` int NULL,
                              `processState` varchar(16) NULL,
                              `adopted` TINYINT(1) NOT NULL DEFAULT 0,
                              `stdinAvailable` TINYINT(1) NOT NULL DEFAULT 1,
                              `diskUsed` bigint NULL,
                              `diskTotal` bigint NULL,
                              `processStartedAt` bigint NULL,
                              `timeZone` varchar(64) NULL,
                              `inPlace` TINYINT(1) NOT NULL DEFAULT 0,
                              `directory` text NULL,
                              `installError` text NULL,
                              PRIMARY KEY (`id`),
                              UNIQUE KEY `idx_server_uuid` (`uuid`),
                              KEY `idx_server_node` (`nodeId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Server table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(
        server: Server,
        sqlClient: SqlClient
    ): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`name`, `motd`, `host`, `remoteAddress`, `port`, `playerCount`, `maxPlayerCount`, `type`, `version`, `favicon`, `permissionGranted`, `status`, `addedTime`, `acceptedTime`, `startTime`, `stopTime`, `aesKey`, `settings`, `protocolVersion`, `pluginVersion`, `capabilities`, `kind`, `nodeId`, `uuid`, `software`, `softwareVersion`, `javaVersion`, `memoryMb`, `jvmArgs`, `properties`, `gamePort`, `autoStart`, `crashRestart`, `lastExitCode`, `processState`, `inPlace`, `directory`) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    server.name,
                    server.motd,
                    server.host,
                    server.remoteAddress,
                    server.port,
                    server.playerCount,
                    server.maxPlayerCount,
                    server.type,
                    server.version,
                    server.favicon,
                    // Written from the entity instead of leaning on the column default: a server
                    // Pano created itself is approved by construction, and leaving it out is what
                    // made every managed server land as a pending connect request nothing could
                    // reach.
                    if (server.permissionGranted) 1 else 0,
                    server.status.name,
                    server.addedTime,
                    server.acceptedTime,
                    server.startTime,
                    0,
                    server.aesKey,
                    server.settings.encode(),
                    server.protocolVersion,
                    server.pluginVersion,
                    JsonArray(server.capabilities).encode(),
                    server.kind.name,
                    server.nodeId,
                    server.uuid,
                    server.software,
                    server.softwareVersion,
                    server.javaVersion,
                    server.memoryMb,
                    JsonArray(server.jvmArgs).encode(),
                    JsonObject(server.properties.mapValues { it.value as Any }).encode(),
                    server.gamePort,
                    if (server.autoStart) 1 else 0,
                    if (server.crashRestart) 1 else 0,
                    server.lastExitCode,
                    server.processState?.name,
                    if (server.inPlace) 1 else 0,
                    server.directory
                )
            ).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): Server? {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE  `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        val row = rows.toList()[0]

        return row.toEntity()
    }

    override suspend fun getAllByPermissionGranted(sqlClient: SqlClient): List<Server> {
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE  `permissionGranted` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getAllPending(sqlClient: SqlClient): List<Server> {
        // Newest first: a connect request is answered while the person who triggered it is still
        // looking at their server console, so the one that just arrived is the one being asked
        // about.
        // Only linked servers: a managed server is created by Pano, approved on the spot and
        // never asks for anything, so an unapproved managed row is a bug rather than a decision
        // waiting for an admin, and offering it here would put a Reject button next to a server
        // whose files only its node can remove.
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                    "WHERE `permissionGranted` = ? AND `kind` = ? ORDER BY `addedTime` DESC, `id` DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(0, ServerKind.LINKED.name))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countOfPermissionGranted(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE  `permissionGranted` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(1)
            )
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun count(sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}`"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute()
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun updateStatusById(id: Long, status: ServerStatus, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    status.name,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateRemoteAddressById(id: Long, remoteAddress: String?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `remoteAddress` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    remoteAddress,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updatePermissionGrantedById(
        id: Long,
        permissionGranted: Boolean,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `permissionGranted` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    if (permissionGranted) 1 else 0,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun existsById(id: Long, sqlClient: SqlClient): Boolean {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` where `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    id
                )
            )
            .coAwait()

        return rows.toList()[0].getLong(0) == 1L
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        val query =
            "DELETE from `${getTablePrefix() + tableName}` WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(id)
            )
            .coAwait()
    }

    override suspend fun updatePlayerCountById(id: Long, playerCount: Int, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `playerCount` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    playerCount,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateStartTimeById(id: Long, startTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `startTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    startTime,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateStopTimeById(id: Long, stopTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `stopTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    stopTime,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateAcceptedTimeById(id: Long, acceptedTime: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `acceptedTime` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    acceptedTime,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateCustomNameById(id: Long, customName: String?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `customName` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(customName, id))
            .coAwait()
    }

    override suspend fun updateServerForOfflineById(id: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `status` = ?, `playerCount` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    ServerStatus.OFFLINE.name,
                    0,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun update(
        server: Server,
        sqlClient: SqlClient
    ) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `name` = ?, `motd` = ?, `host` = ?, `remoteAddress` = ?, `port` = ?, `playerCount` = ?, `maxPlayerCount` = ?, `type` = ?, `version` = ?, `favicon` = ?, `status` = ?, `startTime` = ?, `customName` = ?, `protocolVersion` = ?, `pluginVersion` = ?, `capabilities` = ?, `kind` = ?, `nodeId` = ?, `uuid` = ?, `software` = ?, `softwareVersion` = ?, `javaVersion` = ?, `memoryMb` = ?, `jvmArgs` = ?, `properties` = ?, `gamePort` = ?, `autoStart` = ?, `crashRestart` = ?, `lastExitCode` = ?, `processState` = ?, `diskUsed` = ?, `diskTotal` = ?, `processStartedAt` = ?, `timeZone` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    server.name,
                    server.motd,
                    server.host,
                    server.remoteAddress,
                    server.port,
                    server.playerCount,
                    server.maxPlayerCount,
                    server.type,
                    server.version,
                    server.favicon,
                    server.status.name,
                    server.startTime,
                    server.customName,
                    server.protocolVersion,
                    server.pluginVersion,
                    JsonArray(server.capabilities).encode(),
                    server.kind.name,
                    server.nodeId,
                    server.uuid,
                    server.software,
                    server.softwareVersion,
                    server.javaVersion,
                    server.memoryMb,
                    JsonArray(server.jvmArgs).encode(),
                    JsonObject(server.properties.mapValues { it.value as Any }).encode(),
                    server.gamePort,
                    if (server.autoStart) 1 else 0,
                    if (server.crashRestart) 1 else 0,
                    server.lastExitCode,
                    server.processState?.name,
                    // Every caller of this loads the row first, so this writes back what it read
                    // rather than clearing a measurement the metrics path put there; the tick
                    // that measures it writes through updateDiskUsedById instead.
                    server.diskUsed,
                    server.diskTotal,
                    server.processStartedAt,
                    server.timeZone,
                    server.id
                )
            )
            .coAwait()
    }

    /**
     * Stores the measured size of a server's directory and of the disk it is on (§2.4.18 A).
     *
     * Its own statement rather than a field of [update] because it is written from the metrics
     * path, where the only thing known about the server is its id and the two numbers: loading
     * and rewriting the whole row every time a world grew would be both wasteful and a way to
     * undo whatever the panel changed in between. The pair goes in one statement because it is
     * read as a pair — a used figure next to a stale total is a gauge pointing at the wrong mark.
     */
    override suspend fun updateDiskUsageById(id: Long, diskUsed: Long?, diskTotal: Long?, sqlClient: SqlClient) {
        val query =
            "UPDATE `${getTablePrefix() + tableName}` SET `diskUsed` = ?, `diskTotal` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    diskUsed,
                    diskTotal,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateSettingsById(
        serverSettings: Server.Companion.ServerSettings,
        id: Long,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `settings` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    serverSettings.encode(),
                    id
                )
            )
            .coAwait()
    }

    override suspend fun getByUuid(uuid: String, sqlClient: SqlClient): Server? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `uuid` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(uuid))
            .coAwait()

        if (rows.size() == 0) {
            return null
        }

        return rows.toList()[0].toEntity()
    }

    override suspend fun countByKind(kind: ServerKind, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `kind` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(kind.name))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun getAllByNodeId(nodeId: Long, sqlClient: SqlClient): List<Server> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` " +
                "WHERE `nodeId` = ? ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countByNodeId(nodeId: Long, sqlClient: SqlClient): Long {
        val query = "SELECT COUNT(id) FROM `${getTablePrefix() + tableName}` WHERE `nodeId` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(nodeId))
            .coAwait()

        return rows.toList()[0].getLong(0)
    }

    override suspend fun updateProcessStateById(
        id: Long,
        processState: ServerProcessState?,
        lastExitCode: Int?,
        sqlClient: SqlClient
    ) {
        // The start time goes with the process: every writer that moves a server out of a state
        // with a live process (a stop, a crash, a reinstall, a hello that disagrees) clears it here,
        // so none of them can leave an uptime behind for a server that is not running.
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `processState` = ?, `lastExitCode` = ?, " +
                "`processStartedAt` = IF(?, `processStartedAt`, NULL) WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(processState?.name, lastExitCode, if (ProcessStartTime.keeps(processState)) 1 else 0, id))
            .coAwait()
    }

    override suspend fun updateFaviconById(id: Long, favicon: String, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `favicon` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(favicon, id))
            .coAwait()
    }

    override suspend fun updateInstallErrorById(id: Long, installError: String?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `installError` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(installError, id))
            .coAwait()
    }

    override suspend fun updateTimeZoneById(id: Long, timeZone: String?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `timeZone` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(timeZone, id))
            .coAwait()
    }

    override suspend fun updateProcessStartedAtById(id: Long, processStartedAt: Long?, sqlClient: SqlClient) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `processStartedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(processStartedAt, id))
            .coAwait()
    }

    /**
     * Records what the node just said about an adopted process (SM-51, §2.4.16).
     *
     * Its own statement rather than two more parameters on [updateProcessStateById] because the
     * pair changes on a different rhythm: a state moves several times a minute while these two
     * only move when a node restarts or a server is started properly again, and every other
     * caller of the state update has nothing to say about them.
     */
    override suspend fun updateAdoptionById(
        id: Long,
        adopted: Boolean,
        stdinAvailable: Boolean,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `adopted` = ?, `stdinAvailable` = ? " +
                "WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(if (adopted) 1 else 0, if (stdinAvailable) 1 else 0, id))
            .coAwait()
    }

    override suspend fun updateStartupById(
        id: Long,
        javaVersion: Int?,
        memoryMb: Int?,
        jvmArgs: List<String>,
        properties: Map<String, String>,
        gamePort: Int?,
        autoStart: Boolean,
        crashRestart: Boolean,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `javaVersion` = ?, `memoryMb` = ?, " +
                "`jvmArgs` = ?, `properties` = ?, `gamePort` = ?, `autoStart` = ?, `crashRestart` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    javaVersion,
                    memoryMb,
                    JsonArray(jvmArgs).encode(),
                    JsonObject(properties.mapValues { it.value as Any }).encode(),
                    gamePort,
                    if (autoStart) 1 else 0,
                    if (crashRestart) 1 else 0,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateImportedSoftwareById(
        id: Long,
        type: ServerType,
        software: String?,
        softwareVersion: String?,
        version: String,
        javaVersion: Int?,
        gamePort: Int?,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `type` = ?, `software` = ?, " +
                "`softwareVersion` = ?, `version` = ?, `javaVersion` = ?, `gamePort` = ?, `port` = ? " +
                "WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    type.name,
                    software,
                    softwareVersion,
                    version,
                    javaVersion,
                    gamePort,
                    gamePort ?: 0,
                    id
                )
            )
            .coAwait()
    }

    override suspend fun updateLocationById(
        id: Long,
        inPlace: Boolean,
        directory: String?,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `inPlace` = ?, `directory` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(if (inPlace) 1 else 0, directory, id))
            .coAwait()
    }

    override suspend fun updateGamePortById(
        id: Long,
        gamePort: Int,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `gamePort` = ?, `port` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(gamePort, gamePort, id))
            .coAwait()
    }

    override suspend fun updateSoftwareById(
        id: Long,
        software: String?,
        softwareVersion: String?,
        sqlClient: SqlClient
    ) {
        val query = "UPDATE `${getTablePrefix() + tableName}` SET `software` = ?, `softwareVersion` = ? " +
                "WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(software, softwareVersion, id))
            .coAwait()
    }
}
