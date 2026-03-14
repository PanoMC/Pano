package com.panomc.platform.route.api.panel.settings

import com.panomc.platform.AppConstants
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.permission.ManagePlatformSettingsPermission
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Tuple
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.sql.DriverManager

@Endpoint
class PanelLuckPermsMigrationImportAPI(
    private val authProvider: AuthProvider,
    private val databaseManager: DatabaseManager,
    private val permissionManager: PermissionManager,
    private val vertx: Vertx
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/migration/luckperms/import", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(
                Bodies.json(
                    objectSchema()
                        .requiredProperty("selectedGroups", arraySchema().items(stringSchema()))
                        .optionalProperty("selectedTracks", arraySchema().items(stringSchema()))
                        .optionalProperty("importUserPermissions", booleanSchema())
                        .optionalProperty("mergeStrategy", stringSchema()) // 'replace' or 'merge'
                )
            )
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        if (!authProvider.hasPermission(ManagePlatformSettingsPermission(), context)) {
            throw NoPermission()
        }

        val body = context.body().asJsonObject()
        val selectedGroups = body.getJsonArray("selectedGroups").map { it as String }.toSet()
        val selectedTracks = body.getJsonArray("selectedTracks")?.map { it as String }?.toSet() ?: emptySet()
        val importUserPermissions = body.getBoolean("importUserPermissions") ?: true
        val mergeStrategy = body.getString("mergeStrategy") ?: "merge" // 'replace' or 'merge'

        if (selectedGroups.isEmpty()) {
            throw InvalidData(extras = mapOf("message" to "No groups selected for import"))
        }

        // Load saved config from temp
        val tempConfigPath = AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration_config.yml"
        val configFile = File(tempConfigPath)

        if (!configFile.exists()) {
            throw InvalidData(extras = mapOf("message" to "Migration session expired. Please upload files again."))
        }

        val configText = configFile.readText()
        val yaml = Yaml()

        @Suppress("UNCHECKED_CAST")
        val configMap: Map<String, Any> = yaml.load(configText) as Map<String, Any>

        val storageMethod = (configMap["storage-method"] as? String)?.uppercase() ?: "H2"
        val dataSection = getNestedMap(configMap, "data")
        val tablePrefix = (dataSection?.get("table-prefix") as? String) ?: "luckperms_"

        // Read LuckPerms data again from source
        val luckPermsData: PanelLuckPermsMigrationUploadAPI.LuckPermsData = when (storageMethod) {
            "H2" -> {
                val tempDbPath = AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration.mv.db"
                if (!File(tempDbPath).exists()) {
                    throw InvalidData(extras = mapOf("message" to "Migration session expired. Please upload files again."))
                }
                readFromH2(tempDbPath, tablePrefix)
            }

            "MYSQL", "MARIADB" -> {
                val host = (dataSection?.get("address") as? String) ?: "localhost"
                val port = 3306
                val dbName = (dataSection?.get("database") as? String) ?: "minecraft"
                val username = (dataSection?.get("username") as? String) ?: "root"
                val password = (dataSection?.get("password") as? String) ?: ""

                readFromMySQL(host, port, dbName, username, password, tablePrefix)
            }

            else -> throw InvalidData(extras = mapOf("message" to "Unsupported storage method: $storageMethod"))
        }

        val sqlClient = getSqlClient()
        var importedGroupCount = 0
        var updatedGroupCount = 0
        var importedTrackCount = 0
        var importedNodeCount = 0
        var importedUserNodeCount = 0
        var skippedNodeCount = 0
        val errors = mutableListOf<Map<String, String>>()

        // If replace mode, clear existing permission data
        if (mergeStrategy == "replace") {
            val prefix = databaseManager.getTablePrefix()
            sqlClient.query("DELETE FROM `${prefix}permission_node`").execute().coAwait()
            sqlClient.query("DELETE FROM `${prefix}permission_track`").execute().coAwait()
            sqlClient.query("DELETE FROM `${prefix}permission_group`").execute().coAwait()
        }

        // Get existing groups for merge mode
        val existingGroups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val existingGroupByName = existingGroups.associateBy { it.name }

        // Map to store group name -> Pano group ID
        val groupIdByName = mutableMapOf<String, Long>()

        // Populate existing group IDs
        existingGroups.forEach { grp ->
            groupIdByName[grp.name] = grp.id
        }

        // Ensure "default" group always exists
        if (!groupIdByName.containsKey(permissionManager.defaultGroupName)) {
            val defaultGroup = PermissionGroup(
                name = permissionManager.defaultGroupName,
                displayName = permissionManager.defaultGroupName
            )
            val newId = databaseManager.permissionGroupDao.add(defaultGroup, sqlClient)
            groupIdByName[permissionManager.defaultGroupName] = newId
            importedGroupCount++
        }

        // Import selected groups
        for (groupName in selectedGroups) {
            if (!luckPermsData.groups.contains(groupName)) continue

            try {
                val existing = existingGroupByName[groupName]
                if (existing != null) {
                    // Group exists, keep its ID
                    groupIdByName[groupName] = existing.id
                    updatedGroupCount++
                } else {
                    // New group
                    val newGroup = PermissionGroup(
                        name = groupName,
                        displayName = groupName
                    )
                    val newId = databaseManager.permissionGroupDao.add(newGroup, sqlClient)
                    groupIdByName[groupName] = newId
                    importedGroupCount++
                }
            } catch (e: Exception) {
                errors.add(mapOf("item" to "Group: $groupName", "error" to (e.message ?: "Unknown error")))
            }
        }

        // Import group permissions (for selected groups only)
        for (perm in luckPermsData.groupPermissions) {
            val groupName = perm.groupName ?: continue
            if (groupName !in selectedGroups) continue

            val groupId = groupIdByName[groupName] ?: continue

            // Skip expired permissions
            if (perm.expiry > 0 && perm.expiry < System.currentTimeMillis() / 1000) {
                skippedNodeCount++
                continue
            }

            try {
                val context = buildContextJson(perm.server, perm.world, perm.contexts)
                val expiresAt = if (perm.expiry > 0) perm.expiry * 1000 else null // LP stores seconds, Pano stores millis

                val permissionNode = PermissionNode(
                    holderType = HolderType.GROUP,
                    holderId = groupId,
                    node = perm.permission,
                    active = perm.value,
                    context = context,
                    expiresAt = expiresAt
                )

                databaseManager.permissionNodeDao.add(permissionNode, sqlClient)
                importedNodeCount++
            } catch (e: Exception) {
                errors.add(mapOf("item" to "Group perm: ${perm.permission} (${groupName})", "error" to (e.message ?: "Unknown error")))
                skippedNodeCount++
            }
        }

        // Import tracks (for selected tracks only)
        for (track in luckPermsData.tracks) {
            if (track.name !in selectedTracks) continue

            try {
                val trackGroupIds = track.groups.mapNotNull { groupIdByName[it] }
                val permTrack = PermissionTrack(
                    name = track.name,
                    description = "",
                    groupIds = trackGroupIds
                )
                databaseManager.permissionTrackDao.add(permTrack, sqlClient)
                importedTrackCount++
            } catch (e: Exception) {
                errors.add(mapOf("item" to "Track: ${track.name}", "error" to (e.message ?: "Unknown error")))
            }
        }

        // Import user permissions (if enabled)
        if (importUserPermissions) {
            // Build UUID -> username map from LP players
            val uuidToUsername = luckPermsData.players.associateBy({ it.uuid }, { it.username })

            // Get usernames for all UUIDs that have permissions
            val userUuids = luckPermsData.userPermissions.mapNotNull { it.uuid }.distinct()
            val relevantUsernames = userUuids.mapNotNull { uuidToUsername[it] }.distinct()
            val userIdMap = if (relevantUsernames.isNotEmpty()) {
                databaseManager.userDao.getIdsByListOfUsername(relevantUsernames, sqlClient)
            } else {
                emptyMap()
            }

            for (perm in luckPermsData.userPermissions) {
                val uuid = perm.uuid ?: continue
                val username = uuidToUsername[uuid] ?: continue
                val userId = userIdMap[username] ?: continue // Skip if user doesn't exist in Pano

                // Skip expired permissions
                if (perm.expiry > 0 && perm.expiry < System.currentTimeMillis() / 1000) {
                    skippedNodeCount++
                    continue
                }

                // Skip default group assignment nodes (LP already implies them)
                if (perm.permission == "group.${permissionManager.defaultGroupName}") {
                    continue
                }

                try {
                    val context = buildContextJson(perm.server, perm.world, perm.contexts)
                    val expiresAt = if (perm.expiry > 0) perm.expiry * 1000 else null

                    val permissionNode = PermissionNode(
                        holderType = HolderType.USER,
                        holderId = userId,
                        node = perm.permission,
                        active = perm.value,
                        context = context,
                        expiresAt = expiresAt
                    )

                    databaseManager.permissionNodeDao.add(permissionNode, sqlClient)
                    importedUserNodeCount++
                } catch (e: Exception) {
                    errors.add(mapOf("item" to "User perm: ${perm.permission} ($username)", "error" to (e.message ?: "Unknown error")))
                    skippedNodeCount++
                }
            }
        }

        // Refresh permission cache
        permissionManager.refresh()

        // Cleanup temp files
        try {
            File(AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration.mv.db").delete()
            File(AppConstants.TEMP_FOLDER + File.separator + "luckperms_migration_config.yml").delete()
        } catch (_: Exception) {
        }

        return Successful(
            mapOf(
                "importedGroups" to importedGroupCount,
                "updatedGroups" to updatedGroupCount,
                "importedTracks" to importedTrackCount,
                "importedGroupNodes" to importedNodeCount,
                "importedUserNodes" to importedUserNodeCount,
                "skippedNodes" to skippedNodeCount,
                "errors" to errors
            )
        )
    }

    /**
     * Build a Pano-compatible context JSON from LuckPerms server, world, and contexts fields.
     */
    private fun buildContextJson(server: String, world: String, contextsStr: String): JsonObject {
        val context = JsonObject()
        if (server.isNotBlank() && server != "global") {
            context.put("server", server)
        }
        if (world.isNotBlank() && world != "global") {
            context.put("world", world)
        }
        // Parse additional contexts if any
        if (contextsStr.isNotBlank() && contextsStr != "{}" && contextsStr != "{}") {
            try {
                val parsed = JsonObject(contextsStr)
                parsed.forEach { (key, value) ->
                    if (!context.containsKey(key)) {
                        context.put(key, value)
                    }
                }
            } catch (_: Exception) {
                // Ignore malformed contexts
            }
        }
        return context
    }

    private suspend fun readFromH2(dbPath: String, tablePrefix: String): PanelLuckPermsMigrationUploadAPI.LuckPermsData {
        return vertx.executeBlocking {
            // H2 requires an absolute path
            val absolutePath = File(dbPath).absolutePath
            val jdbcPath = if (absolutePath.endsWith(".mv.db")) {
                absolutePath.removeSuffix(".mv.db")
            } else {
                absolutePath
            }

            // Explicitly load the H2 JDBC driver — required in fat/shadow JAR builds
            try {
                Class.forName("org.h2.Driver")
            } catch (e: ClassNotFoundException) {
                throw InvalidData(extras = mapOf("message" to "H2 database driver not found. Please ensure H2 is included in the build."))
            }

            val conn = try {
                DriverManager.getConnection(
                    "jdbc:h2:$jdbcPath;MODE=MySQL;DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;ACCESS_MODE_DATA=r",
                    "",
                    ""
                )
            } catch (e: Exception) {
                throw InvalidData(extras = mapOf("message" to "Failed to open H2 database: ${e.message}"))
            }

            conn.use { connection ->
                readFromJdbc(connection, tablePrefix)
            }
        }.coAwait()
    }

    private suspend fun readFromMySQL(
        host: String, port: Int, dbName: String,
        username: String, password: String, tablePrefix: String
    ): PanelLuckPermsMigrationUploadAPI.LuckPermsData {
        val connectOptions = MySQLConnectOptions()
            .setHost(host)
            .setPort(port)
            .setDatabase(dbName)
            .setUser(username)
            .setPassword(password)

        val poolOptions = io.vertx.sqlclient.PoolOptions().setMaxSize(1)

        val client = MySQLBuilder.pool()
            .with(poolOptions)
            .connectingTo(connectOptions)
            .using(vertx)
            .build()

        return try {
            val data = PanelLuckPermsMigrationUploadAPI.LuckPermsData()

            val groupRows = client.preparedQuery("SELECT * FROM `${tablePrefix}groups`")
                .execute(Tuple.tuple()).coAwait()
            for (row in groupRows) {
                val name = try { row.getString("name") } catch (_: Exception) { null }
                if (name != null) data.groups.add(name)
            }

            val trackRows = client.preparedQuery("SELECT * FROM `${tablePrefix}tracks`")
                .execute(Tuple.tuple()).coAwait()
            for (row in trackRows) {
                val name = try { row.getString("name") } catch (_: Exception) { null }
                val groups = try { row.getString("groups") } catch (_: Exception) { null }
                if (name != null) {
                    data.tracks.add(PanelLuckPermsMigrationUploadAPI.LPTrack(name, parseTrackGroups(groups)))
                }
            }

            val playerRows = client.preparedQuery("SELECT * FROM `${tablePrefix}players`")
                .execute(Tuple.tuple()).coAwait()
            for (row in playerRows) {
                val uuid = try { row.getString("uuid") } catch (_: Exception) { null }
                val un = try { row.getString("username") } catch (_: Exception) { null }
                val pg = try { row.getString("primary_group") } catch (_: Exception) { "default" }
                if (uuid != null && un != null) {
                    data.players.add(PanelLuckPermsMigrationUploadAPI.LPPlayer(uuid, un, pg ?: "default"))
                }
            }

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
                    data.groupPermissions.add(PanelLuckPermsMigrationUploadAPI.LPPermission(name, null, permission, value ?: true, server ?: "global", world ?: "global", expiry ?: 0L, contexts ?: "{}"))
                }
            }

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
                    data.userPermissions.add(PanelLuckPermsMigrationUploadAPI.LPPermission(null, uuid, permission, value ?: true, server ?: "global", world ?: "global", expiry ?: 0L, contexts ?: "{}"))
                }
            }

            data
        } finally {
            client.close().coAwait()
        }
    }

    private fun readFromJdbc(connection: java.sql.Connection, tablePrefix: String): PanelLuckPermsMigrationUploadAPI.LuckPermsData {
        val data = PanelLuckPermsMigrationUploadAPI.LuckPermsData()
        val stmt = connection.createStatement()

        try {
            val groupRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}groups`")
            while (groupRs.next()) {
                val name = tryGetString(groupRs, "name")
                if (name != null) data.groups.add(name)
            }
        } catch (_: Exception) {}

        try {
            val trackRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}tracks`")
            while (trackRs.next()) {
                val name = tryGetString(trackRs, "name")
                val groups = tryGetString(trackRs, "groups")
                if (name != null) {
                    data.tracks.add(PanelLuckPermsMigrationUploadAPI.LPTrack(name, parseTrackGroups(groups)))
                }
            }
        } catch (_: Exception) {}

        try {
            val playerRs = stmt.executeQuery("SELECT * FROM `${tablePrefix}players`")
            while (playerRs.next()) {
                val uuid = tryGetString(playerRs, "uuid")
                val username = tryGetString(playerRs, "username")
                val primaryGroup = tryGetString(playerRs, "primary_group") ?: "default"
                if (uuid != null && username != null) {
                    data.players.add(PanelLuckPermsMigrationUploadAPI.LPPlayer(uuid, username, primaryGroup))
                }
            }
        } catch (_: Exception) {}

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
                    data.groupPermissions.add(PanelLuckPermsMigrationUploadAPI.LPPermission(name, null, permission, value, server, world, expiry, contexts))
                }
            }
        } catch (_: Exception) {}

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
                    data.userPermissions.add(PanelLuckPermsMigrationUploadAPI.LPPermission(null, uuid, permission, value, server, world, expiry, contexts))
                }
            }
        } catch (_: Exception) {}

        return data
    }

    private fun parseTrackGroups(groups: String?): List<String> {
        if (groups.isNullOrBlank()) return emptyList()
        return try {
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
        return try { rs.getString(column) } catch (_: Exception) { null }
    }

    private fun tryGetLong(rs: java.sql.ResultSet, column: String): Long? {
        return try {
            val value = rs.getLong(column)
            if (rs.wasNull()) null else value
        } catch (_: Exception) { null }
    }

    private fun tryGetBoolean(rs: java.sql.ResultSet, column: String): Boolean? {
        return try {
            val value = rs.getBoolean(column)
            if (rs.wasNull()) null else value
        } catch (_: Exception) { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getNestedMap(map: Map<String, Any>?, key: String): Map<String, Any>? {
        return map?.get(key) as? Map<String, Any>
    }
}
