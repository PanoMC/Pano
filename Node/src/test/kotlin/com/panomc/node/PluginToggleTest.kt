package com.panomc.node

import com.panomc.node.files.FileService
import com.panomc.node.files.PluginScanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Turning a jar off and on again, which without a running server to ask is a rename.
 *
 * The interesting cases are all refusals: the Pano plugin, a name that is a path, a jar that is
 * not there, and a toggle that would land on top of a file that is.
 */
class PluginToggleTest {
    @TempDir
    lateinit var root: File

    private val scanner = PluginScanner()

    @Test
    fun `disabling renames the jar and enabling puts it back`() {
        file("LuckPerms.jar")

        val disabled = scanner.toggle(root, "LuckPerms.jar", enabled = false)

        assertEquals(PluginScanner.ToggleResult.Renamed("LuckPerms.jar.disabled", false), disabled)
        assertFalse(File(root, "LuckPerms.jar").exists())
        assertTrue(File(root, "LuckPerms.jar.disabled").isFile)

        // The panel lists the disabled name, so that is the name it sends back.
        val enabled = scanner.toggle(root, "LuckPerms.jar.disabled", enabled = true)

        assertEquals(PluginScanner.ToggleResult.Renamed("LuckPerms.jar", true), enabled)
        assertTrue(File(root, "LuckPerms.jar").isFile)
        assertFalse(File(root, "LuckPerms.jar.disabled").exists())
    }

    @Test
    fun `either name refers to the same jar`() {
        file("Thing.jar")

        // Asked to disable it by the name it will have afterwards.
        assertEquals(
            PluginScanner.ToggleResult.Renamed("Thing.jar.disabled", false),
            scanner.toggle(root, "Thing.jar.disabled", enabled = false)
        )
    }

    @Test
    fun `a toggle that changes nothing is still a success`() {
        file("Thing.jar")

        assertEquals(
            PluginScanner.ToggleResult.Renamed("Thing.jar", true),
            scanner.toggle(root, "Thing.jar", enabled = true)
        )
    }

    @Test
    fun `the Pano plugin can never be switched off from the panel it answers`() {
        file("Pano-1.2.3.jar")
        file("pano.jar")
        file("pano_velocity.jar")

        listOf("Pano-1.2.3.jar", "pano.jar", "pano_velocity.jar").forEach { name ->
            assertEquals(
                PluginScanner.ToggleResult.Failed(PluginScanner.ERROR_PANO_PLUGIN),
                scanner.toggle(root, name, enabled = false)
            )

            assertTrue(File(root, name).isFile)
        }

        // A plugin that merely starts with the same letters is not the Pano plugin.
        file("panoramas.jar")

        assertEquals(
            PluginScanner.ToggleResult.Renamed("panoramas.jar.disabled", false),
            scanner.toggle(root, "panoramas.jar", enabled = false)
        )
    }

    @Test
    fun `a file name that is a path is refused before anything is looked at`() {
        file("Thing.jar")

        listOf("../Thing.jar", "plugins/Thing.jar", "..\\Thing.jar", "", "Thing.txt", "Thing").forEach { name ->
            assertEquals(
                PluginScanner.ToggleResult.Failed(FileService.ERROR_PATH_DENIED),
                scanner.toggle(root, name, enabled = false)
            )
        }

        assertEquals(
            PluginScanner.ToggleResult.Failed(FileService.ERROR_PATH_DENIED),
            scanner.toggle(root, null, enabled = false)
        )
    }

    @Test
    fun `a jar that is not there cannot be toggled`() {
        assertEquals(
            PluginScanner.ToggleResult.Failed(FileService.ERROR_NOT_FOUND),
            scanner.toggle(root, "Missing.jar", enabled = false)
        )
    }

    @Test
    fun `a toggle never writes over a jar that already has the name`() {
        file("Thing.jar")
        file("Thing.jar.disabled")

        assertEquals(
            PluginScanner.ToggleResult.Failed(FileService.ERROR_EXISTS),
            scanner.toggle(root, "Thing.jar", enabled = false)
        )

        assertTrue(File(root, "Thing.jar").isFile)
        assertTrue(File(root, "Thing.jar.disabled").isFile)
    }

    @Test
    fun `a renamed jar is re-read rather than answered from the cache`() {
        file("Thing.jar")

        assertEquals(listOf("Thing.jar"), scanner.scan(root, PluginScanner.KIND_PLUGIN).map { it.file })

        scanner.toggle(root, "Thing.jar", enabled = false)

        val scanned = scanner.scan(root, PluginScanner.KIND_PLUGIN).single()

        assertEquals("Thing.jar.disabled", scanned.file)
        assertFalse(scanned.enabled)
    }

    private fun file(name: String) {
        File(root, name).writeText("not really a jar, and never opened")
    }
}
