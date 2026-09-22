package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LocalNodeJarLocatorTest {
    private val workingDir = File("/srv/pano").absoluteFile
    private val runningJarDir = File("/opt/pano").absoluteFile

    private fun locate(
        configured: String? = null,
        property: String? = null,
        present: Set<String> = emptySet()
    ) = LocalNodeJarLocator.locate(configured, property, workingDir, runningJarDir) { it.path in present }

    @Test
    fun `an explicit config path wins over everything else`() {
        val configured = File("/custom/pano-node.jar").absolutePath

        val located = locate(
            configured = configured,
            property = File("/dev/pano-node.jar").absolutePath,
            present = setOf(configured, File("/dev/pano-node.jar").absolutePath)
        )

        assertEquals(configured, located?.path)
    }

    @Test
    fun `the system property wins over the build output`() {
        val property = File("/dev/pano-node.jar").absolutePath
        val devBuild = File(workingDir, LocalNodeJarLocator.DEV_RELATIVE_PATH).absolutePath

        assertEquals(property, locate(property = property, present = setOf(property, devBuild))?.path)
    }

    @Test
    fun `falls back to the gradle build output in a checkout`() {
        val devBuild = File(workingDir, LocalNodeJarLocator.DEV_RELATIVE_PATH).absolutePath

        assertEquals(devBuild, locate(present = setOf(devBuild))?.path)
    }

    @Test
    fun `falls back to the jar next to the running platform`() {
        val beside = File(runningJarDir, LocalNodeJarLocator.JAR_NAME).absolutePath

        assertEquals(beside, locate(present = setOf(beside))?.path)
    }

    @Test
    fun `returns null when nothing local answers, so the caller downloads`() {
        assertNull(locate())
    }

    @Test
    fun `ignores a blank configured path instead of looking for an empty file name`() {
        val devBuild = File(workingDir, LocalNodeJarLocator.DEV_RELATIVE_PATH).absolutePath

        assertEquals(devBuild, locate(configured = "   ", present = setOf(devBuild))?.path)
    }

    @Test
    fun `lists every place it looked, without duplicates`() {
        val candidates = LocalNodeJarLocator.candidates(null, null, workingDir, workingDir)

        assertEquals(candidates.map { it.path }.distinct().size, candidates.size)
        assertTrue(candidates.any { it.path.endsWith(LocalNodeJarLocator.DEV_RELATIVE_PATH) })
    }
}
