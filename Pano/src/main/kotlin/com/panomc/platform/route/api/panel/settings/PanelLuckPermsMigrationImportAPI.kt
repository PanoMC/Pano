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
import com.panomc.platform.db.model.User
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.NoPermission
import com.panomc.platform.model.*
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.mysqlclient.MySQLException
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
                        .optionalProperty("createMissingPlayers", booleanSchema())
                        .optionalProperty("mergeStrategy", stringSchema()) // 'replace' or 'merge'
                        .optionalProperty("nodeEdits", arraySchema().items(objectSchema()))
                        .optionalProperty("playerEdits", arraySchema().items(objectSchema()))
                        .optionalProperty("skippedPlayers", arraySchema().items(stringSchema()))
                        .optionalProperty("deletedExistingNodes", arraySchema().items(numberSchema()))
                        .optionalProperty("trackEdits", arraySchema().items(objectSchema()))
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
        val createMissingPlayers = body.getBoolean("createMissingPlayers") ?: true
        val mergeStrategy = body.getString("mergeStrategy") ?: "merge" // 'replace' or 'merge'
        val nodeEdits = body.getJsonArray("nodeEdits")?.mapNotNull { it as? JsonObject } ?: emptyList()

        // The admin can retarget a LuckPerms player at a different Pano username on the review
        // screen — used to fix a rename or a spelling mismatch before anything is written.
        val playerUsernameOverrides = body.getJsonArray("playerEdits")
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { edit ->
                val uuid = edit.getString("uuid")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val username = edit.getString("username")?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                uuid to username
            }
            ?.toMap() ?: emptyMap()

        // Players the admin explicitly excluded from the import, by LuckPerms UUID.
        val skippedPlayerUuids = body.getJsonArray("skippedPlayers")
            ?.mapNotNull { it as? String }
            ?.toSet() ?: emptySet()

        // Existing Pano nodes the admin deleted on the review screen.
        val deletedExistingNodeIds = body.getJsonArray("deletedExistingNodes")
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?.distinct() ?: emptyList()

        // Per-track overrides: a reordered or trimmed group chain, and a description.
        val trackEdits = body.getJsonArray("trackEdits")
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { edit ->
                val name = edit.getString("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                name to edit
            }
            ?.toMap() ?: emptyMap()

        // Group names are matched case-insensitively throughout, mirroring the database collation.
        val selectedGroupsLowercase = selectedGroups.map { it.lowercase() }.toSet()

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

        val dataSection = getNestedMap(configMap, "data")

        // Prefer the settings the upload step actually connected with — they may include panel
        // overrides (host, port, credentials, table prefix) that config.yml alone does not carry.
        val sourceInfo = readSourceInfo()

        val storageMethod = sourceInfo?.getString("storageMethod")?.uppercase()
            ?: (configMap["storage-method"] as? String)?.uppercase() ?: "H2"
        val tablePrefix = sourceInfo?.getString("tablePrefix")
            ?: (dataSection?.get("table-prefix") as? String) ?: "luckperms_"

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
                // LuckPerms writes `address` as "host" or "host:port".
                val address = (dataSection?.get("address") as? String) ?: "localhost"
                val addressParts = address.split(":")

                val host = sourceInfo?.getString("host") ?: addressParts[0].ifBlank { "localhost" }
                val port = sourceInfo?.getInteger("port")
                    ?: addressParts.getOrNull(1)?.toIntOrNull() ?: 3306
                val dbName = sourceInfo?.getString("database")
                    ?: (dataSection?.get("database") as? String) ?: "minecraft"
                val username = sourceInfo?.getString("username")
                    ?: (dataSection?.get("username") as? String) ?: "root"
                val password = sourceInfo?.getString("password")
                    ?: (dataSection?.get("password") as? String) ?: ""

                readFromMySQL(host, port, dbName, username, password, tablePrefix)
            }

            else -> throw InvalidData(extras = mapOf("message" to "Unsupported storage method: $storageMethod"))
        }

        // Apply the admin's review-step edits (added, changed and removed nodes) on top of the
        // freshly read source data before anything is written.
        LuckPermsNodeEditor.apply(luckPermsData, nodeEdits)

        val sqlClient = getSqlClient()
        var importedGroupCount = 0
        var updatedGroupCount = 0
        var importedTrackCount = 0
        var updatedTrackCount = 0
        var skippedTrackGroupCount = 0
        var importedNodeCount = 0
        var importedUserNodeCount = 0
        var overwrittenNodeCount = 0
        var deletedNodeCount = 0
        var skippedNodeCount = 0
        var createdUserCount = 0
        var skippedUserCount = 0
        val errors = mutableListOf<Map<String, String>>()

        // If replace mode, clear existing permission data
        if (mergeStrategy == "replace") {
            val prefix = databaseManager.getTablePrefix()
            sqlClient.query("DELETE FROM `${prefix}permission_node`").execute().coAwait()
            sqlClient.query("DELETE FROM `${prefix}permission_track`").execute().coAwait()
            sqlClient.query("DELETE FROM `${prefix}permission_group`").execute().coAwait()
        }

        // In merge mode a node that already exists for the same holder is replaced rather than
        // duplicated, so the "will overwrite" status shown during review is what actually happens
        // and re-running an import stays idempotent.
        val existingNodesByHolder = if (mergeStrategy == "replace") {
            emptyMap()
        } else {
            databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)
                .groupBy { Triple(it.holderType, it.holderId, it.node) }
        }

        // Existing nodes the admin removed on the review screen. In replace mode everything is gone
        // already, so there is nothing left to delete.
        val alreadyDeletedNodeIds = mutableSetOf<Long>()

        if (mergeStrategy != "replace" && deletedExistingNodeIds.isNotEmpty()) {
            databaseManager.permissionNodeDao.deleteByIds(deletedExistingNodeIds, sqlClient)
            alreadyDeletedNodeIds.addAll(deletedExistingNodeIds)
            deletedNodeCount += deletedExistingNodeIds.size
        }

        // Get existing groups for merge mode
        val existingGroups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val existingGroupByName = existingGroups.associateBy { it.name.lowercase() }

        // Map to store group name -> Pano group ID
        val groupIdByName = mutableMapOf<String, Long>()

        // Populate existing group IDs
        existingGroups.forEach { grp ->
            groupIdByName[grp.name.lowercase()] = grp.id
        }

        // Ensure "default" group always exists
        if (!groupIdByName.containsKey(permissionManager.defaultGroupName.lowercase())) {
            val defaultGroup = PermissionGroup(
                name = permissionManager.defaultGroupName,
                displayName = permissionManager.defaultGroupName
            )
            val newId = databaseManager.permissionGroupDao.add(defaultGroup, sqlClient)
            groupIdByName[permissionManager.defaultGroupName.lowercase()] = newId
            importedGroupCount++
        }

        // Import selected groups
        for (groupName in selectedGroups) {
            if (!luckPermsData.groups.contains(groupName)) continue

            try {
                val existing = existingGroupByName[groupName.lowercase()]
                if (existing != null) {
                    // Group exists, keep its ID
                    groupIdByName[groupName.lowercase()] = existing.id
                    updatedGroupCount++
                } else {
                    // New group
                    val newGroup = PermissionGroup(
                        name = groupName,
                        displayName = groupName
                    )
                    val newId = databaseManager.permissionGroupDao.add(newGroup, sqlClient)
                    groupIdByName[groupName.lowercase()] = newId
                    importedGroupCount++
                }
            } catch (e: Exception) {
                errors.add(mapOf("item" to "Group: $groupName", "error" to (e.message ?: "Unknown error")))
            }
        }

        val now = System.currentTimeMillis()

        // Drop the Pano group nodes that the incoming permissions replace, so merge mode overwrites
        // instead of piling up duplicates.
        val replacedGroupNodeIds = mutableListOf<Long>()

        for (perm in luckPermsData.groupPermissions) {
            val groupName = perm.groupName ?: continue
            if (groupName.lowercase() !in selectedGroupsLowercase) continue
            if (perm.expiry > 0 && perm.expiry < now / 1000) continue

            val groupId = groupIdByName[groupName.lowercase()] ?: continue

            existingNodesByHolder[Triple(HolderType.GROUP, groupId, perm.permission)]
                ?.forEach { replacedGroupNodeIds.add(it.id) }
        }

        // Anything the admin already deleted above must not be counted or deleted a second time.
        val replacedGroupNodeIdsToDelete = replacedGroupNodeIds.distinct() - alreadyDeletedNodeIds

        if (replacedGroupNodeIdsToDelete.isNotEmpty()) {
            databaseManager.permissionNodeDao.deleteByIds(replacedGroupNodeIdsToDelete, sqlClient)
            alreadyDeletedNodeIds.addAll(replacedGroupNodeIdsToDelete)
            overwrittenNodeCount += replacedGroupNodeIdsToDelete.size
        }

        // Import group permissions (for selected groups only)
        for (perm in luckPermsData.groupPermissions) {
            val groupName = perm.groupName ?: continue
            if (groupName.lowercase() !in selectedGroupsLowercase) continue

            val groupId = groupIdByName[groupName.lowercase()] ?: continue

            // Skip expired permissions
            if (perm.expiry > 0 && perm.expiry < now / 1000) {
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
        val existingTrackByName = databaseManager.permissionTrackDao.getAll(sqlClient).associateBy { it.name }

        for (track in luckPermsData.tracks) {
            if (track.name !in selectedTracks) continue

            try {
                val edit = trackEdits[track.name]
                val groupNames = edit?.getJsonArray("groups")?.mapNotNull { it as? String } ?: track.groups
                val description = edit?.getString("description") ?: ""

                val trackGroupIds = groupNames.mapNotNull { groupIdByName[it.lowercase()] }

                // A track is an ordered chain of groups. Any group that is not part of this import
                // has no id to point at and silently disappears from the chain, which quietly
                // reorders promotions — so say which ones were dropped instead of hiding it.
                val droppedGroups = groupNames.filter { groupIdByName[it.lowercase()] == null }

                if (droppedGroups.isNotEmpty()) {
                    skippedTrackGroupCount += droppedGroups.size
                    errors.add(
                        mapOf(
                            "item" to "Track: ${track.name}",
                            "error" to "Groups not imported, left out of the track order: ${droppedGroups.joinToString(", ")}"
                        )
                    )
                }

                val existing = existingTrackByName[track.name]

                if (existing == null) {
                    databaseManager.permissionTrackDao.add(
                        PermissionTrack(name = track.name, description = description, groupIds = trackGroupIds),
                        sqlClient
                    )
                    importedTrackCount++
                } else {
                    // The name column is UNIQUE, so re-adding an existing track fails outright.
                    // Overwrite its chain instead of erroring out on every re-import.
                    databaseManager.permissionTrackDao.update(
                        existing.copy(
                            description = description.ifBlank { existing.description },
                            groupIds = trackGroupIds,
                            updatedAt = now
                        ),
                        sqlClient
                    )
                    updatedTrackCount++
                }
            } catch (e: Exception) {
                errors.add(mapOf("item" to "Track: ${track.name}", "error" to (e.message ?: "Unknown error")))
            }
        }

        // Import user permissions (if enabled)
        if (importUserPermissions) {
            val defaultGroupNode = "group.${permissionManager.defaultGroupName}"

            // Build UUID -> username map from LP players
            val uuidToUsername = luckPermsData.players.associateBy({ it.uuid }, { it.username })

            // Everything the import would actually write, keyed by player. Expired entries are dead,
            // and an *active* group.default is already implied by Pano — both snapshot paths strip
            // it, so storing it would only be undone on the next sync. A negated group.default is
            // meaningful, though, and must survive.
            val isImpliedDefault = { perm: PanelLuckPermsMigrationUploadAPI.LPPermission ->
                perm.permission == defaultGroupNode && perm.value
            }

            val allImportableNodes = luckPermsData.userPermissions
                .filter { it.uuid != null && !isImpliedDefault(it) }
                .filter { it.expiry <= 0 || it.expiry >= now / 1000 }
                .groupBy { it.uuid!! }

            // Rows the panel showed for Pano users that are not in the LuckPerms export at all carry
            // a synthetic "pano:<userId>" key instead of a real uuid. They are written straight to
            // that user rather than going through the LuckPerms player matching below.
            val (panoHolderNodes, importableNodesByUuid) = allImportableNodes.entries
                .partition { it.key.startsWith(PanelLuckPermsMigrationUploadAPI.PANO_HOLDER_PREFIX) }
                .let { (pano, lp) -> pano.associate { it.key to it.value } to lp.associate { it.key to it.value } }

            skippedNodeCount += luckPermsData.userPermissions.count {
                it.uuid != null && !isImpliedDefault(it) &&
                        it.expiry > 0 && it.expiry < now / 1000
            }

            // Write the edits made against existing Pano users. Only ids that really exist are
            // accepted, so a bad key cannot leave orphan permission rows behind.
            val panoHolderIds = panoHolderNodes.keys
                .mapNotNull { it.removePrefix(PanelLuckPermsMigrationUploadAPI.PANO_HOLDER_PREFIX).toLongOrNull() }
                .distinct()

            val knownPanoHolderIds = if (panoHolderIds.isEmpty()) {
                emptySet()
            } else {
                databaseManager.userDao.getUsernameByListOfId(panoHolderIds, sqlClient).keys
            }

            panoHolderNodes.forEach { (holderKey, nodes) ->
                val userId = holderKey.removePrefix(PanelLuckPermsMigrationUploadAPI.PANO_HOLDER_PREFIX)
                    .toLongOrNull()

                if (userId == null || userId !in knownPanoHolderIds) {
                    skippedNodeCount += nodes.size
                    return@forEach
                }

                val replacedIds = nodes
                    .flatMap { perm ->
                        existingNodesByHolder[Triple(HolderType.USER, userId, perm.permission)]
                            ?.map { it.id } ?: emptyList()
                    }
                    .distinct() - alreadyDeletedNodeIds

                if (replacedIds.isNotEmpty()) {
                    databaseManager.permissionNodeDao.deleteByIds(replacedIds, sqlClient)
                    alreadyDeletedNodeIds.addAll(replacedIds)
                    overwrittenNodeCount += replacedIds.size
                }

                nodes.forEach { perm ->
                    try {
                        databaseManager.permissionNodeDao.add(
                            PermissionNode(
                                holderType = HolderType.USER,
                                holderId = userId,
                                node = perm.permission,
                                active = perm.value,
                                context = buildContextJson(perm.server, perm.world, perm.contexts),
                                expiresAt = if (perm.expiry > 0) perm.expiry * 1000 else null
                            ),
                            sqlClient
                        )
                        importedUserNodeCount++
                    } catch (e: Exception) {
                        errors.add(
                            mapOf(
                                "item" to "User perm: ${perm.permission} (#$userId)",
                                "error" to (e.message ?: "Unknown error")
                            )
                        )
                        skippedNodeCount++
                    }
                }
            }

            // Players worth touching: they either carry a permission node, or a primary group that
            // is part of this import. LuckPerms normally also stores the primary group as a
            // group.<name> node, but not every backend does — importing it explicitly is what makes
            // an in-game player's rank actually survive the migration.
            // Nodes the admin added by hand were folded into the source data by applyNodeEdits, so
            // they are already accounted for here.
            val relevantPlayers = luckPermsData.players.filter { player ->
                if (player.uuid in skippedPlayerUuids) {
                    return@filter false
                }

                importableNodesByUuid.containsKey(player.uuid) ||
                        (player.primaryGroup != permissionManager.defaultGroupName &&
                                player.primaryGroup.lowercase() in selectedGroupsLowercase)
            }

            // Whatever the admin retargeted on the review screen wins over the LuckPerms spelling.
            val usernameOf = { player: PanelLuckPermsMigrationUploadAPI.LPPlayer ->
                playerUsernameOverrides[player.uuid] ?: player.username
            }

            // MySQL matches usernames case-insensitively but returns them as stored, so both sides
            // are normalised — otherwise a casing difference would look like a missing player.
            val userIdByUsername = mutableMapOf<String, Long>()

            if (relevantPlayers.isNotEmpty()) {
                databaseManager.userDao
                    .getIdsByListOfUsername(relevantPlayers.map(usernameOf).distinct(), sqlClient)
                    .forEach { (username, id) -> userIdByUsername[username.lowercase()] = id }
            }

            // Create the players LuckPerms knows about but Pano does not. Without this the import is
            // pointless for servers whose players never registered on the website: their in-game
            // ranks would silently go nowhere.
            if (createMissingPlayers) {
                for (player in relevantPlayers) {
                    val username = usernameOf(player)

                    if (userIdByUsername.containsKey(username.lowercase())) continue

                    try {
                        val newUser = User(
                            username = username,
                            email = null,
                            registeredIp = "",
                            registerDate = now,
                            lastLoginDate = now,
                            mcUuid = player.uuid
                        )

                        val newId = try {
                            databaseManager.userDao.add(newUser, null, sqlClient, false)
                        } catch (e: MySQLException) {
                            // A join or register flow may have created the row in the meantime.
                            if (e.errorCode == 1062) {
                                databaseManager.userDao.getUserIdFromUsername(username, sqlClient)
                                    ?: throw e
                            } else {
                                throw e
                            }
                        }

                        userIdByUsername[username.lowercase()] = newId
                        createdUserCount++
                    } catch (e: Exception) {
                        errors.add(
                            mapOf(
                                "item" to "Player: $username",
                                "error" to (e.message ?: "Unknown error")
                            )
                        )
                    }
                }
            }

            // Whatever is still unresolved at this point is genuinely skipped — either because
            // creating players was turned off, or because creating that one failed above.
            skippedUserCount += relevantPlayers.count {
                !userIdByUsername.containsKey(usernameOf(it).lowercase())
            }

            // Nodes belonging to players that were excluded or could not be resolved are dropped.
            val importedUuids = relevantPlayers
                .filter { userIdByUsername.containsKey(usernameOf(it).lowercase()) }
                .map { it.uuid }
                .toSet()

            importableNodesByUuid.forEach { (uuid, nodes) ->
                if (uuid !in importedUuids) {
                    skippedNodeCount += nodes.size
                }
            }

            // Collect every user node to write, including the synthesised primary-group node.
            val userNodesToImport = mutableListOf<Pair<Long, PanelLuckPermsMigrationUploadAPI.LPPermission>>()

            for (player in relevantPlayers) {
                val userId = userIdByUsername[usernameOf(player).lowercase()] ?: continue
                val playerNodes = importableNodesByUuid[player.uuid] ?: emptyList()

                playerNodes.forEach { userNodesToImport.add(userId to it) }

                val primaryGroupNode = "group.${player.primaryGroup}"
                val needsPrimaryGroup = player.primaryGroup != permissionManager.defaultGroupName &&
                        player.primaryGroup.lowercase() in selectedGroupsLowercase &&
                        playerNodes.none { it.permission == primaryGroupNode }

                if (needsPrimaryGroup) {
                    userNodesToImport.add(
                        userId to PanelLuckPermsMigrationUploadAPI.LPPermission(
                            groupName = null,
                            uuid = player.uuid,
                            permission = primaryGroupNode,
                            value = true,
                            server = "global",
                            world = "global",
                            expiry = 0L,
                            contexts = "{}"
                        )
                    )
                }
            }

            // Overwrite rather than duplicate, same as for group nodes.
            val replacedUserNodeIds = userNodesToImport
                .flatMap { (userId, perm) ->
                    existingNodesByHolder[Triple(HolderType.USER, userId, perm.permission)]
                        ?.map { it.id } ?: emptyList()
                }
                .distinct() - alreadyDeletedNodeIds

            if (replacedUserNodeIds.isNotEmpty()) {
                databaseManager.permissionNodeDao.deleteByIds(replacedUserNodeIds, sqlClient)
                alreadyDeletedNodeIds.addAll(replacedUserNodeIds)
                overwrittenNodeCount += replacedUserNodeIds.size
            }

            for ((userId, perm) in userNodesToImport) {
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
                    val username = perm.uuid?.let { uuidToUsername[it] } ?: perm.uuid
                    errors.add(
                        mapOf(
                            "item" to "User perm: ${perm.permission} ($username)",
                            "error" to (e.message ?: "Unknown error")
                        )
                    )
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
            File(
                AppConstants.TEMP_FOLDER + File.separator +
                        PanelLuckPermsMigrationUploadAPI.SOURCE_INFO_FILE_NAME
            ).delete()
        } catch (_: Exception) {
        }

        return Successful(
            mapOf(
                "importedGroups" to importedGroupCount,
                "updatedGroups" to updatedGroupCount,
                "importedTracks" to importedTrackCount,
                "updatedTracks" to updatedTrackCount,
                "skippedTrackGroups" to skippedTrackGroupCount,
                "importedGroupNodes" to importedNodeCount,
                "importedUserNodes" to importedUserNodeCount,
                "overwrittenNodes" to overwrittenNodeCount,
                "deletedNodes" to deletedNodeCount,
                "skippedNodes" to skippedNodeCount,
                "createdUsers" to createdUserCount,
                "skippedUsers" to skippedUserCount,
                "errors" to errors
            )
        )
    }

    /**
     * Read the connection settings the upload step recorded, if they are still around.
     */
    private fun readSourceInfo(): JsonObject? {
        return try {
            val file = File(
                AppConstants.TEMP_FOLDER + File.separator +
                        PanelLuckPermsMigrationUploadAPI.SOURCE_INFO_FILE_NAME
            )

            if (file.exists()) JsonObject(file.readText()) else null
        } catch (_: Exception) {
            null
        }
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
        if (contextsStr.isNotBlank() && contextsStr != "{}") {
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
