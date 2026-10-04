package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.net.ReinstallKeep
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.task.ReinstallCarryOver
import com.panomc.node.task.SoftwareFamily
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executors

/**
 * What a reinstall carries over and how it swaps directories (SM-66, §2.4.31): worlds found by
 * `level.dat`, plugins and mods without the Pano jar, config files by family, the old directory
 * kept until the new one is registered and put back when anything fails, and a boot after a
 * daemon died half-way.
 */
class ReinstallCarryOverTest {
    @TempDir
    lateinit var root: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val servers get() = File(root, "servers").apply { mkdirs() }

    private fun file(directory: File, relative: String, text: String = relative): File =
        File(directory, relative).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    /** A Paper server with a custom-named world, the default ones, plugins and configs. */
    private fun oldServer(uuid: String = "s1"): File {
        val directory = File(servers, uuid).apply { mkdirs() }

        file(directory, "server.json", """{"uuid":"$uuid","name":"$uuid","software":"paper","jar":"server.jar","memoryMb":1024,"port":25565}""")
        file(directory, "server.jar", "old jar")
        file(directory, "world/level.dat")
        file(directory, "world_nether/DIM-1/region/r.0.0.mca")
        file(directory, "survival/level.dat")
        file(directory, "survival/region/r.0.0.mca")
        file(directory, "not-a-world/readme.txt")
        file(directory, "plugins/EssentialsX.jar")
        file(directory, "plugins/Essentials/config.yml")
        file(directory, "plugins/pano-spigot-1.0.0-alpha.62.jar")
        file(directory, "plugins/Pano/config.conf", "old token")
        file(directory, "server.properties", "level-name=survival\n")
        file(directory, "bukkit.yml")
        file(directory, "spigot.yml")
        file(directory, "config/paper-global.yml")
        file(directory, "config/paper-world-defaults.yml")
        file(directory, "config/other.yml")
        file(directory, "ops.json")
        file(directory, "whitelist.json")
        file(directory, "banned-players.json")
        file(directory, "eula.txt")
        file(directory, "logs/latest.log")

        return directory
    }

    /** What a fresh install leaves in the working directory before the carry-over. */
    private fun freshInstall(uuid: String = "s1"): File {
        val directory = File(servers, "$uuid${ReinstallCarryOver.INSTALLING_SUFFIX}").apply { mkdirs() }

        file(directory, "server.jar", "new jar")

        return directory
    }

    // ------------------------------------------------------------------------------- families

    @Test
    fun `the node's family table matches the contract`() {
        assertEquals(SoftwareFamily.BUKKIT, SoftwareFamily.of("Purpur"))
        assertEquals(SoftwareFamily.FABRIC, SoftwareFamily.of("quilt"))
        assertEquals(SoftwareFamily.FORGE, SoftwareFamily.of("neoforge"))
        assertEquals(SoftwareFamily.BUNGEE, SoftwareFamily.of("waterfall"))
        assertEquals(SoftwareFamily.VELOCITY, SoftwareFamily.of("velocity"))
        assertEquals(SoftwareFamily.VANILLA, SoftwareFamily.of("vanilla"))
        assertEquals(null, SoftwareFamily.of("sponge"))
        assertEquals(listOf("mods", "config"), SoftwareFamily.FABRIC.pluginPaths)
    }

    // --------------------------------------------------------------------------------- worlds

    @Test
    fun `worlds are every directory with a level dat plus world star`() {
        val old = oldServer()

        assertEquals(listOf("survival", "world", "world_nether"), ReinstallCarryOver.worlds(old).map { it.name })
        assertEquals(listOf("world", "world_nether"), ReinstallCarryOver.worlds(old, legacy = true).map { it.name })
    }

    // ------------------------------------------------------------------------ plugins and configs

    @Test
    fun `plugins are copied without the old Pano jar and without overwriting the new install`() {
        val old = oldServer()
        val work = freshInstall()

        // What the fresh install's Pano plugin step already wrote.
        file(work, "plugins/Pano/config.conf", "new token")

        ReinstallCarryOver.copyKept(old, work, ReinstallKeep(worlds = true, plugins = true, configs = false), "paper", "purpur")

        assertTrue(File(work, "plugins/EssentialsX.jar").isFile)
        assertTrue(File(work, "plugins/Essentials/config.yml").isFile)
        assertFalse(File(work, "plugins/pano-spigot-1.0.0-alpha.62.jar").exists())
        assertEquals("new token", File(work, "plugins/Pano/config.conf").readText())
        assertFalse(File(work, "server.properties").exists())
        // Copied, not moved: the old directory is the way back.
        assertTrue(File(old, "plugins/EssentialsX.jar").isFile)
    }

    @Test
    fun `a fabric server carries its mods and its config directory`() {
        val old = File(servers, "f1").apply { mkdirs() }

        file(old, "mods/lithium.jar")
        file(old, "mods/pano.jar")
        file(old, "config/lithium.properties")
        file(old, "server.properties")

        val work = freshInstall("f1")

        ReinstallCarryOver.copyKept(old, work, ReinstallKeep(plugins = true, configs = true), "fabric", "fabric")

        assertTrue(File(work, "mods/lithium.jar").isFile)
        assertFalse(File(work, "mods/pano.jar").exists())
        assertTrue(File(work, "config/lithium.properties").isFile)
        assertTrue(File(work, "server.properties").isFile)
    }

    @Test
    fun `configs within a family are the whole list, only the ones that exist`() {
        val old = oldServer()
        val work = freshInstall()

        val copied = ReinstallCarryOver.copyKept(old, work, ReinstallKeep(configs = true), "paper", "purpur")

        assertEquals(
            listOf(
                "banned-players.json", "bukkit.yml", "config/paper-global.yml", "config/paper-world-defaults.yml",
                "eula.txt", "ops.json", "server.properties", "spigot.yml", "whitelist.json"
            ),
            copied.sorted()
        )
        assertFalse(File(work, "config/other.yml").exists())
        assertFalse(File(work, "plugins").exists())
        assertFalse(File(work, "logs").exists())
    }

    @Test
    fun `configs across bukkit and fabric are the vanilla files only`() {
        val old = oldServer()
        val work = freshInstall()

        val copied = ReinstallCarryOver.copyKept(old, work, ReinstallKeep(configs = true), "paper", "fabric")

        assertEquals(listOf("banned-players.json", "ops.json", "server.properties", "whitelist.json"), copied.sorted())
    }

    @Test
    fun `the Pano jar pattern matches release, local and fallback names only`() {
        assertTrue(ReinstallCarryOver.isPanoPluginJar("pano.jar"))
        assertTrue(ReinstallCarryOver.isPanoPluginJar("pano-velocity-1.0.0.jar"))
        assertTrue(ReinstallCarryOver.isPanoPluginJar("Pano-Fabric-local-build.jar"))
        assertFalse(ReinstallCarryOver.isPanoPluginJar("panorama.jar"))
        assertFalse(ReinstallCarryOver.isPanoPluginJar("pano-addon-1.0.jar"))
    }

    // -------------------------------------------------------------------------------- the swap

    @Test
    fun `a swap moves the kept worlds and keeps the old directory until it is committed`() {
        val final = oldServer()
        val work = freshInstall()

        val swap = ReinstallCarryOver.swap(final, work, ReinstallKeep(worlds = true), now = 42)

        assertEquals("new jar", File(final, "server.jar").readText())
        assertTrue(File(final, "survival/region/r.0.0.mca").isFile)
        assertTrue(File(final, "world/level.dat").isFile)
        assertFalse(File(final, "not-a-world").exists())
        assertFalse(work.exists())

        val old = File(servers, "s1.old-42")

        assertTrue(old.isDirectory)
        assertEquals("old jar", File(old, "server.jar").readText())

        swap.commit(logger)

        assertFalse(old.exists())
    }

    @Test
    fun `worlds off leaves every world behind`() {
        val final = oldServer()
        val work = freshInstall()

        ReinstallCarryOver.swap(final, work, ReinstallKeep(worlds = false, plugins = false, configs = false)).commit()

        assertFalse(File(final, "world").exists())
        assertFalse(File(final, "survival").exists())
        assertTrue(File(final, "server.jar").isFile)
    }

    @Test
    fun `no keep list carries the world star folders as a reinstall always did`() {
        val final = oldServer()
        val work = freshInstall()

        ReinstallCarryOver.swap(final, work, keep = null).commit()

        assertTrue(File(final, "world").isDirectory)
        assertTrue(File(final, "world_nether").isDirectory)
        assertFalse(File(final, "survival").exists())
    }

    @Test
    fun `a rollback puts the old server back exactly as it was`() {
        val final = oldServer()
        val work = freshInstall()

        val swap = ReinstallCarryOver.swap(final, work, ReinstallKeep(worlds = true), now = 7)

        swap.rollback()

        assertEquals("old jar", File(final, "server.jar").readText())
        assertTrue(File(final, "survival/level.dat").isFile)
        assertTrue(File(final, "world_nether/DIM-1/region/r.0.0.mca").isFile)
        assertTrue(File(final, "plugins/pano-spigot-1.0.0-alpha.62.jar").isFile)
        assertFalse(File(servers, "s1.old-7").exists())
        assertFalse(work.exists())
    }

    @Test
    fun `a swap that fails half-way undoes itself`() {
        val final = oldServer()

        // A working directory that cannot exist (its parent is a file): the old directory has
        // already moved aside when moving the first world into it fails.
        val blocker = File(servers, "blocked").apply { writeText("x") }

        assertThrows(Exception::class.java) {
            ReinstallCarryOver.swap(final, File(blocker, "work"), ReinstallKeep(worlds = true), now = 9)
        }

        assertEquals("old jar", File(final, "server.jar").readText())
        assertTrue(File(final, "world/level.dat").isFile)
        assertTrue(File(final, "survival/level.dat").isFile)
        assertFalse(File(servers, "s1.old-9").exists())
    }

    // ---------------------------------------------------------------------------- after a crash

    @Test
    fun `a boot after a crash mid-swap restores the old server with its worlds`() {
        val final = oldServer()
        val work = freshInstall()

        // The state after the old directory moved away and a world moved into the new one.
        val old = File(servers, "s1.old-5")
        assertTrue(final.renameTo(old))
        assertTrue(File(old, "survival").renameTo(File(work, "survival")))

        ReinstallCarryOver.recover(servers, logger)

        assertTrue(File(final, "survival/level.dat").isFile)
        assertEquals("old jar", File(final, "server.jar").readText())
        assertFalse(old.exists())
        assertFalse(work.exists())
    }

    @Test
    fun `a boot after the swap but before the clean-up removes the old directory`() {
        oldServer()
        val old = oldServer("s1.old-5")

        ReinstallCarryOver.recover(servers, logger)

        assertFalse(old.exists())
        assertTrue(File(servers, "s1/server.json").isFile)
    }

    @Test
    fun `the registry never loads a reinstall's temporary directories as servers`() {
        oldServer()
        // Same uuid in its server.json, as a real leftover would have.
        val leftover = File(servers, "s1${ReinstallCarryOver.INSTALLING_SUFFIX}").apply { mkdirs() }
        file(leftover, "server.json", """{"uuid":"s1","name":"s1","software":"purpur","jar":"server.jar","memoryMb":1024,"port":25565}""")

        val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
        val listener = object : ServerProcessListener {
            override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

            override fun onConsoleReady(server: ServerProcess) {}
        }

        try {
            val registry = ServerRegistry(root, JavaRuntimeLocator(root), ProcessRuntime(), scheduler, logger, listener)

            registry.load()

            assertNotNull(registry.get("s1"))

            assertEquals("paper", registry.get("s1")!!.spec.software)
            assertEquals(1, registry.all().size)
            assertFalse(leftover.exists())
        } finally {
            scheduler.shutdownNow()
        }
    }
}
