package com.panomc.platform.server.feature

import com.panomc.platform.db.model.Server
import com.panomc.platform.server.ServerCapability
import com.panomc.platform.server.ServerKind
import com.panomc.platform.server.ServerProcessState
import com.panomc.platform.server.ServerProtocol
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.plugins.PluginLoaderMapping

/**
 * Everything [ServerFeatureResolver] is allowed to look at.
 *
 * A closed set of facts rather than a `Server` plus two managers, because the resolution is a
 * table and a table is worth proving: given these values the answer is fixed, and a test can walk
 * the whole space without a database, a socket or a Spring context anywhere near it.
 *
 * [capabilities] is already narrowed to the ids this Pano knows — [ServerCapability.fromId] drops
 * the rest — so a plugin newer than this platform contributes the parts that are understood and
 * nothing else, which is the whole point of announcing capabilities by id.
 */
data class ServerFeatureInputs(
    val kind: ServerKind,
    /** Whether a node owns this server's process *and* is connected right now. */
    val nodeConnected: Boolean,
    /** The node's word about whether it can still write to the process's stdin. */
    val stdinAvailable: Boolean,
    /** Process lifecycle as the node last reported it; `null` when no node owns the process. */
    val processState: ServerProcessState?,
    /** Whether the Pano plugin inside the game currently holds a socket. */
    val pluginConnected: Boolean,
    val capabilities: Set<ServerCapability>,
    /** Only the Bukkit family can enable or disable a plugin without a restart. */
    val bukkitFamily: Boolean,
    /** Vanilla has no plugin directory at all, so no source can list, toggle or install into one. */
    val supportsPlugins: Boolean = true,
    /**
     * The protocol the connected plugin announced. Only read to tell an outdated plugin (one that
     * predates the capability handshake and so announces nothing) from a current one that simply
     * does not offer a capability — the two need different sentences (SM-70, §2.4.35).
     */
    val pluginProtocol: Int = ServerProtocol.CURRENT_PROTOCOL_VERSION,
    /** Proxies run no world, so nothing on one counts a tick. */
    val proxy: Boolean = false,
    /** Whether a Pano plugin exists for this software at all; Vanilla, Forge and NeoForge have none. */
    val pluginModule: Boolean = true
) {
    /** Whether a node can be asked to do anything at all for this server. */
    val nodeAvailable get() = kind == ServerKind.MANAGED && nodeConnected

    /** Whether there is a process to stop, kill or measure. */
    val processAlive get() = processState?.isAlive == true

    /**
     * Whether a restore may overwrite the files in place.
     *
     * A crashed server is stopped too, and a row no node has reported on yet is the same thing.
     */
    val processStopped
        get() = processState == null ||
            processState == ServerProcessState.STOPPED ||
            processState == ServerProcessState.CRASHED

    fun has(capability: ServerCapability) = pluginConnected && capabilities.contains(capability)

    /** Whether the plugin holding the socket is too old to have announced any capability. */
    val pluginLegacy get() = pluginConnected && pluginProtocol <= ServerProtocol.LEGACY_PROTOCOL_VERSION

    /**
     * Whether there is something alive to ask: the process for a managed server, the plugin for a
     * linked one, which has no process Pano could see.
     */
    val serverUp get() = if (kind == ServerKind.MANAGED) processAlive else pluginConnected

    companion object {
        /**
         * Reads the inputs off a server row plus the two liveness facts only the managers know.
         *
         * The row carries the capability ids verbatim, so unknown ones are dropped here rather
         * than rejected: an older Pano meeting a newer plugin must degrade, never fail.
         */
        fun of(server: Server, nodeConnected: Boolean, pluginConnected: Boolean) = ServerFeatureInputs(
            kind = server.kind,
            nodeConnected = nodeConnected,
            stdinAvailable = server.stdinAvailable,
            processState = server.processState,
            pluginConnected = pluginConnected,
            capabilities = server.capabilities.mapNotNull { ServerCapability.fromId(it) }.toSet(),
            bukkitFamily = server.type.isBukkitFamily,
            supportsPlugins = PluginLoaderMapping.supportsPlugins(server.type),
            pluginProtocol = server.protocolVersion,
            proxy = server.type.isProxy,
            pluginModule = !server.type.hasNoPluginModule
        )

        /** [of] for a server whose type is not on the row yet, used by the resolver's own tests. */
        fun of(
            kind: ServerKind,
            nodeConnected: Boolean = false,
            stdinAvailable: Boolean = true,
            processState: ServerProcessState? = null,
            pluginConnected: Boolean = false,
            capabilities: Set<ServerCapability> = emptySet(),
            type: ServerType = ServerType.PAPER,
            pluginProtocol: Int = ServerProtocol.CURRENT_PROTOCOL_VERSION
        ) = ServerFeatureInputs(
            kind = kind,
            nodeConnected = nodeConnected,
            stdinAvailable = stdinAvailable,
            processState = processState,
            pluginConnected = pluginConnected,
            capabilities = capabilities,
            bukkitFamily = type.isBukkitFamily,
            supportsPlugins = PluginLoaderMapping.supportsPlugins(type),
            pluginProtocol = pluginProtocol,
            proxy = type.isProxy,
            pluginModule = !type.hasNoPluginModule
        )
    }
}
