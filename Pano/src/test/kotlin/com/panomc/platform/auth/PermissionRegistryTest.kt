package com.panomc.platform.auth

import com.panomc.platform.auth.panel.permission.AccessPanelPermission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PermissionRegistryTest {
    @Test
    fun `a permission registered again is listed once`() {
        val registry = PermissionRegistry()

        // a plugin loaded twice brings new instances of the same permission classes
        registry.register("my-plugin", AccessPanelPermission())
        registry.register("my-plugin", AccessPanelPermission())

        val listed = registry.getAllPermissions().getValue("my-plugin")

        assertEquals(1, listed.size)
        assertEquals("pano.plugin.my-plugin.access.panel", listed.single().toString())
    }
}
