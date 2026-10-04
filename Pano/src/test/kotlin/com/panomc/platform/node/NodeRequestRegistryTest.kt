package com.panomc.platform.node

import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeRequestRegistryTest {
    @Test
    fun `pairs a reply with the request that is waiting for it`() = runBlocking {
        val registry = NodeRequestRegistry()

        val (first, firstResult) = registry.register(1)
        val (second, secondResult) = registry.register(1)

        registry.complete(1, second, JsonObject().put("ok", true).put("which", "second"))
        registry.complete(1, first, JsonObject().put("ok", true).put("which", "first"))

        assertEquals("first", firstResult.await().getString("which"))
        assertEquals("second", secondResult.await().getString("which"))
        assertEquals(0, registry.size())
    }

    @Test
    fun `refuses a reply from a node that did not get the request`() {
        val registry = NodeRequestRegistry()

        val (eventId, result) = registry.register(1)

        assertFalse(registry.complete(2, eventId, JsonObject().put("ok", true)))
        assertFalse(result.isCompleted)
        assertEquals(1, registry.size())
    }

    @Test
    fun `ignores a reply nobody is waiting for`() {
        val registry = NodeRequestRegistry()

        assertFalse(registry.complete(1, "00000000-0000-0000-0000-000000000000", JsonObject()))
        assertFalse(registry.complete(1, null, JsonObject()))
    }

    @Test
    fun `answers a request only once`() {
        val registry = NodeRequestRegistry()

        val (eventId, _) = registry.register(1)

        assertTrue(registry.complete(1, eventId, JsonObject().put("ok", true)))
        assertFalse(registry.complete(1, eventId, JsonObject().put("ok", true)))
    }

    @Test
    fun `fails everything in flight when a node disconnects`() = runBlocking {
        val registry = NodeRequestRegistry()

        val (_, mine) = registry.register(1)
        val (_, theirs) = registry.register(2)

        registry.failAll(1, IllegalStateException("gone"))

        assertThrows(IllegalStateException::class.java) { runBlocking { mine.await() } }
        assertFalse(theirs.isCompleted)
        assertEquals(1, registry.size())
    }

    @Test
    fun `forgets a request that timed out`() {
        val registry = NodeRequestRegistry()

        val (eventId, _) = registry.register(1)

        registry.forget(eventId)

        assertEquals(0, registry.size())
        assertFalse(registry.complete(1, eventId, JsonObject().put("ok", true)))
    }
}
