package com.panomc.node

import com.panomc.node.server.JvmHeap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmHeapTest {
    @Test
    fun `the heap leaves the JVM its own share of the setting`() {
        assertEquals(794, JvmHeap.heapMb(1536))
        assertEquals(1229, JvmHeap.heapMb(2048))
        assertEquals(1664, JvmHeap.heapMb(2560))
        assertEquals(2970, JvmHeap.heapMb(4096))
    }

    @Test
    fun `the share is 512 MB plus 15 percent, at most 2 GB, and the heap at least half`() {
        assertEquals(512, JvmHeap.heapMb(1024))
        assertEquals(6451, JvmHeap.heapMb(8192))
        assertEquals(14336, JvmHeap.heapMb(16384))
    }

    @Test
    fun `a server process gets the allocator setting unless it brought its own`() {
        val environment = mutableMapOf("_JAVA_OPTIONS" to "-Xmx1G", "PATH" to "/usr/bin")

        com.panomc.node.server.ServerProcess.sanitizeChildEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin", "MALLOC_ARENA_MAX" to "2"), environment)

        val own = mutableMapOf("MALLOC_ARENA_MAX" to "8")

        com.panomc.node.server.ServerProcess.sanitizeChildEnvironment(own)

        assertEquals("8", own["MALLOC_ARENA_MAX"])
    }

    @Test
    fun `a tiny setting still gets half of itself`() {
        assertEquals(256, JvmHeap.heapMb(512))
    }

    @Test
    fun `no setting is passed through`() {
        assertEquals(0, JvmHeap.heapMb(0))
    }

    @Test
    fun `the heap starts at a quarter of its maximum, at least 256 MB`() {
        assertEquals(288, JvmHeap.initialHeapMb(1152))
        assertEquals(742, JvmHeap.initialHeapMb(2970))
        assertEquals(256, JvmHeap.initialHeapMb(512))
        assertEquals(200, JvmHeap.initialHeapMb(200))
    }

    @Test
    fun `memory is only handed back on a JVM that knows how`() {
        assertEquals(
            listOf("-XX:G1PeriodicGCInterval=30000", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40"),
            JvmHeap.returnMemoryFlags(21)
        )
        assertEquals(emptyList<String>(), JvmHeap.returnMemoryFlags(11))
        assertEquals(emptyList<String>(), JvmHeap.returnMemoryFlags(8))
        assertEquals(emptyList<String>(), JvmHeap.returnMemoryFlags(null))
    }

    @Test
    fun `the launch command puts the heap first and the admin's flags after`() {
        val command = com.panomc.node.server.ServerProcess.buildCommand(
            "java",
            "server.jar",
            com.panomc.node.server.ServerSpec(software = "paper", memoryMb = 1536, jvmArgs = listOf("-Xms1G")),
            21
        )

        assertEquals(
            listOf(
                "java", "-Xms256M", "-Xmx794M",
                "-XX:G1PeriodicGCInterval=30000", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40",
                "-Xms1G", "-jar", "server.jar", "nogui"
            ),
            command
        )
    }
}
