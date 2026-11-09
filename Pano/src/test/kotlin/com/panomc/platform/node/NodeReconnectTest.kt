package com.panomc.platform.node

import com.panomc.platform.db.model.Node
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

class NodeReconnectTest {
    private fun node(version: String) =
        Node(id = 7, uuid = "u", name = "n", kind = NodeKind.values().first(), aesKey = "k", version = version)

    @Test
    fun `a reconnect replaces the key object, not only the value`() {
        val map = ConcurrentHashMap<Node, String>()
        val first = node("1.0")
        map[first] = "old-socket"

        val second = node("2.0")
        val superseded = supersedeNodeEntry(map, second)
        map[second] = "new-socket"

        assertEquals("old-socket", superseded)
        assertSame(second, map.keys.single())
        assertEquals("2.0", map.keys.single().version)

        // the old socket's late close no longer matches and leaves the live entry alone
        assertEquals(false, map.remove(first, "old-socket"))
        assertEquals("new-socket", map[second])
    }

    @Test
    fun `a first connect has nothing to supersede`() {
        assertNull(supersedeNodeEntry(ConcurrentHashMap<Node, String>(), node("1.0")))
    }
}
