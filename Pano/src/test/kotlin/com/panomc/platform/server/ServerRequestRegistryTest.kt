package com.panomc.platform.server

import com.panomc.platform.server.event.request.ConsoleHistoryResultEventRequest
import com.panomc.platform.server.message.ConsoleHistoryMessage
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerRequestRegistryTest {
    private fun reply(hasMore: Boolean) = ConsoleHistoryResultEventRequest(emptyList(), hasMore)

    @Test
    fun `pairs a reply with the request that is waiting for it`() = runBlocking {
        val registry = ServerRequestRegistry()

        val (first, firstResult) = registry.register(1)
        val (second, secondResult) = registry.register(1)

        registry.complete(1, second, reply(true))
        registry.complete(1, first, reply(false))

        assertEquals(false, (firstResult.await() as ConsoleHistoryResultEventRequest).hasMore)
        assertEquals(true, (secondResult.await() as ConsoleHistoryResultEventRequest).hasMore)
        assertEquals(0, registry.size())
    }

    @Test
    fun `refuses a reply from a server that did not get the request`() {
        val registry = ServerRequestRegistry()

        val (eventId, result) = registry.register(1)

        assertFalse(registry.complete(2, eventId, reply(false)))
        assertFalse(result.isCompleted)
        assertEquals(1, registry.size())
    }

    @Test
    fun `ignores a reply nobody is waiting for`() {
        val registry = ServerRequestRegistry()

        assertFalse(registry.complete(1, "00000000-0000-0000-0000-000000000000", reply(false)))
        assertFalse(registry.complete(1, null, reply(false)))
    }

    @Test
    fun `answers a request only once`() {
        val registry = ServerRequestRegistry()

        val (eventId, _) = registry.register(1)

        assertTrue(registry.complete(1, eventId, reply(false)))
        assertFalse(registry.complete(1, eventId, reply(false)))
    }

    @Test
    fun `fails everything in flight when a server disconnects`() = runBlocking {
        val registry = ServerRequestRegistry()

        val (_, mine) = registry.register(1)
        val (_, theirs) = registry.register(2)

        registry.failAll(1, IllegalStateException("gone"))

        assertThrows(IllegalStateException::class.java) { runBlocking { mine.await() } }
        assertFalse(theirs.isCompleted)
        assertEquals(1, registry.size())
    }

    @Test
    fun `forgets a request that timed out`() {
        val registry = ServerRequestRegistry()

        val (eventId, _) = registry.register(1)

        registry.forget(eventId)

        assertEquals(0, registry.size())
        assertFalse(registry.complete(1, eventId, reply(false)))
    }

    @Test
    fun `asks for a console page in the shape the plugin protocol says`() {
        val message = ConsoleHistoryMessage(500, 1000)

        message.eventId = "11111111-2222-3333-4444-555555555555"

        val encoded = JsonObject(message.encode())

        assertEquals("CONSOLE_HISTORY", message.getResponseName())
        assertEquals("CONSOLE_HISTORY", encoded.getString("event"))
        assertEquals("11111111-2222-3333-4444-555555555555", encoded.getString("eventId"))
        assertEquals(500, encoded.getInteger("limit"))
        assertEquals(1000, encoded.getInteger("skip"))
        // A plain page asks nothing of a plugin that predates search.
        assertEquals(null, encoded.getString("query"))
    }

    @Test
    fun `a console search carries its query to the plugin`() {
        val encoded = JsonObject(ConsoleHistoryMessage(500, 5, "joined").encode())

        assertEquals("CONSOLE_HISTORY", encoded.getString("event"))
        assertEquals("joined", encoded.getString("query"))
        assertEquals(5, encoded.getInteger("skip"))
    }
}
