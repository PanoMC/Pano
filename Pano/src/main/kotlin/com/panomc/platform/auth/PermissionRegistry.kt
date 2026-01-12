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

    private val permissions = mutableMapOf<String, MutableSet<Permission>>()

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
        permissions.getOrPut(source) { mutableSetOf() }.add(permission)
    }

    fun unregisterAll(source: String) {
        permissions.remove(source)
    }

    fun getAllPermissions(): Map<String, List<Permission>> {
        return permissions.mapValues { it.value.toList() }
    }

    override suspend fun onPluginLoad(plugin: PanoPlugin) {
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
