package com.panomc.platform.server.event

import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import io.vertx.core.json.JsonObject

/**
 * Decides which permission rows a connected Minecraft server may write through a permissions
 * snapshot.
 *
 * The permission tables are shared: LuckPerms data synced from a server lives next to the nodes
 * the panel authorizes with. A server (or whoever holds its token) must never be able to grant
 * panel access, so everything that can change what a user may do on the panel is owned by the
 * panel alone:
 *
 * - "panel nodes": `*`, `**`, and every node whose first segment is `pano` or a wildcard,
 *   unless the node carries the explicit `pano=false` context (then the panel ignores it);
 * - "panel groups": groups that hold a panel node, or inherit (`group.<name>`) such a group;
 * - every node held by a panel group, and every `group.<panel group>` membership/inheritance node.
 *
 * Incoming rows matching these are dropped, and the existing ones are kept as they are.
 */
object ServerPermissionSyncGuard {
    private const val GROUP_PREFIX = "group."

    fun isPanelNode(node: String, context: JsonObject): Boolean {
        if (!isPanoContextAllowed(context)) return false
        val first = node.trim().substringBefore('.').lowercase()
        return first == "*" || first == "**" || first == "pano"
    }

    fun groupNameOf(node: String): String? =
        if (node.startsWith(GROUP_PREFIX)) node.removePrefix(GROUP_PREFIX) else null

    /** Names of the groups that hold, directly or through inheritance, a panel node. */
    fun panelGroups(groups: List<PermissionGroup>, nodes: List<PermissionNode>): Set<String> {
        val nameById = groups.associate { it.id to it.name }
        val groupNodes = nodes.filter { it.holderType == HolderType.GROUP }

        val result = groupNodes
            .filter { isPanelNode(it.node, it.context) }
            .mapNotNull { nameById[it.holderId] }
            .toMutableSet()

        var added = true
        while (added) {
            added = false
            groupNodes.forEach { node ->
                val holderName = nameById[node.holderId] ?: return@forEach
                if (holderName in result) return@forEach
                val inherited = groupNameOf(node.node) ?: return@forEach
                if (inherited in result) {
                    result.add(holderName)
                    added = true
                }
            }
        }

        return result
    }

    /**
     * Whether a row is owned by the panel. [holderGroupName] is the holder group's name for GROUP
     * holders, null for USER holders.
     */
    fun isProtected(
        holderType: HolderType,
        holderGroupName: String?,
        node: String,
        context: JsonObject,
        panelGroups: Set<String>
    ): Boolean {
        if (holderType == HolderType.GROUP && holderGroupName != null && holderGroupName in panelGroups) return true
        if (isPanelNode(node, context)) return true
        val target = groupNameOf(node)
        return target != null && target in panelGroups
    }

    // Same rule as PermissionManager: an explicit pano=false context hides the node from the panel.
    private fun isPanoContextAllowed(context: JsonObject): Boolean {
        return when (val value = context.getValue("pano")) {
            null -> true
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> true
        }
    }
}
