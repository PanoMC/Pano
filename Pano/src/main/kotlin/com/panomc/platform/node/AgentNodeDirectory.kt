package com.panomc.platform.node

import com.panomc.platform.db.model.Node
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Which nodes are Pano Agents, and what each last said about itself, without a database round trip.
 *
 * The server JSON is built in a dozen places by a function that is not suspending
 * ([com.panomc.platform.server.feature.ServerFeatureResolver.toPublicJsonObject]), and it has to say
 * `agent: true` with the agent's version for a server whose agent is offline as much as for one
 * whose agent is connected. So the agent rows are kept here: every one of them at boot
 * ([NodeManager.init]), a new one when it pairs, a fresh copy on every hello, and none once the
 * node is deleted. Ordinary nodes are never in it.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class AgentNodeDirectory {
    private val agents = ConcurrentHashMap<Long, Node>()

    /** Who fetched the code each freshly paired agent used, until its first hello takes it. */
    private val linkers = ConcurrentHashMap<Long, Long>()

    /** Remembers that [userId] set up the agent [nodeId]. */
    fun noteLinker(nodeId: Long, userId: Long) {
        linkers[nodeId] = userId
    }

    /** Who set up [nodeId], once; null when nobody is known (e.g. Pano restarted in between). */
    fun takeLinker(nodeId: Long): Long? = linkers.remove(nodeId)

    /** Replaces everything with the agent rows among [nodes]. */
    fun seed(nodes: Collection<Node>) {
        agents.clear()

        nodes.filter { it.agent }.forEach { agents[it.id] = it }
    }

    /** Records [node] if it is an agent, drops it otherwise. */
    fun put(node: Node) {
        if (node.agent) agents[node.id] = node else agents.remove(node.id)
    }

    fun remove(nodeId: Long) {
        agents.remove(nodeId)
        linkers.remove(nodeId)
    }

    /** The agent node [nodeId], or null when it is an ordinary node, or none at all. */
    fun get(nodeId: Long?): Node? = nodeId?.let { agents[it] }

    fun isAgent(nodeId: Long?): Boolean = get(nodeId) != null
}
