package com.panomc.node

import com.panomc.node.server.JvmHeap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JvmHeapTest {
    @Test
    fun `the heap leaves the JVM its own share of the setting`() {
        assertEquals(1152, JvmHeap.heapMb(1536))
        assertEquals(1536, JvmHeap.heapMb(2048))
        assertEquals(3072, JvmHeap.heapMb(4096))
    }

    @Test
    fun `the share is at least 384 MB and at most 1 GB`() {
        assertEquals(640, JvmHeap.heapMb(1024))
        assertEquals(7168, JvmHeap.heapMb(8192))
        assertEquals(15360, JvmHeap.heapMb(16384))
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
        assertEquals(768, JvmHeap.initialHeapMb(3072))
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
                "java", "-Xms288M", "-Xmx1152M",
                "-XX:G1PeriodicGCInterval=30000", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40",
                "-Xms1G", "-jar", "server.jar", "nogui"
            ),
            command
        )
    }
}
