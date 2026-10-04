package com.panomc.node

import com.panomc.node.host.HostMetrics
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class HostMemoryTest {
    private fun meminfo(total: Long, free: Long, available: Long): File =
        File.createTempFile("meminfo", null).apply {
            deleteOnExit()
            writeText(
                """
                MemTotal:       $total kB
                MemFree:        $free kB
                MemAvailable:   $available kB
                Cached:          4280400 kB
                """.trimIndent()
            )
        }

    @Test
    fun `available memory is used when meminfo describes the same host`() {
        val file = meminfo(32243280, 964292, 4832732)

        assertEquals(4832732L * 1024, HostMetrics.linuxAvailable(32243280L * 1024, file))
    }

    @Test
    fun `a container limit keeps the bean's own figure`() {
        val file = meminfo(32243280, 964292, 4832732)

        assertNull(HostMetrics.linuxAvailable(4L * 1024 * 1024 * 1024, file))
    }

    @Test
    fun `no MemAvailable line falls back`() {
        val file = File.createTempFile("meminfo", null).apply {
            deleteOnExit()
            writeText("MemTotal:       32243280 kB\nMemFree:        964292 kB\n")
        }

        assertNull(HostMetrics.linuxAvailable(32243280L * 1024, file))
    }
}
