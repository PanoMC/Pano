package com.panomc.platform.route.api.panel.permission

import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.error.InvalidData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PermissionSnapshotTest {
    private fun group(id: Long, name: String) = JsonObject().put("id", id).put("name", name)

    private fun node(holderType: String, holderId: Any?, node: Any?, holderName: String? = null) =
        JsonObject().put("holderType", holderType).put("holderId", holderId).put("node", node).put("holderName", holderName)

    private fun body(groups: List<JsonObject> = listOf(group(1, "admin"), group(2, "default")), nodes: List<JsonObject> = listOf()) =
        JsonObject().put("groups", JsonArray(groups)).put("nodes", JsonArray(nodes))

    private fun parse(body: JsonObject) = PermissionSnapshot.parse(body, "default", now = 1_000)

    @Test
    fun `a valid payload is read with group holders named`() {
        val snapshot = parse(body(nodes = listOf(node("GROUP", 1, "*", holderName = "admin"), node("USER", 7, "group.admin"))))

        assertEquals(listOf("admin", "default"), snapshot.groups.map { it.name })
        assertEquals("admin", snapshot.nodes[0].groupName)
        assertEquals(HolderType.USER, snapshot.nodes[1].holderType)
        assertEquals(7L, snapshot.nodes[1].holderId)
    }

    @Test
    fun `a group holder may be named by the id the browser holds`() {
        val snapshot = parse(body(nodes = listOf(node("GROUP", 1, "*"))))

        assertEquals("admin", snapshot.nodes.single().groupName)
    }

    @Test
    fun `the default group is added when missing`() {
        assertTrue(parse(body(groups = listOf(group(1, "admin")))).groups.any { it.name == "default" })
    }

    @Test
    fun `an unknown holder type is refused`() {
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("ROBOT", 1, "x")))) }
    }

    @Test
    fun `a value of the wrong type is refused`() {
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", "seven", "x")))) }
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", 7, "x").put("active", "yes")))) }
        assertThrows<InvalidData> { parse(JsonObject().put("groups", "admin")) }
    }

    @Test
    fun `group names must be unique and fit the column`() {
        assertThrows<InvalidData> { parse(body(groups = listOf(group(1, "admin"), group(2, "admin")))) }
        assertThrows<InvalidData> { parse(body(groups = listOf(group(1, "a".repeat(33))))) }
        assertThrows<InvalidData> { parse(body(groups = listOf(group(1, "")))) }
    }

    @Test
    fun `nodes must fit the column and hold no whitespace`() {
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", 7, "a".repeat(256))))) }
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", 7, "a b")))) }
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", 7, "")))) }
    }

    @Test
    fun `too many nodes are refused`() {
        val nodes = (0..PermissionSnapshot.MAX_NODES).map { node("USER", 7, "n$it") }

        assertThrows<InvalidData> { parse(body(nodes = nodes)) }
    }

    @Test
    fun `a scope that is not an object is refused`() {
        assertThrows<InvalidData> { parse(body(nodes = listOf(node("USER", 7, "x").put("context", "server")))) }
    }

    @Test
    fun `the implicit default group and expired nodes are dropped`() {
        val snapshot = parse(
            body(nodes = listOf(node("USER", 7, "group.default"), node("USER", 7, "x").put("expiresAt", 999L)))
        )

        assertEquals(0, snapshot.nodes.size)
    }
}
