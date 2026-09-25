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
}
