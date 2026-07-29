package com.panomc.platform.route.api.panel.settings

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LuckPermsNodeEditorTest {
    private fun permission(
        groupName: String? = null,
        uuid: String? = null,
        permission: String,
        value: Boolean = true,
        server: String = "global",
        world: String = "global",
        expiry: Long = 0L,
        contexts: String = "{}"
    ) = PanelLuckPermsMigrationUploadAPI.LPPermission(
        groupName, uuid, permission, value, server, world, expiry, contexts
    )

    private fun match(
        permission: String,
        server: String = "global",
        world: String = "global",
        expiry: Long = 0L,
        contexts: String = "{}"
    ) = JsonObject()
        .put("permission", permission)
        .put("server", server)
        .put("world", world)
        .put("expiry", expiry)
        .put("contexts", contexts)

    private fun dataWith(
        groupPermissions: List<PanelLuckPermsMigrationUploadAPI.LPPermission> = emptyList(),
        userPermissions: List<PanelLuckPermsMigrationUploadAPI.LPPermission> = emptyList()
    ) = PanelLuckPermsMigrationUploadAPI.LuckPermsData(
        groupPermissions = groupPermissions.toMutableList(),
        userPermissions = userPermissions.toMutableList()
    )

    @Test
    fun `no edits leaves the data untouched`() {
        val data = dataWith(groupPermissions = listOf(permission(groupName = "admin", permission = "pano.admin")))

        LuckPermsNodeEditor.apply(data, emptyList())

        assertEquals(1, data.groupPermissions.size)
        assertEquals("pano.admin", data.groupPermissions.first().permission)
    }

    @Test
    fun `remove drops only the matching group node`() {
        val data = dataWith(
            groupPermissions = listOf(
                permission(groupName = "admin", permission = "pano.admin"),
                permission(groupName = "admin", permission = "pano.keep")
            )
        )

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "admin")
                    .put("action", "remove")
                    .put("match", match("pano.admin"))
            )
        )

        assertEquals(listOf("pano.keep"), data.groupPermissions.map { it.permission })
    }

    @Test
    fun `remove only affects the named holder`() {
        val data = dataWith(
            groupPermissions = listOf(
                permission(groupName = "admin", permission = "shared.node"),
                permission(groupName = "mod", permission = "shared.node")
            )
        )

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "admin")
                    .put("action", "remove")
                    .put("match", match("shared.node"))
            )
        )

        assertEquals(listOf("mod"), data.groupPermissions.map { it.groupName })
    }

    @Test
    fun `remove does not match a node whose context differs`() {
        val data = dataWith(
            groupPermissions = listOf(permission(groupName = "admin", permission = "pano.admin", server = "lobby"))
        )

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "admin")
                    .put("action", "remove")
                    .put("match", match("pano.admin", server = "global"))
            )
        )

        assertEquals(1, data.groupPermissions.size, "a different server context must not be removed")
    }

    @Test
    fun `update rewrites the matched node and keeps untouched fields`() {
        val data = dataWith(
            groupPermissions = listOf(
                permission(groupName = "admin", permission = "pano.admin", value = true, world = "nether")
            )
        )

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "admin")
                    .put("action", "update")
                    .put("match", match("pano.admin", world = "nether"))
                    .put("node", JsonObject().put("permission", "pano.superadmin").put("value", false))
            )
        )

        val updated = data.groupPermissions.single()
        assertEquals("pano.superadmin", updated.permission)
        assertEquals(false, updated.value)
        assertEquals("nether", updated.world, "fields absent from the edit must be preserved")
        assertEquals("admin", updated.groupName)
    }

    @Test
    fun `add appends a node bound to its holder`() {
        val data = dataWith()

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "vip")
                    .put("action", "add")
                    .put("node", JsonObject().put("permission", "pano.vip").put("server", "survival"))
            )
        )

        val added = data.groupPermissions.single()
        assertEquals("pano.vip", added.permission)
        assertEquals("vip", added.groupName)
        assertNull(added.uuid)
        assertEquals("survival", added.server)
        assertEquals("global", added.world, "unspecified context falls back to global")
        assertTrue(added.value)
    }

    @Test
    fun `user edits are bound by uuid rather than group name`() {
        val uuid = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"
        val data = dataWith()

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "USER")
                    .put("holderKey", uuid)
                    .put("action", "add")
                    .put("node", JsonObject().put("permission", "group.admin"))
            )
        )

        val added = data.userPermissions.single()
        assertEquals(uuid, added.uuid)
        assertNull(added.groupName)
        assertTrue(data.groupPermissions.isEmpty(), "a USER edit must not leak into group permissions")
    }

    @Test
    fun `an add with a blank permission is ignored`() {
        val data = dataWith()

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject()
                    .put("holderType", "GROUP")
                    .put("holderKey", "admin")
                    .put("action", "add")
                    .put("node", JsonObject().put("permission", "   "))
            )
        )

        assertTrue(data.groupPermissions.isEmpty())
    }

    @Test
    fun `malformed edits are skipped without touching the data`() {
        val data = dataWith(groupPermissions = listOf(permission(groupName = "admin", permission = "pano.admin")))

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject().put("holderType", "GROUP").put("action", "remove"), // no holderKey
                JsonObject().put("holderType", "GROUP").put("holderKey", "admin"), // no action
                JsonObject().put("holderType", "GROUP").put("holderKey", "admin").put("action", "update"), // no match
                JsonObject().put("holderType", "NONSENSE").put("holderKey", "admin").put("action", "remove")
                    .put("match", match("pano.admin"))
            )
        )

        assertEquals(listOf("pano.admin"), data.groupPermissions.map { it.permission })
    }

    @Test
    fun `remove, update and add compose in a single pass`() {
        val data = dataWith(
            groupPermissions = listOf(
                permission(groupName = "admin", permission = "drop.me"),
                permission(groupName = "admin", permission = "change.me"),
                permission(groupName = "admin", permission = "keep.me")
            )
        )

        LuckPermsNodeEditor.apply(
            data,
            listOf(
                JsonObject().put("holderType", "GROUP").put("holderKey", "admin").put("action", "remove")
                    .put("match", match("drop.me")),
                JsonObject().put("holderType", "GROUP").put("holderKey", "admin").put("action", "update")
                    .put("match", match("change.me"))
                    .put("node", JsonObject().put("permission", "changed")),
                JsonObject().put("holderType", "GROUP").put("holderKey", "admin").put("action", "add")
                    .put("node", JsonObject().put("permission", "brand.new"))
            )
        )

        assertEquals(listOf("changed", "keep.me", "brand.new"), data.groupPermissions.map { it.permission })
    }
}
