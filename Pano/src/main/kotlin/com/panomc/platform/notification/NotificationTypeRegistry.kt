package com.panomc.platform.notification

import com.panomc.platform.PluginManager
import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service

/**
 * Central registry of notification types: host types ([NotificationDefinition] beans, collected lazily on first use)
 * and plugin types (collected from each plugin's bean context on load, removed on unload).
 * A plugin type named like a core type or like another plugin's type is rejected.
 */
@Service
class NotificationTypeRegistry internal constructor(
    private val coreTypesProvider: () -> Collection<NotificationType>
) : PluginLifecycleListener {
    private val logger = LoggerFactory.getLogger("NotificationTypeRegistry")
    private val lock = Any()

    private val pluginTypes = mutableMapOf<String, NotificationType>()
    private val pluginOwners = mutableMapOf<String, String>()

    private val coreTypes: Map<String, NotificationType> by lazy {
        val map = linkedMapOf<String, NotificationType>()

        coreTypesProvider().forEach { map.putIfAbsent(it.getName(), it) }

        map
    }

    @Autowired
    constructor(pluginManager: PluginManager, applicationContext: ApplicationContext) : this({
        applicationContext.getBeansWithAnnotation(NotificationDefinition::class.java).values
            .filterIsInstance<NotificationType>()
    }) {
        pluginManager.addLifecycleListener(this)
    }

    /** Never null: [UnknownNotificationType] when [name] is not registered. */
    fun resolve(name: String): NotificationType = synchronized(lock) {
        coreTypes[name] ?: pluginTypes[name]
    } ?: UnknownNotificationType(name)

    /** Plugin id owning [name]; null for core and unknown types. */
    fun ownerOf(name: String): String? = synchronized(lock) { pluginOwners[name] }

    /** Registers [type] for [pluginId]; returns false (and logs) when rejected. */
    internal fun registerPluginType(pluginId: String, type: NotificationType): Boolean {
        val name = type.getName()

        synchronized(lock) {
            if (coreTypes.containsKey(name)) {
                logger.error("NotificationType '$name' of plugin '$pluginId' is reserved for core; not registered")
                return false
            }

            val owner = pluginOwners[name]

            if (owner != null) {
                if (owner != pluginId) {
                    logger.error("NotificationType '$name' of plugin '$pluginId' is already registered by plugin '$owner'; not registered")
                }

                return owner == pluginId
            }

            pluginTypes[name] = type
            pluginOwners[name] = pluginId
        }

        return true
    }

    internal fun unregisterPlugin(pluginId: String) = synchronized(lock) {
        val names = pluginOwners.filterValues { it == pluginId }.keys.toList()

        names.forEach {
            pluginOwners.remove(it)
            pluginTypes.remove(it)
        }
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        // a reload re-registers the same names; drop leftovers first
        unregisterPlugin(plugin.pluginId)

        plugin.pluginBeanContext.getBeansWithAnnotation(NotificationDefinition::class.java).values
            .filterIsInstance<NotificationType>()
            .forEach { registerPluginType(plugin.pluginId, it) }
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        unregisterPlugin(plugin.pluginId)
    }
}
