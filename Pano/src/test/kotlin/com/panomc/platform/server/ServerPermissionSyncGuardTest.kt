package com.panomc.platform.server

import com.panomc.platform.db.model.PermissionGroup
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.server.event.ServerPermissionSyncGuard
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerPermissionSyncGuardTest {
    private val none = JsonObject()

    @Test
    fun `wildcards and pano nodes are panel nodes`() {
        listOf("*", "**", "pano.panel.manage.servers", "pano.*", "*.panel.x", "PANO.panel.x").forEach {
            assertTrue(ServerPermissionSyncGuard.isPanelNode(it, none), it)
        }
        listOf("essentials.fly", "group.vip", "prefix.10.&a", "panorama.view").forEach {
            assertFalse(ServerPermissionSyncGuard.isPanelNode(it, none), it)
        }
    }

    @Test
    fun `pano false context hides a node from the panel`() {
        assertFalse(ServerPermissionSyncGuard.isPanelNode("*", JsonObject().put("pano", false)))
        assertFalse(ServerPermissionSyncGuard.isPanelNode("*", JsonObject().put("pano", "false")))
        assertTrue(ServerPermissionSyncGuard.isPanelNode("*", JsonObject().put("pano", true)))
        assertTrue(ServerPermissionSyncGuard.isPanelNode("*", JsonObject().put("server", 3)))
    }

    @Test
    fun `panel groups include groups that inherit them`() {
        val groups = listOf(
            PermissionGroup(id = 1, name = "default", displayName = "default"),
            PermissionGroup(id = 2, name = "admin", displayName = "admin"),
            PermissionGroup(id = 3, name = "owner", displayName = "owner"),
            PermissionGroup(id = 4, name = "vip", displayName = "vip")
        )
        val nodes = listOf(
            PermissionNode(holderType = HolderType.GROUP, holderId = 2, node = "*", active = true),
            PermissionNode(holderType = HolderType.GROUP, holderId = 3, node = "group.admin", active = true),
            PermissionNode(holderType = HolderType.GROUP, holderId = 4, node = "essentials.fly", active = true),
            PermissionNode(holderType = HolderType.GROUP, holderId = 4, node = "group.default", active = true)
        )
        assertEquals(setOf("admin", "owner"), ServerPermissionSyncGuard.panelGroups(groups, nodes))
    }

    @Test
    fun `a server cannot grant panel access`() {
        val panelGroups = setOf("admin")
        // '*' or a panel node on any holder
        assertTrue(ServerPermissionSyncGuard.isProtected(HolderType.USER, null, "*", none, panelGroups))
        assertTrue(ServerPermissionSyncGuard.isProtected(HolderType.GROUP, "default", "pano.panel.x", none, panelGroups))
        // joining or inheriting a panel group
        assertTrue(ServerPermissionSyncGuard.isProtected(HolderType.USER, null, "group.admin", none, panelGroups))
        assertTrue(ServerPermissionSyncGuard.isProtected(HolderType.GROUP, "default", "group.admin", none, panelGroups))
        // anything held by a panel group
        assertTrue(ServerPermissionSyncGuard.isProtected(HolderType.GROUP, "admin", "weight.1000", none, panelGroups))
        // ordinary game permissions still sync
        assertFalse(ServerPermissionSyncGuard.isProtected(HolderType.USER, null, "group.vip", none, panelGroups))
        assertFalse(ServerPermissionSyncGuard.isProtected(HolderType.GROUP, "vip", "essentials.fly", none, panelGroups))
        assertFalse(
            ServerPermissionSyncGuard.isProtected(
                HolderType.GROUP, "vip", "*", JsonObject().put("pano", false), panelGroups
            )
        )
    }
}
