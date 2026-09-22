package com.panomc.platform.auth

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PermissionNodeContextTest {
    @Test
    fun `a missing context becomes an empty object`() {
        assertEquals(JsonObject(), PermissionNodeContext.sanitize(null))
    }

    @Test
    fun `a context that is not an object is refused`() {
        assertNull(PermissionNodeContext.sanitize("server"))
        assertNull(PermissionNodeContext.sanitize(JsonArray().add(1)))
        assertNull(PermissionNodeContext.sanitize(7))
    }

    @Test
    fun `unrelated keys are passed through untouched`() {
        val context = JsonObject().put("pano", true).put("world", "nether")

        assertEquals(context, PermissionNodeContext.sanitize(context))
    }

    @Test
    fun `server ids are normalised to numbers`() {
        val sanitized = PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add(2).add("3")))

        assertNotNull(sanitized)
        assertEquals(JsonArray().add(2L).add(3L), sanitized!!.getJsonArray("server"))
    }

    @Test
    fun `a single server id is accepted as an array of one`() {
        val sanitized = PermissionNodeContext.sanitize(JsonObject().put("server", 4))

        assertEquals(JsonArray().add(4L), sanitized!!.getJsonArray("server"))
    }

    @Test
    fun `duplicate server ids are collapsed`() {
        val sanitized = PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add(5).add("5")))

        assertEquals(JsonArray().add(5L), sanitized!!.getJsonArray("server"))
    }

    @Test
    fun `an empty server list stays empty, which scopes the node to nothing`() {
        val sanitized = PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray()))

        assertEquals(JsonArray(), sanitized!!.getJsonArray("server"))
    }

    @Test
    fun `a malformed server id is refused rather than dropped`() {
        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add("all"))))
        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add(1.5))))
        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add(0))))
        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add(-3))))
        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", true)))
    }

    @Test
    fun `more server ids than the cap are refused`() {
        val servers = JsonArray()

        (1..PermissionNodeContext.MAX_SERVER_IDS + 1).forEach { servers.add(it) }

        assertNull(PermissionNodeContext.sanitize(JsonObject().put("server", servers)))
    }

    @Test
    fun `what comes out is what the scope reader accepts`() {
        val sanitized = PermissionNodeContext.sanitize(JsonObject().put("server", JsonArray().add("8")))!!

        assert(PermissionServerScope.appliesTo(sanitized, 8L))
        assert(!PermissionServerScope.appliesTo(sanitized, 9L))
        assert(!PermissionServerScope.appliesTo(sanitized, null))
    }
}
