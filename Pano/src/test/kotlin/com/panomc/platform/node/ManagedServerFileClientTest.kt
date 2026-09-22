package com.panomc.platform.node

import com.panomc.platform.error.PathDenied
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ManagedServerFileClientTest {
    @Test
    fun `normalises a path the way the node does`() {
        assertEquals("plugins/Pano", ManagedServerFileClient.normalisePath("./plugins/./Pano/"))
        assertEquals("a/b", ManagedServerFileClient.normalisePath("a\\b"))
        assertEquals("", ManagedServerFileClient.normalisePath(null))
        assertEquals("", ManagedServerFileClient.normalisePath(""))
    }

    @Test
    fun `refuses traversal, absolute paths and drive letters`() {
        listOf("../etc", "a/../../b", "/etc/passwd", "C:/Windows", "a\u0000b").forEach { path ->
            assertThrows(PathDenied::class.java, { ManagedServerFileClient.normalisePath(path) }, path)
        }
    }

    @Test
    fun `refuses a path longer than anything a real file has`() {
        assertThrows(PathDenied::class.java) {
            ManagedServerFileClient.normalisePath("a".repeat(ManagedServerFileClient.MAX_PATH_LENGTH + 1))
        }
    }

    @Test
    fun `refuses an empty or oversized batch`() {
        assertThrows(PathDenied::class.java) { ManagedServerFileClient.normalisePaths(emptyList()) }
        assertThrows(PathDenied::class.java) { ManagedServerFileClient.normalisePaths(null) }
        assertThrows(PathDenied::class.java) {
            ManagedServerFileClient.normalisePaths(List(ManagedServerFileClient.MAX_PATHS + 1) { "a" })
        }
    }

    @Test
    fun `names a download after its last segment`() {
        assertEquals("config.yml", ManagedServerFileClient.fileNameOf("plugins/LuckPerms/config.yml"))
        assertEquals("download", ManagedServerFileClient.fileNameOf(""))
    }
}
