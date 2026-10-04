package com.panomc.platform.node

import com.panomc.platform.node.dto.JavaRuntimeData
import com.panomc.platform.node.dto.NodeResources
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `GET /api/panel/nodes/:id/java`'s answer (SM-63, §2.4.28), built from a node's reply or from what
 * Pano stored. The reply is untrusted input from another machine, and the panel's Java card reads
 * one shape whatever happened — these pin both.
 */
class NodeJavaCatalogTest {
    private val stored = NodeResources(
        javaRuntimes = listOf(JavaRuntimeData(major = 17, path = "/usr/lib/jvm/java-17", vendor = "Arch", version = "17.0.16")),
        javaAutoDownload = true,
        javaDownloads = true
    )

    private val servers = mapOf(
        "uuid-lobby" to NodeJavaCatalog.ServerRef(4, "Lobby"),
        "uuid-survival" to NodeJavaCatalog.ServerRef(7, "Survival")
    )

    private fun reply() = JsonObject()
        .put("ok", true)
        .put("os", "linux")
        .put("arch", "x64")
        .put("libc", "glibc")
        .put("autoDownload", false)
        .put(
            "runtimes",
            JsonArray()
                .add(
                    JsonObject()
                        .put("major", 21)
                        .put("version", "21.0.12+7")
                        .put("vendor", "temurin")
                        .put("path", "/data/java/temurin-21.0.12+7")
                        .put("managed", true)
                        .put("usedBy", JsonArray().add("uuid-lobby").add("uuid-elsewhere").add("uuid-lobby"))
                )
                .add(JsonObject().put("major", 17).put("path", "/usr/lib/jvm/java-17"))
                .add(JsonObject().put("path", "/no/major"))
        )
        .put(
            "downloadable",
            JsonArray()
                .add(
                    JsonObject()
                        .put("major", 21)
                        .put("available", true)
                        .put("vendor", "temurin")
                        .put("version", "21.0.13+1")
                        .put("size", 52_000_000L)
                        .put("installedVersion", "21.0.12+7")
                        .put("updateAvailable", true)
                )
                .add(JsonObject().put("major", 8).put("available", false))
                .add(JsonObject().put("major", 500).put("available", true))
        )

    @Test
    fun `maps a reply into the card's shape`() {
        val json = NodeJavaCatalog.fromReply(reply(), stored, servers, "linux", "x64")

        assertTrue(json.getBoolean("online"))
        assertTrue(json.getBoolean("supported"))
        assertEquals("glibc", json.getString("libc"))
        assertFalse(json.getBoolean("autoDownload"))
        assertNull(json.getString("catalogError"))

        val runtimes = json.getJsonArray("runtimes")

        // The entry without a major is dropped; the other two survive in order.
        assertEquals(2, runtimes.size())

        val managed = runtimes.getJsonObject(0)

        assertEquals(21, managed.getInteger("major"))
        assertEquals("21.0.12+7", managed.getString("version"))
        assertTrue(managed.getBoolean("managed"))

        // A system runtime from a node that said nothing about it is not managed.
        assertFalse(runtimes.getJsonObject(1).getBoolean("managed"))
        assertEquals(0, runtimes.getJsonObject(1).getJsonArray("usedBy").size())
    }

    @Test
    fun `usedBy turns this node's uuids into servers and keeps the ones it does not know`() {
        val usedBy = NodeJavaCatalog.fromReply(reply(), stored, servers, null, null)
            .getJsonArray("runtimes")
            .getJsonObject(0)
            .getJsonArray("usedBy")

        // Duplicates collapse.
        assertEquals(2, usedBy.size())

        val lobby = usedBy.getJsonObject(0)

        assertEquals(4L, lobby.getLong("id"))
        assertEquals("Lobby", lobby.getString("name"))
        assertEquals("uuid-lobby", lobby.getString("uuid"))

        // Still in use, still listed: a Remove button must not look safe because Pano lacks a row.
        val unknown = usedBy.getJsonObject(1)

        assertNull(unknown.getValue("id"))
        assertNull(unknown.getValue("name"))
        assertEquals("uuid-elsewhere", unknown.getString("uuid"))
    }

    @Test
    fun `downloadable majors are copied through and nonsense is dropped`() {
        val downloadable = NodeJavaCatalog.fromReply(reply(), stored, servers, null, null).getJsonArray("downloadable")

        assertEquals(2, downloadable.size())

        val java21 = downloadable.getJsonObject(0)

        assertEquals(21, java21.getInteger("major"))
        assertTrue(java21.getBoolean("available"))
        assertEquals(52_000_000L, java21.getLong("size"))
        assertEquals("21.0.12+7", java21.getString("installedVersion"))
        assertTrue(java21.getBoolean("updateAvailable"))

        val java8 = downloadable.getJsonObject(1)

        assertFalse(java8.getBoolean("available"))
        assertFalse(java8.getBoolean("updateAvailable"))
        assertNull(java8.getValue("size"))
    }

    @Test
    fun `a resolver failure on the node is passed on, not turned into an error`() {
        val json = NodeJavaCatalog.fromReply(
            reply().put("downloadable", JsonArray()).put("catalogError", "api.adoptium.net: timed out"),
            stored,
            servers,
            null,
            null
        )

        assertEquals("api.adoptium.net: timed out", json.getString("catalogError"))
        assertEquals(0, json.getJsonArray("downloadable").size())
        assertEquals(2, json.getJsonArray("runtimes").size())
    }

    @Test
    fun `a reply that says no falls back to the stored runtimes`() {
        val json = NodeJavaCatalog.fromReply(
            JsonObject().put("ok", false).put("error", "BOOM"),
            stored,
            servers,
            "linux",
            "x64"
        )

        assertTrue(json.getBoolean("online"))
        assertEquals("BOOM", json.getString("catalogError"))
        assertEquals(17, json.getJsonArray("runtimes").getJsonObject(0).getInteger("major"))
        assertEquals("linux", json.getString("os"))
    }

    @Test
    fun `an offline node answers with what was stored`() {
        val json = NodeJavaCatalog.fallback(stored, online = false, supported = true, os = "linux", arch = "aarch64")

        assertFalse(json.getBoolean("online"))
        assertTrue(json.getBoolean("supported"))
        assertTrue(json.getBoolean("autoDownload"))
        assertEquals("aarch64", json.getString("arch"))
        assertEquals(0, json.getJsonArray("downloadable").size())

        val runtime = json.getJsonArray("runtimes").getJsonObject(0)

        assertEquals("17.0.16", runtime.getString("version"))
        assertEquals(0, runtime.getJsonArray("usedBy").size())
    }

    @Test
    fun `an old node is unsupported and keeps every key the card reads`() {
        val json = NodeJavaCatalog.fallback(NodeResources.EMPTY, online = true, supported = false, os = null, arch = null)

        assertFalse(json.getBoolean("supported"))
        assertNull(json.getValue("autoDownload"))

        listOf("online", "supported", "os", "arch", "libc", "autoDownload", "catalogError", "runtimes", "downloadable")
            .forEach { assertTrue(json.containsKey(it), "missing $it") }
    }

    @Test
    fun `majors and versions are validated`() {
        assertTrue(NodeJavaCatalog.isValidMajor(1))
        assertTrue(NodeJavaCatalog.isValidMajor(21))
        assertTrue(NodeJavaCatalog.isValidMajor(99))
        assertFalse(NodeJavaCatalog.isValidMajor(0))
        assertFalse(NodeJavaCatalog.isValidMajor(100))
        assertFalse(NodeJavaCatalog.isValidMajor(null))

        assertTrue(NodeJavaCatalog.isValidVersion("21.0.12+7"))
        assertTrue(NodeJavaCatalog.isValidVersion("1.8.0_462"))
        assertTrue(NodeJavaCatalog.isValidVersion("17.0.16-LTS"))
        assertFalse(NodeJavaCatalog.isValidVersion("../21"))
        assertFalse(NodeJavaCatalog.isValidVersion("21 0"))
        assertFalse(NodeJavaCatalog.isValidVersion(""))
    }
}
