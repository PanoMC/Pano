package com.panomc.node

import com.panomc.node.task.PluginInstallService
import com.panomc.node.util.FileHash
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The filename rule is the whole sandbox for a plugin install: the name Pano forwards is the only
 * part of the message that becomes a path on disk.
 */
class PluginInstallNameTest {
    @TempDir
    lateinit var root: File

    @Test
    fun `accepts an ordinary jar name`() {
        assertTrue(PluginInstallService.isJarName("EssentialsX-2.21.2.jar"))
        assertTrue(PluginInstallService.isJarName("worldedit.JAR"))
    }

    @Test
    fun `rejects traversal and separators`() {
        assertFalse(PluginInstallService.isJarName("../evil.jar"))
        assertFalse(PluginInstallService.isJarName("plugins/evil.jar"))
        assertFalse(PluginInstallService.isJarName("plugins\\evil.jar"))
        assertFalse(PluginInstallService.isJarName("..jar"))
    }

    @Test
    fun `rejects names that are not jars`() {
        assertFalse(PluginInstallService.isJarName("start.sh"))
        assertFalse(PluginInstallService.isJarName(".jar"))
        assertFalse(PluginInstallService.isJarName(""))
        assertFalse(PluginInstallService.isJarName(null))
    }

    @Test
    fun `rejects control characters and absurd lengths`() {
        assertFalse(PluginInstallService.isJarName("evil\u0000.jar"))
        assertFalse(PluginInstallService.isJarName("a".repeat(PluginInstallService.MAX_NAME_LENGTH) + ".jar"))
    }

    @Test
    fun `verifies a file against every published algorithm`() {
        val file = File(root, "sample.jar")

        file.writeText("pano")

        assertTrue(
            FileHash.matches(
                file,
                "SHA-1",
                FileHash.of(file, "SHA-1").uppercase()
            )
        )
        assertTrue(FileHash.matches(file, "SHA-512", FileHash.of(file, "SHA-512")))
        assertFalse(FileHash.matches(file, "SHA-256", "00"))
    }
}
