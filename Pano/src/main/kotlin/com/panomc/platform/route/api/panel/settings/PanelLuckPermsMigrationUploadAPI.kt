package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Tuple
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.DriverManager

@Endpoint
class PanelLuckPermsMigrationUploadAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val vertx: Vertx
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/luckperms/upload", RouteType.POST))

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(100 * 1024 * 1024) // 100MB

    override fun getValidationHandler(schemaRepository: SchemaRepository) = null

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val fileUploads = context.fileUploads()

        val configUpload = fileUploads.find { it.name() == "config" }
            ?: throw InvalidData(extras = mapOf("message" to "config.yml file is required"))

        val configFile = File(configUpload.uploadedFileName())
        val configText = configFile.readText()

        // Parse LuckPerms config.yml
        val yaml = Yaml()
        val configMap: Map<String, Any> = try {
            @Suppress("UNCHECKED_CAST")
            yaml.load(configText) as Map<String, Any>
        } catch (e: Exception) {
            throw InvalidData(extras = mapOf("message" to "Invalid YAML config file"))
        }

        // Extract storage method
        val storageMethod = (configMap["storage-method"] as? String)?.uppercase() ?: "H2"

        // Extract table prefix
        val dataSection = getNestedMap(configMap, "data")
        val tablePrefix = (dataSection?.get("table-prefix") as? String) ?: "luckperms_"

        // Read LuckPerms data based on storage method
        val luckPermsData: LuckPermsData = when (storageMethod) {
            "H2" -> {
                val dbUpload = fileUploads.find { it.name() == "database" }
                    ?: throw InvalidData(extras = mapOf("message" to "H2 database file is required for H2 backend"))

                val dbFile = File(dbUpload.uploadedFileName())

                // Save db file temporarily for later import
                val tempFolder = File(AppConstants.TEMP_FOLDER)
                if (!tempFolder.exists()) tempFolder.mkdirs()

                val tempDbPath = AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration.mv.db"
                dbFile.copyTo(File(tempDbPath), true)

                // Also save config for later import
                val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration_config.yml"
                configFile.copyTo(File(tempConfigPath), true)

                readFromH2(tempDbPath, tablePrefix)
            }

            "MYSQL", "MARIADB" -> {
                val host = context.request().getFormAttribute("dbHost")?.takeIf { it.isNotBlank() }
                    ?: (dataSection?.get("address") as? String) ?: "localhost"
                val port = context.request().getFormAttribute("dbPort")?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    ?: 3306
                val dbName = context.request().getFormAttribute("dbName")?.takeIf { it.isNotBlank() }
                    ?: (dataSection?.get("database") as? String) ?: "minecraft"
                val username = context.request().getFormAttribute("dbUser")?.takeIf { it.isNotBlank() }
                    ?: (dataSection?.get("username") as? String) ?: "root"
                val password = context.request().getFormAttribute("dbPassword")
                    ?: (dataSection?.get("password") as? String) ?: ""

                // Save config for later import
                val tempFolder = File(AppConstants.TEMP_FOLDER)
                if (!tempFolder.exists()) tempFolder.mkdirs()
                val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration_config.yml"
                configFile.copyTo(File(tempConfigPath), true)

                readFromMySQL(host, port, dbName, username, password, tablePrefix)
            }

            else -> throw InvalidData(extras = mapOf("message" to "Unsupported storage method: $storageMethod. Supported: H2, MySQL, MariaDB"))
        }

        // Check existing groups/tracks in Pano's permission system
        val sqlClient = getSqlClient()
        val existingGroups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val existingTracks = databaseManager.permissionTrackDao.getAll(sqlClient)
        val existingNodes = databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)

        val existingGroupNames = existingGroups.map { it.name }.toSet()
        val existingTrackNames = existingTracks.map { it.name }.toSet()

        // Build preview data
        val groupsPreview = luckPermsData.groups.map { groupName ->
            mapOf(
                "name" to groupName,
                "status" to if (groupName in existingGroupNames) "existing" else "new",
                "nodeCount" to luckPermsData.groupPermissions.count { it.groupName == groupName }
            )
        }

        val tracksPreview = luckPermsData.tracks.map { track ->
            mapOf(
                "name" to track.name,
                "groups" to track.groups,
                "status" to if (track.name in existingTrackNames) "existing" else "new"
            )
        }

        // Build user permissions preview: only users that exist in Pano
        val lpUsernames = luckPermsData.players.map { it.username }
        val userIdMap = if (lpUsernames.isNotEmpty()) {
            databaseManager.userDao.getIdsByListOfUsername(lpUsernames.distinct(), sqlClient)
        } else {
            emptyMap()
        }

        val playersPreview = luckPermsData.players.map { player ->
            val existsInPano = userIdMap.containsKey(player.username)
            val userPermCount = luckPermsData.userPermissions.count { it.uuid == player.uuid }
            mapOf(
                "uuid" to player.uuid,
                "username" to player.username,
                "primaryGroup" to player.primaryGroup,
                "existsInPano" to existsInPano,
                "permissionCount" to userPermCount
            )
        }

        val newGroupCount = groupsPreview.count { it["status"] == "new" }
        val existingGroupCount = groupsPreview.count { it["status"] == "existing" }

        return Successful(
            mapOf(
                "groups" to groupsPreview,
                "tracks" to tracksPreview,
                "players" to playersPreview,
                "groupPermissions" to luckPermsData.groupPermissions.map { it.toMap() },
                "userPermissions" to luckPermsData.userPermissions.map { it.toMap() },
                "totalGroupCount" to luckPermsData.groups.size,
                "newGroupCount" to newGroupCount,
                "existingGroupCount" to existingGroupCount,
                "totalTrackCount" to luckPermsData.tracks.size,
                "totalPlayerCount" to luckPermsData.players.size,
                "panoPlayerCount" to playersPreview.count { it["existsInPano"] == true },
                "storageMethod" to storageMethod,
                "tablePrefix" to tablePrefix,
                "existingPanoGroupCount" to existingGroups.size,
                "existingPanoNodeCount" to existingNodes.size
            )
        )
    }

    private suspend fun readFromH2(dbPath: String, tablePrefix: String): LuckPermsData {
        return vertx.executeBlocking {
            // H2 stores the file as filename.mv.db but the JDBC URL should not include .mv.db
            // H2 also requires an absolute path
            val absolutePath = File(dbPath).absolutePath
            val jdbcPath = if (absolutePath.endsWith(".mv.db")) {
                absolutePath.removeSuffix(".mv.db")
            } else {
                absolutePath
            }

            val conn = DriverManager.getConnection(
                "jdbc:h2:$jdbcPath;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;ACCESS_MODE_DATA=r",
                "",
                ""
            )

            conn.use { connection ->
                readFromJdbc(connection, tablePrefix)
            }
        }.coAwait()
    }

    private suspend fun readFromMySQL(
        host: String, port: Int, dbName: String,
        username: String, password: String, tablePrefix: String
    ): LuckPermsData {
        val connectOptions = MySQLConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase(dbName)
            .setUser(username)
            .setPassword(password)

        val poolOptions = io.vertx.sqlclient.PoolOptions().setMaxSize(1)

        val client = try {
            MySQLBuilder.pool()
                .with(poolOptions)
                .connectingTo(connectOptions)
                .using(vertx)
                .build()
        } catch (e: Exception) {
            throw InvalidData(
                extras = mapOf(
                    "message" to "Failed to connect to MySQL/MariaDB at $host:$port/$dbName. Error: ${e.message}"
                )
            )
        }

        return try {
            val data = LuckPermsData()

            // Read groups
            val groupRows = client.preparedQuery("SELECT * FROM `${tablePrefix}groups`")
                .execute(Tuple.tuple()).coAwait()
            for (row in groupRows) {
                val name = try { row.getString("name") } catch (_: Exception) { null }
                if (name != null) data.groups.add(name)
            }

            // Read tracks
            val trackRows = client.preparedQuery("SELECT * FROM `${tablePrefix}tracks`")
                .execute(Tuple.tuple()).coAwait()
            for (row in trackRows) {
                val name = try { row.getString("name") } catch (_: Exception) { null }
                val groups = try { row.getString("groups") } catch (_: Exception) { null }
                if (name != null) {
                    data.tracks.add(LPTrack(name, parseTrackGroups(groups)))
                }
            }

            // Read players
            val playerRows = client.preparedQuery("SELECT * FROM `${tablePrefix}players`")
                .execute(Tuple.tuple()).coAwait()
            for (row in playerRows) {
                val uuid = try { row.getString("uuid") } catch (_: Exception) { null }
                val username = try { row.getString("username") } catch (_: Exception) { null }
                val primaryGroup = try { row.getString("primary_group") } catch (_: Exception) { "default" }
                if (uuid != null && username != null) {
                    data.players.add(LPPlayer(uuid, username, primaryGroup ?: "default"))
                }
            }

            // Read group permissions
            val gpRows = client.preparedQuery("SELECT * FROM `${tablePrefix}group_permissions`")
                .execute(Tuple.tuple()).coAwait()
            for (row in gpRows) {
                val name = try { row.getString("name") } catch (_: Exception) { null }
                val permission = try { row.getString("permission") } catch (_: Exception) { null }
                val value = try { row.getBoolean("value") } catch (_: Exception) { true }
                val server = try { row.getString("server") } catch (_: Exception) { "global" }
                val world = try { row.getString("world") } catch (_: Exception) { "global" }
                val expiry = try { row.getLong("expiry") } catch (_: Exception) { 0L }
                val contexts = try { row.getString("contexts") } catch (_: Exception) { "{}" }
                if (name != null && permission != null) {
                    data.groupPermissions.add(LPPermission(name, null, permission, value ?: true, server ?: "global", world ?: "global", expiry ?: 0L, contexts ?: "{}"))
                }
            }

            // Read user permissions
            val upRows = client.preparedQuery("SELECT * FROM `${tablePrefix}user_permissions`")
                .execute(Tuple.tuple()).coAwait()
            for (row in upRows) {
                val uuid = try { row.getString("uuid") } catch (_: Exception) { null }
                val permission = try { row.getString("permission") } catch (_: Exception) { null }
                val value = try { row.getBoolean("value") } catch (_: Exception) { true }
                val server = try { row.getString("server") } catch (_: Exception) { "global" }
                val world = try { row.getString("world") } catch (_: Exception) { "global" }
                val expiry = try { row.getLong("expiry") } catch (_: Exception) { 0L }
                val contexts = try { row.getString("contexts") } catch (_: Exception) { "{}" }
                if (uuid != null && permission != null) {
                    data.userPermissions.add(LPPermission(null, uuid, permission, value ?: true, server ?: "global", world ?: "global", expiry ?: 0L, contexts ?: "{}"))
                }
            }

            data
        } finally {
            client.close().coAwait()
        }
    }

    private fun readFromJdbc(connection: java.sql.Connection, tablePrefix: String): LuckPermsData {
        val data = LuckPermsData()
        val stmt = connection.createStatement()

        // Read groups
        try {
            val groupRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}groups`")
            while (groupRs.next()) {
                val name = tryGetString(groupRs, "name")
                if (name != null) data.groups.add(name)
            }
        } catch (_: Exception) {
            // Table may not exist
        }

        // Read tracks
        try {
            val trackRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}tracks`")
            while (trackRs.next()) {
                val name = tryGetString(trackRs, "name")
                val groups = tryGetString(trackRs, "groups")
                if (name != null) {
                    data.tracks.add(LPTrack(name, parseTrackGroups(groups)))
                }
            }
        } catch (_: Exception) {
            // Table may not exist — tracks are optional in LuckPerms
        }

        // Read players
        try {
            val playerRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}players`")
            while (playerRs.next()) {
                val uuid = tryGetString(playerRs, "uuid")
                val username = tryGetString(playerRs, "username")
                val primaryGroup = tryGetString(playerRs, "primary_group") ?: "default"
                if (uuid != null && username != null) {
                    data.players.add(LPPlayer(uuid, username, primaryGroup))
                }
            }
        } catch (_: Exception) {
            // Table may not exist
        }

        // Read group permissions
        try {
            val gpRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}group_permissions`")
            while (gpRs.next()) {
                val name = tryGetString(gpRs, "name")
                val permission = tryGetString(gpRs, "permission")
                val value = tryGetBoolean(gpRs, "value") ?: true
                val server = tryGetString(gpRs, "server") ?: "global"
                val world = tryGetString(gpRs, "world") ?: "global"
                val expiry = tryGetLong(gpRs, "expiry") ?: 0L
                val contexts = tryGetString(gpRs, "contexts") ?: "{}"
                if (name != null && permission != null) {
                    data.groupPermissions.add(LPPermission(name, null, permission, value, server, world, expiry, contexts))
                }
            }
        } catch (_: Exception) {
            // Table may not exist
        }

        // Read user permissions
        try {
            val upRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}user_permissions`")
            while (upRs.next()) {
                val uuid = tryGetString(upRs, "uuid")
                val permission = tryGetString(upRs, "permission")
                val value = tryGetBoolean(upRs, "value") ?: true
                val server = tryGetString(upRs, "server") ?: "global"
                val world = tryGetString(upRs, "world") ?: "global"
                val expiry = tryGetLong(upRs, "expiry") ?: 0L
                val contexts = tryGetString(upRs, "contexts") ?: "{}"
                if (uuid != null && permission != null) {
                    data.userPermissions.add(LPPermission(null, uuid, permission, value, server, world, expiry, contexts))
                }
            }
        } catch (_: Exception) {
            // Table may not exist
        }

        return data
    }

    /**
     * Parse LuckPerms track groups field.
     * The format is a JSON array like: ["default","vip","admin"]
     */
    private fun parseTrackGroups(groups: String?): List<String> {
        if (groups.isNullOrBlank()) return emptyList()
        return try {
            // Simple JSON array parse
            val trimmed = groups.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val inner = trimmed.substring(1, trimmed.length - 1)
                if (inner.isBlank()) return emptyList()
                inner.split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
            } else {
                listOf(trimmed)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun tryGetString(rs: java.sql.ResultSet, column: String): String? {
        return try {
            rs.getString(column)
        } catch (_: Exception) {
            null
        }
    }

    private fun tryGetLong(rs: java.sql.ResultSet, column: String): Long? {
        return try {
            val value = rs.getLong(column)
            if (rs.wasNull()) null else value
        } catch (_: Exception) {
            null
        }
    }

    private fun tryGetBoolean(rs: java.sql.ResultSet, column: String): Boolean? {
        return try {
            val value = rs.getBoolean(column)
            if (rs.wasNull()) null else value
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getNestedMap(map: Map<String, Any>?, key: String): Map<String, Any>? {
        return map?.get(key) as? Map<String, Any>
    }

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) {
            throw InvalidData(extras = mapOf("message" to "Invalid upload"))
        }
    }

    // Data classes for LuckPerms data
    data class LuckPermsData(
        val groups: MutableList<String> = mutableListOf(),
        val tracks: MutableList<LPTrack> = mutableListOf(),
        val players: MutableList<LPPlayer> = mutableListOf(),
        val groupPermissions: MutableList<LPPermission> = mutableListOf(),
        val userPermissions: MutableList<LPPermission> = mutableListOf()
    )

    data class LPTrack(val name: String, val groups: List<String>)

    data class LPPlayer(val uuid: String, val username: String, val primaryGroup: String)

    data class LPPermission(
        val groupName: String?,
        val uuid: String?,
        val permission: String,
        val value: Boolean,
        val server: String,
        val world: String,
        val expiry: Long,
        val contexts: String
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "groupName" to groupName,
            "uuid" to uuid,
            "permission" to permission,
            "value" to value,
            "server" to server,
            "world" to world,
            "expiry" to expiry,
            "contexts" to contexts
        )
    }
}
