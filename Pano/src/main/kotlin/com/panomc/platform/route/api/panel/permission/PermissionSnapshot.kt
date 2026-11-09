package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.auth.PermissionNodeContext
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.PermissionTrack
import com.panomc.platform.error.InvalidData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * The whole permission grid (groups, tracks, nodes) as the permission editor saves it, with group holders named
 * instead of numbered: group ids change on every save, names do not.
 *
 * [parse] checks the entire payload before the save touches a table, so a refused payload leaves the grid as it was.
 */
internal class PermissionSnapshot(
    val groups: List<Group>,
    val tracks: List<Track>,
    val nodes: List<Node>
) {
    data class Group(val name: String, val displayName: String, val createdAt: Long, val updatedAt: Long)

    data class Track(
        val name: String,
        val description: String,
        val groupNames: List<String>,
        val createdAt: Long,
        val updatedAt: Long
    )

    /** [holderId] is the user id for a USER holder; a GROUP holder is identified by [groupName] only. */
    data class Node(
        val holderType: HolderType,
        val holderId: Long,
        val groupName: String?,
        val node: String,
        val active: Boolean,
        val context: JsonObject,
        val expiresAt: Long?,
        val createdAt: Long,
        val updatedAt: Long
    )

    companion object {
        const val MAX_GROUPS = 1_000
        const val MAX_TRACKS = 1_000
        const val MAX_NODES = 20_000
        const val MAX_GROUP_NAME_LENGTH = 32
        const val MAX_DISPLAY_NAME_LENGTH = 64
        const val MAX_TRACK_NAME_LENGTH = 128
        const val MAX_NODE_LENGTH = 255

        /** Throws [InvalidData] for anything the tables would refuse or that cannot be read. */
        fun parse(body: JsonObject, defaultGroupName: String, now: Long = System.currentTimeMillis()): PermissionSnapshot =
            try {
                parseUnchecked(body, defaultGroupName, now)
            } catch (e: ClassCastException) {
                // getLong / getString / getJsonArray on a value of the wrong JSON type
                throw InvalidData()
            } catch (e: IllegalArgumentException) {
                // an unknown holderType
                throw InvalidData()
            }

        private fun parseUnchecked(body: JsonObject, defaultGroupName: String, now: Long): PermissionSnapshot {
            val incomingGroups = body.getJsonArray("groups") ?: JsonArray()
            val incomingTracks = body.getJsonArray("tracks") ?: JsonArray()
            val incomingNodes = body.getJsonArray("nodes") ?: JsonArray()

            if (incomingGroups.size() > MAX_GROUPS || incomingTracks.size() > MAX_TRACKS || incomingNodes.size() > MAX_NODES) {
                throw InvalidData()
            }

            // Group ids the browser holds are the ids of the current rows: a node may name its group by that id.
            val groupNameByPayloadId = mutableMapOf<Long, String>()

            val groups = mutableListOf<Group>()
            incomingGroups.forEach { el ->
                val obj = el as? JsonObject ?: throw InvalidData()
                val name = obj.getString("name") ?: throw InvalidData()
                val displayName = obj.getString("displayName") ?: name

                if (name.isBlank() || name.length > MAX_GROUP_NAME_LENGTH || displayName.length > MAX_DISPLAY_NAME_LENGTH) {
                    throw InvalidData()
                }

                if (groups.any { it.name == name }) {
                    throw InvalidData()
                }

                obj.getLong("id")?.let { groupNameByPayloadId[it] = name }

                groups.add(Group(name, displayName, obj.getLong("createdAt") ?: now, obj.getLong("updatedAt") ?: now))
            }

            if (groups.none { it.name == defaultGroupName }) {
                groups.add(Group(defaultGroupName, defaultGroupName, now, now))
            }

            val groupNames = groups.map { it.name }.toSet()

            val tracks = mutableListOf<Track>()
            incomingTracks.forEach { el ->
                val obj = el as? JsonObject ?: throw InvalidData()
                val name = obj.getString("name") ?: throw InvalidData()

                if (name.isBlank() || name.length > MAX_TRACK_NAME_LENGTH || tracks.any { it.name == name }) {
                    throw InvalidData()
                }

                val trackGroups = (obj.getJsonArray("groupNames") ?: JsonArray())
                    .mapNotNull { it as? String }
                    .filter { it in groupNames }

                tracks.add(
                    Track(
                        name = name,
                        description = obj.getString("description") ?: "",
                        groupNames = trackGroups,
                        createdAt = obj.getLong("createdAt") ?: now,
                        updatedAt = obj.getLong("updatedAt") ?: now
                    )
                )
            }

            val nodes = mutableListOf<Node>()
            incomingNodes.forEach { el ->
                val obj = el as? JsonObject ?: throw InvalidData()

                // Refused rather than dropped: a picker that quietly saved no scope would hand
                // somebody the whole estate when they meant to grant two servers.
                val context = PermissionNodeContext.sanitize(obj.getValue("context")) ?: throw InvalidData()

                val holderTypeStr = obj.getString("holderType") ?: return@forEach
                val holderType = HolderType.valueOf(holderTypeStr)
                val holderId = obj.getLong("holderId") ?: return@forEach
                val nodeStr = obj.getString("node") ?: return@forEach
                val active = obj.getBoolean("active") ?: true
                val expiresAt = obj.getLong("expiresAt")

                if (nodeStr.isEmpty() || nodeStr.length > MAX_NODE_LENGTH || nodeStr.any { it.isWhitespace() }) {
                    throw InvalidData()
                }

                // Everybody is in the default group without a node saying so.
                if (holderType == HolderType.USER && nodeStr == "group.$defaultGroupName" && active) {
                    return@forEach
                }

                val groupName = if (holderType == HolderType.GROUP) {
                    (obj.getString("holderName")?.takeIf { it in groupNames } ?: groupNameByPayloadId[holderId])
                        ?: return@forEach
                } else null

                if (expiresAt != null && expiresAt < now) {
                    return@forEach
                }

                nodes.add(
                    Node(
                        holderType = holderType,
                        holderId = if (holderType == HolderType.USER) holderId else -1,
                        groupName = groupName,
                        node = nodeStr,
                        active = active,
                        context = context,
                        expiresAt = expiresAt,
                        createdAt = obj.getLong("createdAt") ?: now,
                        updatedAt = obj.getLong("updatedAt") ?: now
                    )
                )
            }

            return PermissionSnapshot(groups, tracks, nodes)
        }

        /** The grid as it is stored, in the same shape, so it can be compared with a payload or written back. */
        fun fromRows(
            groups: List<PermissionGroup>,
            tracks: List<PermissionTrack>,
            nodes: List<PermissionNode>
        ): PermissionSnapshot {
            val nameById = groups.associateBy({ it.id }, { it.name })

            return PermissionSnapshot(
                groups = groups.map { Group(it.name, it.displayName, it.createdAt, it.updatedAt) },
                tracks = tracks.map { track ->
                    Track(track.name, track.description, track.groupIds.mapNotNull { nameById[it] }, track.createdAt, track.updatedAt)
                },
                nodes = nodes.mapNotNull { node ->
                    val groupName = if (node.holderType == HolderType.GROUP) nameById[node.holderId] ?: return@mapNotNull null else null

                    Node(
                        holderType = node.holderType,
                        holderId = if (node.holderType == HolderType.USER) node.holderId else -1,
                        groupName = groupName,
                        node = node.node,
                        active = node.active,
                        context = node.context,
                        expiresAt = node.expiresAt,
                        createdAt = node.createdAt,
                        updatedAt = node.updatedAt
                    )
                }
            )
        }
    }
}
