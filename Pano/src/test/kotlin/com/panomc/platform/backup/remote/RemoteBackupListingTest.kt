package com.panomc.platform.backup.remote

import com.panomc.platform.error.PageNotFound
import com.panomc.platform.model.PageRequest
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RemoteBackupListingTest {
    private fun archive(id: String, at: Long, status: String = "DONE") =
        JsonObject().put("id", id).put("status", status).put("createdAt", at).put("sizeBytes", 10)

    private fun snapshot(id: String, instance: String, at: Long, status: String = "DONE", own: Boolean = false) =
        JsonObject().put("id", id).put("instanceId", instance).put("instanceName", "Pano $instance").put("status", status)
            .put("createdAt", at).put("uniqueBytes", 5).put("own", own)

    private val backups = JsonObject()
        .put("instanceId", "me")
        .put(
            "panos",
            JsonArray()
                .add(JsonObject().put("instanceId", "other").put("instanceName", "Other").put("current", false).put("usedBytes", 7).put("backups", JsonArray().add(archive("a1", 100))))
                .add(JsonObject().put("instanceId", "me").put("instanceName", "Me").put("current", true).put("usedBytes", 20).put("backups", JsonArray().add(archive("a2", 300)).add(archive("a3", 50, "FAILED"))))
                .add(JsonObject().put("instanceId", "empty").put("instanceName", "Empty").put("current", false).put("backups", JsonArray()))
        )

    private val snapshots = JsonObject().put(
        "snapshots",
        JsonArray()
            .add(snapshot("s1", "me", 400, own = true))
            .add(snapshot("s2", "me", 200, "RUNNING", own = true))
            .add(snapshot("s3", "third", 500))
            .add(snapshot("s4", "me", 10, "ABORTED", own = true))
    )

    @Suppress("UNCHECKED_CAST")
    private fun ids(body: Map<String, Any?>) = (body["items"] as List<JsonObject>).map { it.getString("id") }

    @Test
    fun `only this Pano's rows, newest first, archives and snapshots mixed`() {
        val body = RemoteBackupListing.build(backups, snapshots, PageRequest(1, 50))

        assertEquals(listOf("s1", "a2", "s2"), ids(body))
        assertEquals("me", body["instanceId"])
        assertEquals(false, body.containsKey("groups"))
        assertEquals(listOf("snapshot", "archive", "snapshot"), (body["items"] as List<*>).map { (it as JsonObject).getString("type") })
        assertEquals(listOf("me"), (body["items"] as List<*>).map { (it as JsonObject).getString("instanceId") }.distinct())
    }

    @Test
    fun `the page is cut on the server`() {
        val first = RemoteBackupListing.build(backups, snapshots, PageRequest(1, 2))
        val last = RemoteBackupListing.build(backups, snapshots, PageRequest(2, 2))

        assertEquals(listOf("s1", "a2"), ids(first))
        assertEquals(listOf("s2"), ids(last))
        val info = last["page"] as Map<*, *>

        assertEquals("2 2 3 2", listOf("number", "size", "totalItems", "totalPages").joinToString(" ") { info[it].toString() })
        assertThrows<PageNotFound> { RemoteBackupListing.build(backups, snapshots, PageRequest(3, 2)) }
    }

    @Test
    fun `an empty account is one valid empty page`() {
        val body = RemoteBackupListing.build(JsonObject().put("instanceId", "me").put("panos", JsonArray()), JsonObject(), PageRequest(1, 10))

        assertEquals(emptyList<String>(), ids(body))
        assertEquals(0L, (body["page"] as Map<*, *>)["totalPages"])
    }
}
