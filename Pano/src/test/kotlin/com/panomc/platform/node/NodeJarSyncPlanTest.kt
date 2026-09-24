package com.panomc.platform.node

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NodeJarSyncPlanTest {
    private val devJar = File("/srv/pano/Node/build/libs/pano-node.jar")

    @Test
    fun `a release install replaces a jar from another version, and one it does not have`() {
        assertTrue(NodeJarSyncPlan.needsUnpack("1.0.0-alpha.520", chosenByOperator = false, jarVersion = "1.0.0-alpha.519"))
        assertTrue(NodeJarSyncPlan.needsUnpack("1.0.0-alpha.520", chosenByOperator = false, jarVersion = null))
        assertFalse(NodeJarSyncPlan.needsUnpack("1.0.0-alpha.520", chosenByOperator = false, jarVersion = "1.0.0-alpha.520"))
    }

    @Test
    fun `a development build unpacks its own copy every time`() {
        assertTrue(NodeJarSyncPlan.needsUnpack("local-build", chosenByOperator = false, jarVersion = "local-build"))
        assertTrue(NodeJarSyncPlan.needsUnpack("local-build", chosenByOperator = false, jarVersion = "1.0.0-alpha.1"))
        assertFalse(NodeJarSyncPlan.isReleaseBuild("local-build"))
        assertFalse(NodeJarSyncPlan.isReleaseBuild(""))
        assertTrue(NodeJarSyncPlan.isReleaseBuild("1.0.0"))
    }

    @Test
    fun `a jar somebody chose is left alone on every kind of build`() {
        assertFalse(NodeJarSyncPlan.needsUnpack("1.0.0-alpha.520", chosenByOperator = true, jarVersion = "1.0.0-alpha.1"))
        assertFalse(NodeJarSyncPlan.needsUnpack("local-build", chosenByOperator = true, jarVersion = "local-build"))
        assertFalse(NodeJarSyncPlan.needsUnpack("local-build", chosenByOperator = true, jarVersion = null))
    }

    @Test
    fun `the operator's jar is the configured one, the system property's, or the checkout's build`() {
        val configured = File("/opt/custom/pano-node.jar")
        val released = File("/srv/pano/pano-node.jar")

        assertTrue(NodeJarSyncPlan.isOperatorChoice(configured, configured.path, null, devJar))
        assertTrue(NodeJarSyncPlan.isOperatorChoice(configured, null, " ${configured.path} ", devJar))
        assertTrue(NodeJarSyncPlan.isOperatorChoice(devJar, null, null, devJar))
        assertFalse(NodeJarSyncPlan.isOperatorChoice(released, null, null, devJar))
        assertFalse(NodeJarSyncPlan.isOperatorChoice(released, configured.path, "", devJar))
    }

    @Test
    fun `the bundled jar is unpacked whole, over whatever was there, leaving no part file`(@TempDir dir: File) {
        val target = File(dir, "pano-node.jar")
        val bytes = ByteArray(200_000) { (it % 251).toByte() }

        target.writeBytes(byteArrayOf(1, 2, 3))

        val unpacked = NodeJarBundle.unpack(target, zip("pano-node.jar" to bytes, "README.txt" to "x".toByteArray()))

        assertEquals(target.absoluteFile, unpacked.absoluteFile)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(dir, "pano-node.jar.part").exists())
    }

    @Test
    fun `a zip without the jar, or no zip at all, changes nothing`(@TempDir dir: File) {
        val target = File(dir, "pano-node.jar")

        target.writeBytes(byteArrayOf(1, 2, 3))

        assertThrows(IllegalStateException::class.java) {
            NodeJarBundle.unpack(target, zip("something-else.jar" to byteArrayOf(9)))
        }
        assertThrows(IllegalStateException::class.java) {
            NodeJarBundle.unpack(target, null)
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(File(dir, "pano-node.jar.part").exists())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray().inputStream()
}
