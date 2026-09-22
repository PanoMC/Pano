package com.panomc.platform.auth

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionServerScopeTest {
    @Test
    fun `a node without a server context is global`() {
        val context = JsonObject()

        assertTrue(PermissionServerScope.appliesTo(context, null), "global check must accept a global node")
        assertTrue(PermissionServerScope.appliesTo(context, 3L), "server check must accept a global node")
    }

    @Test
    fun `an unrelated context key keeps the node global`() {
        val context = JsonObject().put("pano", true)

        assertTrue(PermissionServerScope.appliesTo(context, null))
        assertTrue(PermissionServerScope.appliesTo(context, 4L))
    }

    @Test
    fun `a numeric server context only matches that server`() {
        val context = JsonObject().put("server", 3)

        assertTrue(PermissionServerScope.appliesTo(context, 3L), "must match the scoped server")
        assertFalse(PermissionServerScope.appliesTo(context, 4L), "must not match another server")
        assertFalse(PermissionServerScope.appliesTo(context, null), "must be ignored by a global check")
    }

    @Test
    fun `a numeric string server context only matches that server`() {
        val context = JsonObject().put("server", "3")

        assertTrue(PermissionServerScope.appliesTo(context, 3L))
        assertFalse(PermissionServerScope.appliesTo(context, 4L))
        assertFalse(PermissionServerScope.appliesTo(context, null))
    }

    @Test
    fun `a long server context matches beyond the int range`() {
        val serverId = 9_000_000_000L
        val context = JsonObject().put("server", serverId)

        assertTrue(PermissionServerScope.appliesTo(context, serverId))
        assertFalse(PermissionServerScope.appliesTo(context, 3L))
    }

    @Test
    fun `an array server context matches every listed server`() {
        val context = JsonObject().put("server", JsonArray().add(3).add(5))

        assertTrue(PermissionServerScope.appliesTo(context, 3L))
        assertTrue(PermissionServerScope.appliesTo(context, 5L))
        assertFalse(PermissionServerScope.appliesTo(context, 4L))
        assertFalse(PermissionServerScope.appliesTo(context, null))
    }

    @Test
    fun `an array server context accepts numeric strings`() {
        val context = JsonObject().put("server", JsonArray().add("3").add(5))

        assertTrue(PermissionServerScope.appliesTo(context, 3L))
        assertTrue(PermissionServerScope.appliesTo(context, 5L))
        assertFalse(PermissionServerScope.appliesTo(context, 4L))
    }

    @Test
    fun `a malformed server context matches nothing`() {
        val malformedContexts = listOf(
            JsonObject().put("server", "all"),
            JsonObject().put("server", true),
            JsonObject().put("server", 3.5),
            JsonObject().put("server", JsonObject().put("id", 3)),
            JsonObject().putNull("server")
        )

        malformedContexts.forEach { context ->
            assertFalse(PermissionServerScope.appliesTo(context, 3L), "must not match a server: $context")
            assertFalse(PermissionServerScope.appliesTo(context, null), "must not match globally: $context")
        }
    }

    @Test
    fun `an array with a malformed entry matches nothing`() {
        val context = JsonObject().put("server", JsonArray().add(3).add("all"))

        assertFalse(PermissionServerScope.appliesTo(context, 3L))
        assertFalse(PermissionServerScope.appliesTo(context, null))
    }

    @Test
    fun `an empty array server context matches nothing`() {
        val context = JsonObject().put("server", JsonArray())

        assertFalse(PermissionServerScope.appliesTo(context, 3L))
        assertFalse(PermissionServerScope.appliesTo(context, null))
    }
}
