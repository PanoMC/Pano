package com.panomc.platform.node

import com.google.gson.Gson
import com.panomc.platform.node.dto.JavaRuntimeData
import com.panomc.platform.node.dto.NodeResources
import com.panomc.platform.node.event.TaskProgressEvent
import com.panomc.platform.node.event.request.NodeHelloEventRequest
import com.panomc.platform.node.event.request.NodeJavaRuntimesEventRequest
import com.panomc.platform.node.event.request.ServerStateEventRequest
import com.panomc.platform.node.message.JavaCatalogMessage
import com.panomc.platform.node.message.JavaInstallMessage
import com.panomc.platform.node.message.JavaRemoveMessage
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Java runtime half of the node protocol (SM-63, §2.4.28), as it comes off and goes on the wire.
 *
 * Decoded with a plain Gson because that is what `NodeManager` uses: no adapters, explicit nulls
 * written straight into Kotlin fields. Old nodes must keep working, so every new field has a
 * test for its absence.
 */
class NodeJavaRuntimesTest {
    private val gson = Gson()

    @Test
    fun `a new hello carries versions, managed flags and the auto-download setting`() {
        val hello = gson.fromJson(
            """
            {"event":"NODE_HELLO","protocolVersion":3,"javaAutoDownload":true,
             "javaRuntimes":[
               {"major":21,"path":"/d/java/temurin-21","vendor":"temurin","version":"21.0.12+7","managed":true},
               {"major":17,"path":"/usr/lib/jvm/java-17","vendor":null,"version":"17.0.16"}
             ]}
            """.trimIndent(),
            NodeHelloEventRequest::class.java
        )

        val resources = NodeResources.fromReported(
            cpuCores = hello.cpuCores,
            memTotal = hello.memTotal,
            diskTotal = hello.diskTotal,
            javaRuntimes = hello.javaRuntimes,
            javaAutoDownload = hello.javaAutoDownload,
            javaDownloads = NodeJavaSupport.supportsDownloads(hello.protocolVersion, hello.javaAutoDownload, hello.capabilities)
        )

        assertEquals(2, resources.javaRuntimes.size)
        assertTrue(resources.javaRuntimes[0].managed)
        assertEquals("21.0.12+7", resources.javaRuntimes[0].version)
        assertFalse(resources.javaRuntimes[1].managed)
        assertEquals(true, resources.javaAutoDownload)
        assertTrue(resources.javaDownloads)
    }

    @Test
    fun `an old hello leaves the new fields at their defaults`() {
        val hello = gson.fromJson(
            """{"protocolVersion":2,"javaRuntimes":[{"major":21,"path":"/usr/lib/jvm/java-21"}]}""",
            NodeHelloEventRequest::class.java
        )

        assertNull(hello.javaAutoDownload)
        assertNull(hello.capabilities)

        val runtime = JavaRuntimeData.sanitizeAll(hello.javaRuntimes).single()

        assertNull(runtime.version)
        assertFalse(runtime.managed)
        assertFalse(NodeJavaSupport.supportsDownloads(hello.protocolVersion, hello.javaAutoDownload, hello.capabilities))
    }

    @Test
    fun `any one signal is enough to call a node capable`() {
        assertTrue(NodeJavaSupport.supportsDownloads(NodeProtocol.JAVA_RUNTIMES_VERSION, null, null))
        assertTrue(NodeJavaSupport.supportsDownloads(2, false, null))
        assertTrue(NodeJavaSupport.supportsDownloads(null, null, listOf("other", NodeJavaSupport.CAPABILITY)))
        assertFalse(NodeJavaSupport.supportsDownloads(null, null, null))
        assertFalse(NodeJavaSupport.supportsDownloads(2, null, listOf("other", null)))
    }

    @Test
    fun `a runtime list survives explicit nulls and junk`() {
        val request = gson.fromJson(
            """{"javaRuntimes":[{"major":21,"path":null,"managed":null},null,{"major":0,"path":"/x"},{"major":25,"version":"   "}]}""",
            NodeJavaRuntimesEventRequest::class.java
        )

        val runtimes = JavaRuntimeData.sanitizeAll(request.javaRuntimes)

        assertEquals(listOf(21, 25), runtimes.map { it.major })
        assertEquals("", runtimes[0].path)
        assertFalse(runtimes[0].managed)
        assertNull(runtimes[1].version)
    }

    @Test
    fun `NODE_JAVA_RUNTIMES replaces only the runtimes`() {
        val before = NodeResources(
            cpuCores = 8,
            memTotal = 1024,
            diskTotal = 2048,
            javaRuntimes = listOf(JavaRuntimeData(major = 17, path = "/a")),
            javaAutoDownload = false,
            javaDownloads = true
        )

        val after = before.withJavaRuntimes(listOf(JavaRuntimeData(major = 25, path = "/b", managed = true)))

        assertEquals(8, after.cpuCores)
        assertEquals(2048, after.diskTotal)
        assertEquals(false, after.javaAutoDownload)
        assertTrue(after.javaDownloads)
        assertEquals(25, after.javaRuntimes.single().major)
        assertTrue(after.javaRuntimes.single().managed)
    }

    @Test
    fun `resources round-trip through the stored column, and old columns still load`() {
        val resources = NodeResources(
            cpuCores = 4,
            javaRuntimes = listOf(JavaRuntimeData(major = 21, path = "/p", vendor = "temurin", version = "21.0.12", managed = true)),
            javaAutoDownload = true,
            javaDownloads = true
        )

        assertEquals(resources, NodeResources.decode(resources.encode()))

        val old = NodeResources.decode("""{"cpuCores":2,"javaRuntimes":[{"major":17,"path":"/j","vendor":"x"}]}""")

        assertNull(old.javaAutoDownload)
        assertFalse(old.javaDownloads)
        assertFalse(old.javaRuntimes.single().managed)
        assertNull(old.javaRuntimes.single().version)
    }

    @Test
    fun `the node row's public JSON carries the new runtime fields`() {
        val json = JsonObject.mapFrom(
            NodeResources(
                javaRuntimes = listOf(JavaRuntimeData(major = 21, path = "/p", version = "21.0.12", managed = true)),
                javaAutoDownload = true,
                javaDownloads = true
            )
        )

        val runtime = json.getJsonArray("javaRuntimes").getJsonObject(0)

        assertEquals("21.0.12", runtime.getString("version"))
        assertTrue(runtime.getBoolean("managed"))
        assertTrue(json.getBoolean("javaAutoDownload"))
        assertTrue(json.getBoolean("javaDownloads"))
    }

    @Test
    fun `SERVER_STATE may explain a stop for a machine, and an old node does not`() {
        val state = gson.fromJson(
            """{"serverUuid":"u","state":"STOPPED","reason":"No Java 21 runtime on this host","reasonCode":"JAVA_MISSING","javaMajor":21}""",
            ServerStateEventRequest::class.java
        )

        assertEquals("JAVA_MISSING", state.reasonCode)
        assertEquals(21, state.javaMajor)

        val old = gson.fromJson("""{"serverUuid":"u","state":"STOPPED"}""", ServerStateEventRequest::class.java)

        assertNull(old.reasonCode)
        assertNull(old.javaMajor)
    }

    @Test
    fun `Java messages go out under their contract names`() {
        val catalog = JavaCatalogMessage().apply { eventId = "e1" }
        val catalogJson = JsonObject(catalog.encode())

        assertEquals("JAVA_CATALOG", catalogJson.getString("event"))
        assertEquals("e1", catalogJson.getString("eventId"))

        val install = JsonObject(JavaInstallMessage("t1", 21).encode())

        assertEquals("JAVA_INSTALL", install.getString("event"))
        assertEquals("t1", install.getString("taskId"))
        assertEquals(21, install.getInteger("major"))

        val remove = JsonObject(JavaRemoveMessage("t2", 17, "17.0.16+8").encode())

        assertEquals("JAVA_REMOVE", remove.getString("event"))
        assertEquals(17, remove.getInteger("major"))
        assertEquals("17.0.16+8", remove.getString("version"))

        // No version is "the managed runtime of that major", sent as an explicit null.
        assertNull(JsonObject(JavaRemoveMessage("t3", 17).encode()).getValue("version"))
    }

    @Test
    fun `Java task kinds resolve and only a node-started install may be opened by the node`() {
        assertEquals(ServerTaskKind.JAVA_INSTALL, ServerTaskKind.fromId("JAVA_INSTALL"))
        assertEquals(ServerTaskKind.JAVA_REMOVE, ServerTaskKind.fromId("java_remove"))

        assertTrue(ServerTaskKind.JAVA_INSTALL.mayBeOpenedByNode)

        ServerTaskKind.entries
            .filter { it != ServerTaskKind.JAVA_INSTALL }
            .forEach { assertFalse(it.mayBeOpenedByNode, "$it must not be opened by a node") }
    }

    @Test
    fun `a node-opened task id has to fit the task table`() {
        assertTrue(TaskProgressEvent.isTaskUuid("3f2b8c1e-5a4d-4e8f-9b7a-1c2d3e4f5a6b"))
        assertFalse(TaskProgressEvent.isTaskUuid("3f2b8c1e-5a4d-4e8f-9b7a-1c2d3e4f5a6b-and-more"))
        assertFalse(TaskProgressEvent.isTaskUuid("'; DROP TABLE"))
        assertFalse(TaskProgressEvent.isTaskUuid(""))
    }
}
