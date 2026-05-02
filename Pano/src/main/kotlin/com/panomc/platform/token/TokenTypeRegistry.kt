package com.panomc.platform.token

import com.panomc.platform.PluginManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Central registry for all token types (both core and plugin-defined).
 * Core types are [CoreTokenType] Spring beans collected by the host and passed to
 * [registerCoreTypes]. Plugins should register custom types with [registerPluginToken] so entries
 * are removed when the plugin is unloaded; legacy [register] does not tie types to a plugin lifecycle.
 */
@Service
class TokenTypeRegistry(
    private val pluginManager: PluginManager,
    coreTypes: List<CoreTokenType>,
) : PluginLifecycleListener {
    private val logger = LoggerFactory.getLogger("TokenTypeRegistry")
    private val types = mutableMapOf<String, TokenType>()
    private val coreTypeNames = mutableSetOf<String>()
    private val tokenNamesByPluginId = mutableMapOf<String, MutableSet<String>>()

    init {
        registerCoreTypes(coreTypes)
        pluginManager.addLifecycleListener(this)
    }

    /**
     * Registers a token type. Logs a warning if a type with the same name already exists.
     * Prefer [registerPluginToken] from plugins so types disappear on plugin unload.
     */
    fun register(tokenType: TokenType) {
        putPluginToken(tokenType, pluginIdForOwnership = null)
    }

    /**
     * Registers a token type owned by [pluginId]. When the plugin unloads, the host removes
     * these entries (after routes are torn down) so re-enable does not hit duplicate-name warnings.
     */
    fun registerPluginToken(pluginId: String, tokenType: TokenType) {
        putPluginToken(tokenType, pluginIdForOwnership = pluginId)
    }

    private fun putPluginToken(tokenType: TokenType, pluginIdForOwnership: String?) {
        val name = tokenType.getName()
        if (coreTypeNames.contains(name)) {
            logger.warn(
                "TokenType '$name' is reserved for core; ignoring registration of ${tokenType.javaClass.name}"
            )
            return
        }
        if (types.containsKey(name)) {
            logger.warn("TokenType '$name' is already registered, overwriting with ${tokenType.javaClass.name}")
        }
        types[name] = tokenType
        if (pluginIdForOwnership != null) {
            tokenNamesByPluginId.getOrPut(pluginIdForOwnership) { mutableSetOf() }.add(name)
        }
        logger.debug("Registered token type: $name")
    }

    /**
     * Removes token types previously registered for this plugin via [registerPluginToken].
     * Core types are never removed. Invoked by the host after plugin HTTP routes are torn down.
     */
    private fun unregisterTokensOwnedByPlugin(pluginId: String) {
        val names = tokenNamesByPluginId.remove(pluginId) ?: return
        for (name in names) {
            if (coreTypeNames.contains(name)) {
                continue
            }
            types.remove(name)
            logger.debug("Unregistered token type: $name (plugin $pluginId)")
        }
    }

    /**
     * Looks up a token type by its name. Returns null if not found.
     */
    fun get(name: String): TokenType? = types[name]

    /**
     * Returns all registered token types.
     */
    fun getAll(): List<TokenType> = types.values.toList()

    /**
     * Registers all built-in core token types from Spring-injected [CoreTokenType] beans.
     */
    fun registerCoreTypes(coreTypes: Iterable<CoreTokenType>) {
        for (tokenType in coreTypes) {
            val name = tokenType.getName()
            coreTypeNames.add(name)
            types[name] = tokenType
            logger.debug("Registered core token type: $name")
        }
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        unregisterTokensOwnedByPlugin(plugin.pluginId)
    }
}
