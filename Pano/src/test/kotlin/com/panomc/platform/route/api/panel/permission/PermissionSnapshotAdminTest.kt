package com.panomc.platform.route.api.panel.permission

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PermissionSnapshotAdminTest {
    private val admins = setOf(1L)

    private fun group(id: Long, name: String) = JsonObject().put("id", id).put("name", name)

    private fun groupNode(group: String, node: String) =
        JsonObject().put("holderType", "GROUP").put("holderId", 0).put("holderName", group).put("node", node)

    private fun userNode(userId: Long, node: String) =
        JsonObject().put("holderType", "USER").put("holderId", userId).put("node", node)

    private val baseNodes = listOf(
        groupNode("admin", "*"),
        groupNode("mod", "pano.manage.posts"),
        userNode(1, "group.admin"),
        userNode(2, "group.mod")
    )

    private fun snapshot(nodes: List<JsonObject> = baseNodes, groups: List<JsonObject> = listOf(group(1, "admin"), group(2, "mod"))) =
        PermissionSnapshot.parse(JsonObject().put("groups", JsonArray(groups)).put("nodes", JsonArray(nodes)), "default", now = 1_000)

    private fun keys(s: PermissionSnapshot, other: PermissionSnapshot) =
        s.adminKeys(admins, s.adminGroups() + other.adminGroups(), "default", now = 1_000)

    private fun sameAdmins(before: PermissionSnapshot, after: PermissionSnapshot) = keys(before, after) == keys(after, before)

    @Test
    fun `editing a non-admin group is not an admin change`() {
        val after = snapshot(baseNodes + listOf(groupNode("mod", "pano.manage.tickets"), userNode(3, "group.mod")))

        assertEquals(true, sameAdmins(snapshot(), after))
    }

    @Test
    fun `granting yourself admin is an admin change`() {
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes + listOf(userNode(2, "group.admin")))))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes + listOf(userNode(2, "*")))))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes + listOf(groupNode("mod", "*")))))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes + listOf(groupNode("mod", "group.admin")))))
    }

    @Test
    fun `stripping an admin or the admin group is an admin change`() {
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes.filterNot { it == userNode(1, "group.admin") })))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes.filterNot { it == groupNode("admin", "*") })))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(groups = listOf(group(2, "mod")))))
        assertNotEquals(true, sameAdmins(snapshot(), snapshot(baseNodes + listOf(userNode(1, "pano.manage.posts")))))
    }

    @Test
    fun `inheritance of an admin group counts as admin`() {
        val nested = snapshot(baseNodes + listOf(groupNode("staff", "group.admin")), groups = listOf(group(1, "admin"), group(2, "mod"), group(3, "staff")))

        assertEquals(setOf("admin", "staff"), nested.adminGroups())
    }
}
