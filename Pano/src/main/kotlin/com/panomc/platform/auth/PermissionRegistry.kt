package com.panomc.platform.auth

import com.panomc.platform.annotation.PermissionDefinition
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PermissionRegistry() : PluginLifecycleListener {

    /**
     * The permissions of each source, keyed by node (`pano.manage.tickets`): a plugin that is loaded again (an update,
     * a re-enable) replaces its own entries instead of adding a second copy of each, which the panel's node list
     * cannot show twice.
     */
    private val permissions = mutableMapOf<String, LinkedHashMap<String, Permission>>()

    companion object {
        // Key used for platform-level permissions
        private const val PLATFORM_KEY = "platform"
    }

    fun initialize(applicationContext: ApplicationContext) {
        // Collect platform permissions from all beans of type Permission that have the annotation
        val allPermissions = applicationContext.getBeansOfType(Permission::class.java)
        allPermissions.values.forEach { bean ->
            if (bean::class.java.isAnnotationPresent(PermissionDefinition::class.java)) {
                register(PLATFORM_KEY, bean)
            }
        }
    }

    fun register(source: String, permission: Permission) {
        permission.source = source
        permissions.getOrPut(source) { LinkedHashMap() }[permission.toString()] = permission
    }

    fun unregisterAll(source: String) {
        permissions.remove(source)
    }

    fun getSource(permission: Permission): String? {
        val packageName = permission.javaClass.`package`.name
        return permissions.entries.find { (_, perms) ->
            perms.values.any { it.javaClass.`package`.name == packageName }
        }?.key
    }

    fun getAllPermissions(): Map<String, List<Permission>> {
        return permissions.mapValues { it.value.values.toList() }
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
        // a plugin loaded again starts from a clean list: nodes it no longer declares go away
        unregisterAll(plugin.pluginId)

        val allPermissions = plugin.pluginBeanContext.getBeansOfType(Permission::class.java)
        allPermissions.values.forEach { bean ->
            if (bean::class.java.isAnnotationPresent(PermissionDefinition::class.java)) {
                register(plugin.pluginId, bean)
            }
        }
    }

    override suspend fun onPluginUnload(plugin: PanoPlugin) {
        unregisterAll(plugin.pluginId)
    }
}
