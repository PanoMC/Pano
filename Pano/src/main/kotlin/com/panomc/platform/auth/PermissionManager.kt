package com.panomc.platform.auth

import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PermissionManager(
    private val databaseManager: DatabaseManager,
    private val permissionRegistry: PermissionRegistry
) {
    // In-memory snapshot of groups + nodes; refreshed lazily after TTL.
    private data class Cache(
        val fetchedAt: Long,
        val groupsById: Map<Long, PermissionGroup>,
        val groupsByName: Map<String, PermissionGroup>,
        val nodes: List<PermissionNode>
    )

    private val mutex = Mutex()
    @Volatile
    private var cache: Cache? = null
    private val ttlMillis = 60_000L
    private val groupPrefix = "group."
    val defaultGroupName = "default"

    // Loads a fresh snapshot from the database. Caller is expected to hold [mutex].
    private suspend fun loadFreshCacheLocked(): Cache {
        val sqlClient = databaseManager.getSqlClient()

        val groups = databaseManager.permissionGroupDao.getPermissionGroups(sqlClient)
        val nodes = databaseManager.permissionNodeDao.getPermissionNodes(sqlClient)

        val refreshedNow = System.currentTimeMillis()
        val expiredNodeIds = nodes.filter { it.expiresAt != null && it.expiresAt < refreshedNow }.map { it.id }
        if (expiredNodeIds.isNotEmpty()) {
            databaseManager.permissionNodeDao.deleteByIds(expiredNodeIds, sqlClient)
        }

        val activeNodes = nodes.filter { it.expiresAt == null || it.expiresAt >= refreshedNow }

        return Cache(
            fetchedAt = refreshedNow,
            groupsById = groups.associateBy { it.id },
            groupsByName = groups.associateBy { it.name },
            nodes = activeNodes
        )
    }

    // Return cached snapshot; refresh on TTL expiry (lazy, mutex-guarded).
    private suspend fun getCache(): Cache {
        val current = cache
        val now = System.currentTimeMillis()
        if (current != null && now - current.fetchedAt < ttlMillis) {
            return current
        }

        return mutex.withLock {
            val latest = cache
            val refreshedNow = System.currentTimeMillis()
            if (latest != null && refreshedNow - latest.fetchedAt < ttlMillis) {
                return@withLock latest
            }

            loadFreshCacheLocked().also { cache = it }
        }
    }

    /**
     * Atomically reloads the cache from the database.
     *
     * IMPORTANT: Setting `cache = null` was intentionally dropped. The previous behavior
     * left the cache null after flows like [SavePermissionsSnapshotEvent] that truncate
     * and reinsert the permission tables. Concurrent requests whose TTL had expired
     * could acquire the mutex and read a half-applied (or even empty) snapshot from
     * the database, which would then be cached for the full TTL window. The visible
     * result was permission checks falsely failing for the duration. The new behavior
     * fully loads the fresh snapshot under the lock and swaps it in place; concurrent
     * readers keep using the previous cache until the swap is complete.
     */
    suspend fun refresh() {
        mutex.withLock {
            cache = loadFreshCacheLocked()
        }
    }

    /**
     * Returns userIds that exist in the current in-memory permission cache.
     *
     * Rules:
     * - Only considers cached [PermissionNode] entries where holderType == USER
     * - Includes both active and inactive nodes
     * - Excludes expired nodes (based on current time)
     *
     * Note:
     * - Users that have **no permission nodes at all** will NOT be included (because user list is not cached here).
     */
    suspend fun getCachedUserIds(): Set<Long> {
        val cache = getCache()
        val now = System.currentTimeMillis()
        return cache.nodes
            .asSequence()
            .filter { it.expiresAt == null || it.expiresAt > now }
            .filter { it.holderType == HolderType.USER }
            .map { it.holderId }
            .toSet()
    }

    // Node validity: must be active and not expired.
    private fun PermissionNode.isActive(now: Long): Boolean =
        active && (expiresAt == null || expiresAt > now)

    // Extract group name from node like "group.admin".
    private fun String.extractGroupName(): String? {
        if (!this.startsWith(groupPrefix)) return null
        return this.removePrefix(groupPrefix)
    }

    // Permission match supporting LuckPerms-style wildcards:
    // - "*" matches a single segment
    // - "**" matches remaining segments
    // - trailing patterns like "pano.*" are covered by the general matcher
    private fun matchesNode(target: String, candidate: String): Boolean {
        if (candidate == "*") return true
        if (candidate == target) return true

        val candParts = candidate.split(".")
        val tgtParts = target.split(".")

        var i = 0
        var j = 0
        while (i < candParts.size && j < tgtParts.size) {
            when (candParts[i]) {
                "**" -> {
                    // consume rest
                    return true
                }
                "*" -> {
                    // match any single segment
                    i++; j++; continue
                }
                else -> {
                    if (candParts[i] != tgtParts[j]) return false
                    i++; j++; continue
                }
            }
        }

        // Handle remaining candidate parts
        while (i < candParts.size && candParts[i] == "**") {
            // trailing ** can match empty
            i++
        }

        // Match if both consumed
        return i == candParts.size && j == tgtParts.size
    }

    private data class Specificity(val solidSegments: Int, val totalSegments: Int, val length: Int)

    // Specificity: more solid segments > more segments > longer text.
    private fun nodeSpecificity(candidate: String): Specificity {
        val parts = candidate.split(".")
        val solid = parts.count { it != "*" && it != "**" }
        return Specificity(solid, parts.size, candidate.length)
    }

    private fun getGroupWeight(group: PermissionGroup, activeNodes: List<PermissionNode>): Int {
        return activeNodes
            .filter { it.holderType == HolderType.GROUP && it.holderId == group.id && it.node.startsWith("weight.") }
            .mapNotNull { it.node.removePrefix("weight.").toIntOrNull() }
            .maxOrNull() ?: 0
    }

    private fun PermissionNode.metaValue(metaKey: String): String? {
        val prefix = "$metaKey."
        if (!node.startsWith(prefix)) return null
        return node.removePrefix(prefix)
    }

    private data class ParsedMeta(
        val node: PermissionNode,
        val priority: Int,
        val value: String
    )

    // LuckPerms-style meta format support:
    // - "prefix.<value>" => priority 0, value "<value>"
    // - "prefix.<priority>.<value>" => priority parsed, value "<value>"
    private fun PermissionNode.parseMeta(metaKey: String): ParsedMeta? {
        val raw = metaValue(metaKey) ?: return null
        val firstDot = raw.indexOf('.')
        if (firstDot > 0) {
            val maybePriority = raw.substring(0, firstDot).toIntOrNull()
            if (maybePriority != null) {
                val value = raw.substring(firstDot + 1)
                return ParsedMeta(this, maybePriority, value)
            }
        }
        return ParsedMeta(this, 0, raw)
    }

    // Context filter for panel-specific meta nodes.
    // Supports either boolean (true) or string ("true") representation.
    private fun PermissionNode.hasPanoContextEnabled(): Boolean {
        try {
            context.getBoolean("pano")?.let { return it }
        } catch (_: Exception) {
            // ignore type mismatch
        }
        return try {
            context.getString("pano")?.equals("true", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    // For permission evaluation: if pano is explicitly disabled (false/"false"), ignore this node.
    // If pano key is missing, allow the node.
    private fun PermissionNode.isPanoContextAllowed(): Boolean {
        try {
            context.getBoolean("pano")?.let { return it }
        } catch (_: Exception) {
            // ignore type mismatch
        }
        try {
            context.getString("pano")?.let { return it.equals("true", ignoreCase = true) }
        } catch (_: Exception) {
            // ignore type mismatch
        }
        return true
    }

    private fun selectMeta(
        metaKey: String,
        userNodes: List<PermissionNode>,
        groupNodes: List<PermissionNode>,
        cache: Cache,
        weightNodes: List<PermissionNode>
    ): String? {
        // IMPORTANT: user-held meta MUST override group meta (even when user node is inactive).
        // If the best user meta node is inactive, it explicitly clears the meta, so return null.

        val userBest = userNodes
            .mapNotNull { it.parseMeta(metaKey) }
            .maxWithOrNull(
                compareByDescending<ParsedMeta> { it.priority }
                    .thenByDescending { it.node.updatedAt }
            )

        if (userBest != null) {
            return if (userBest.node.active) userBest.value else null
        }

        val groupBest = groupNodes
            .mapNotNull { it.parseMeta(metaKey) }
            .maxWithOrNull(
                compareByDescending<ParsedMeta> { it.priority }
                    .thenByDescending {
                        val group = cache.groupsById[it.node.holderId]
                        if (group != null) getGroupWeight(group, weightNodes) else 0
                    }
                    .thenByDescending { it.node.updatedAt }
            )

        return if (groupBest?.node?.active == true) groupBest.value else null
    }

    // Resolve effective group IDs: direct user nodes, default group, then inherited group->group nodes.
    private fun resolveUserGroups(
        userId: Long,
        cache: Cache,
        activeNodes: List<PermissionNode>
    ): Set<Long> {
        val defaultGroupId = cache.groupsByName[defaultGroupName]?.id
        val groupIds = mutableSetOf<Long>().apply {
            defaultGroupId?.let { add(it) }
        }

        // direct user->group nodes
        activeNodes
            .filter { it.holderType == HolderType.USER && it.holderId == userId }
            .mapNotNull { it.node.extractGroupName() }
            .forEach { name ->
                cache.groupsByName[name]?.let { groupIds.add(it.id) }
            }

        // breadth-like expansion via group->group nodes
        var added = true
        while (added) {
            added = false
            val currentIds = groupIds.toSet()
            activeNodes
                .filter { it.holderType == HolderType.GROUP && it.holderId in currentIds }
                .mapNotNull { it.node.extractGroupName() }
                .forEach { name ->
                    cache.groupsByName[name]?.let { grp ->
                        if (groupIds.add(grp.id)) added = true
                    }
                }
        }

        // If user ends up with no groups, fall back to default (LuckPerms style implicit default).
        if (groupIds.isEmpty()) {
            defaultGroupId?.let { groupIds.add(it) }
        }

        return groupIds
    }

    // Decide final value for a node: user nodes override; else highest-weight group, then latest update wins.
    private fun selectDecision(
        targetNode: String,
        userNodes: List<PermissionNode>,
        groupNodes: List<PermissionNode>,
        cache: Cache,
        activeNodes: List<PermissionNode>
    ): Boolean {
        // user explicit nodes override by latest updated
        userNodes
            .filter { matchesNode(targetNode, it.node) }
            .maxWithOrNull(
                compareByDescending<PermissionNode> { nodeSpecificity(it.node).solidSegments }
                    .thenByDescending { nodeSpecificity(it.node).totalSegments }
                    .thenByDescending { nodeSpecificity(it.node).length }
                    .thenByDescending { it.updatedAt }
            )
            ?.let { return it.active }

        // otherwise pick highest weight group, then latest updated
        return groupNodes
            .filter { matchesNode(targetNode, it.node) }
            .sortedWith(
                compareByDescending<PermissionNode> {
                    val group = cache.groupsById[it.holderId]
                    if (group != null) getGroupWeight(group, activeNodes) else 0
                }
                    .thenByDescending { nodeSpecificity(it.node).solidSegments }
                    .thenByDescending { nodeSpecificity(it.node).totalSegments }
                    .thenByDescending { nodeSpecificity(it.node).length }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
            ?.active
            ?: false
    }

    // Active nodes only (respect active flag + expiry).
    private fun activeNodes(cache: Cache): List<PermissionNode> {
        val now = System.currentTimeMillis()
        return cache.nodes.filter { it.isActive(now) }
    }

    // Valid nodes for evaluation (respect expiry only; keep active=false nodes so they can override).
    private fun validNodes(cache: Cache): List<PermissionNode> {
        val now = System.currentTimeMillis()
        return cache.nodes.filter { it.expiresAt == null || it.expiresAt > now }
    }

    // Check a permission node for a user using cached graph.
    suspend fun hasPermission(
        userId: Long,
        permission: Permission
    ): Boolean {
        val source = permissionRegistry.getSource(permission)
        permission.source = source
        return hasPermissionNode(userId, permission.toString())
    }

    suspend fun hasPermissionNode(
        userId: Long,
        node: String
    ): Boolean {
        val cache = getCache()
        val activeNodes = activeNodes(cache) // active=true only (used for group resolution + weights)
        val nodes = validNodes(cache).filter { it.isPanoContextAllowed() } // active=true/false + pano context
        val userGroups = resolveUserGroups(userId, cache, activeNodes)

        val userNodes = nodes.filter { it.holderType == HolderType.USER && it.holderId == userId }
        val groupNodes = nodes.filter { it.holderType == HolderType.GROUP && it.holderId in userGroups }

        return selectDecision(node, userNodes, groupNodes, cache, activeNodes)
    }

    // Check if user is member of a group name (resolved via nodes + inheritance).
    suspend fun isInGroup(
        userId: Long,
        groupName: String
    ): Boolean {
        val cache = getCache()
        val activeNodes = activeNodes(cache)
        val groups = resolveUserGroups(userId, cache, activeNodes)
        val targetId = cache.groupsByName[groupName]?.id ?: return false
        return groups.contains(targetId)
    }

    // List all nodes granted to user after resolving overrides/inheritance/weights.
    suspend fun getGrantedNodes(
        userId: Long,
    ): Set<String> {
        val cache = getCache()
        val activeNodes = activeNodes(cache) // active=true only (used for group resolution + weights)
        val nodes = validNodes(cache).filter { it.isPanoContextAllowed() } // active=true/false + pano context
        val userGroups = resolveUserGroups(userId, cache, activeNodes)

        val userNodes = nodes.filter { it.holderType == HolderType.USER && it.holderId == userId }
        val groupNodes = nodes.filter { it.holderType == HolderType.GROUP && it.holderId in userGroups }

        val allCandidateNodes = (userNodes + groupNodes).map { it.node }.toSet()

        return allCandidateNodes.filter { candidate ->
            selectDecision(candidate, userNodes, groupNodes, cache, activeNodes)
        }.toSet()
    }

    // Resolve prefix meta (user overrides group; groups by weight then updatedAt).
    suspend fun getPrefix(userId: Long): String? {
        val cache = getCache()
        val weightNodes = activeNodes(cache)
        val groups = resolveUserGroups(userId, cache, weightNodes)
        val nodes = validNodes(cache)

        val userNodes = nodes.filter { it.holderType == HolderType.USER && it.holderId == userId }
        val groupNodes = nodes.filter { it.holderType == HolderType.GROUP && it.holderId in groups }

        val panoUserNodes = userNodes.filter { it.hasPanoContextEnabled() }
        val panoGroupNodes = groupNodes.filter { it.hasPanoContextEnabled() }
        return selectMeta("prefix", panoUserNodes, panoGroupNodes, cache, weightNodes) ?: ""
    }

    // Resolve suffix meta (user overrides group; groups by weight then updatedAt).
    suspend fun getSuffix(userId: Long): String? {
        val cache = getCache()
        val weightNodes = activeNodes(cache)
        val groups = resolveUserGroups(userId, cache, weightNodes)
        val nodes = validNodes(cache)

        val userNodes = nodes.filter { it.holderType == HolderType.USER && it.holderId == userId }
        val groupNodes = nodes.filter { it.holderType == HolderType.GROUP && it.holderId in groups }

        val panoUserNodes = userNodes.filter { it.hasPanoContextEnabled() }
        val panoGroupNodes = groupNodes.filter { it.hasPanoContextEnabled() }
        return selectMeta("suffix", panoUserNodes, panoGroupNodes, cache, weightNodes) ?: ""
    }

    // List effective groups (including inherited/default) for a user, ordered by weight desc then id.
    suspend fun getGroups(
        userId: Long
    ): List<PermissionGroup> {
        val cache = getCache()
        val activeNodes = activeNodes(cache)
        val groupIds = resolveUserGroups(userId, cache, activeNodes)

        return groupIds
            .mapNotNull { cache.groupsById[it] }
            .sortedWith(compareByDescending<PermissionGroup> { getGroupWeight(it, activeNodes) }.thenBy { it.id })
    }

    // Resolve primary permission group for a user (LuckPerms-style: highest weight wins).
    // Returns null if no group can be resolved (e.g., missing default group in DB).
    suspend fun getPermissionGroup(userId: Long): PermissionGroup? {
        val cache = getCache()
        val activeNodes = activeNodes(cache)
        val groupIds = resolveUserGroups(userId, cache, activeNodes)

        return groupIds
            .mapNotNull { cache.groupsById[it] }
            .sortedWith(
                compareByDescending<PermissionGroup> { getGroupWeight(it, activeNodes) }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.id }
            )
            .firstOrNull()
    }

    // Return userIds that effectively have the given permission node.
    suspend fun getUserIdsWithPermission(
        permission: Permission
    ): Set<Long> {
        return getUserIdsWithNode(permission.toString()).toSet()
    }

    suspend fun getUserIdsWithNode(targetNode: String): List<Long> {
        val cache = getCache()
        val activeNodes = activeNodes(cache) // active=true only (used for group resolution + weights)
        val nodes = validNodes(cache).filter { it.isPanoContextAllowed() } // active=true/false + pano context

        // evaluate all users so default group permissions also apply
        val sqlClient = databaseManager.getSqlClient()
        val userIds = databaseManager.userDao.getAllIds(sqlClient)

        return userIds.filter { userId ->
            val groups = resolveUserGroups(userId, cache, activeNodes)
            val userNodes = nodes.filter { it.holderType == HolderType.USER && it.holderId == userId }
            val groupNodes = nodes.filter { it.holderType == HolderType.GROUP && it.holderId in groups }

            selectDecision(targetNode, userNodes, groupNodes, cache, activeNodes)
        }
    }

    suspend fun groupExists(
        groupName: String
    ): Boolean {
        val cache = getCache()
        return cache.groupsByName.containsKey(groupName)
    }

    // Return permission group by its name from the cached snapshot (null if not found).
    suspend fun getPermissionGroupByName(
        groupName: String
    ): PermissionGroup? {
        val cache = getCache()
        return cache.groupsByName[groupName]
    }

    // Returns userIds that are members of the given group name (resolved from nodes).
    suspend fun getUserIdsInGroup(
        groupName: String
    ): Set<Long> {
        val cache = getCache()
        val targetId = cache.groupsByName[groupName]?.id ?: return emptySet()
        val activeNodes = activeNodes(cache)

        if (groupName == defaultGroupName) {
            val sqlClient = databaseManager.getSqlClient()
            val allUserIds = databaseManager.userDao.getAllIds(sqlClient).toSet()
            return allUserIds.filter { userId ->
                resolveUserGroups(userId, cache, activeNodes).contains(targetId)
            }.toSet()
        }

        // collect user ids that resolve to target group
        val userIds = mutableSetOf<Long>()
        // collect users that have any node (user holder)
        val userHolderIds = activeNodes
            .filter { it.holderType == HolderType.USER }
            .map { it.holderId }
            .toSet()

        userHolderIds.forEach { userId ->
            val groups = resolveUserGroups(userId, cache, activeNodes)
            if (groups.contains(targetId)) {
                userIds.add(userId)
            }
        }

        return userIds
    }
}

