package com.panomc.platform.gate

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import com.panomc.platform.SpringConfig
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.error.PluginApiLevelUnsupported
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * "If plugins and themes are not compatible, they are disabled and can never be switched on. But they can be
 * updated." The plugin half, at the manager the panel, the console and the install code all call.
 */
class LockedAddonsTest {
    @TempDir
    lateinit var tempDir: Path

    class FixturePlugin : PanoPlugin()

    private lateinit var vertx: Vertx
    private val pluginsDir: Path get() = tempDir.resolve("plugins")

    @BeforeEach
    fun setUp() {
        Files.createDirectories(pluginsDir)

        vertx = Vertx.vertx()

        SpringConfig.setDefaults(vertx, LoggerFactory.getLogger("locked-addons-test"))
        Main.applicationContext = AnnotationConfigApplicationContext()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun writeJar(file: String, id: String, apiLevel: String?, version: String = "1.0.0", dependencies: String? = null): Path {
        val manifest = Manifest()
        val attributes = manifest.mainAttributes

        attributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes[Attributes.Name("id")] = id
        attributes[Attributes.Name("name")] = id
        attributes[Attributes.Name("description")] = "fixture"
        attributes[Attributes.Name("pano-version")] = "1.0.0"
        attributes[Attributes.Name("main-class")] = FixturePlugin::class.java.name
        attributes[Attributes.Name("version")] = version
        attributes[Attributes.Name("developer")] = "test"
        attributes[Attributes.Name("license")] = "MIT"

        if (apiLevel != null) {
            attributes[Attributes.Name("api-level")] = apiLevel
        }

        if (dependencies != null) {
            attributes[Attributes.Name("dependencies")] = dependencies
        }

        val path = pluginsDir.resolve(file)

        JarOutputStream(Files.newOutputStream(path), manifest).close()

        return path
    }

    private fun booted(): PluginManager {
        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()
        manager.startPlugins()

        return manager
    }

    @Test
    fun `every enable route refuses an incompatible plugin and starts nothing`() {
        writeJar("old.jar", "old", "0")
        writeJar("new.jar", "new", "2")
        writeJar("ok.jar", "ok", "1")

        val manager = booted()

        manager.requireCompatible("ok")

        for ((id, verdict) in listOf("old" to "TOO_OLD", "new" to "TOO_NEW")) {
            val error = assertThrows(PluginApiLevelUnsupported::class.java) { manager.requireCompatible(id) }

            assertEquals("PLUGIN_API_LEVEL_UNSUPPORTED", error.code)
            assertTrue(error.encode().contains(verdict), error.encode())

            // the console, a reload, a dependent pulling it in and PF4J's own API all end at the same refusal
            assertFalse(manager.enablePlugin(id), id)
            assertEquals(PluginState.DISABLED, manager.startPlugin(id), id)
            assertEquals(PluginState.DISABLED, manager.reloadPlugin(id), id)
            assertEquals(PluginState.DISABLED, manager.getPlugin(id).pluginState, id)
        }

        // a restart: a fresh manager over the same folder holds them again
        manager.stopPlugins()

        val restarted = booted()

        assertEquals(PluginState.STARTED, restarted.getPlugin("ok").pluginState)
        assertEquals(PluginState.DISABLED, restarted.getPlugin("old").pluginState)
        assertEquals(PluginState.DISABLED, restarted.getPlugin("new").pluginState)

        restarted.stopPlugins()
    }

    @Test
    fun `a plugin that requires an incompatible plugin is refused with the dependency named`() {
        writeJar("old.jar", "old", "0")
        writeJar("dependent.jar", "dependent", "1", dependencies = "old")

        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()

        val error = assertThrows(PluginApiLevelUnsupported::class.java) { manager.requireCompatible("dependent") }

        assertTrue(error.encode().contains("\"dependencyId\":\"old\""), error.encode())
    }

    @Test
    fun `an incompatible plugin is replaced by a newer compatible one and comes back enabled`() {
        val jar = writeJar("market.jar", "market", "0", version = "1.0.0")
        val manager = booted()

        assertFalse(manager.isDisabledByAdmin("market"))

        // InstallManager.unloadPluginForInstall: stop, disable, unload. Nothing may be written as disabled.
        manager.stopPlugin("market")
        manager.disablePlugin("market")
        manager.unloadPlugin("market")

        assertFalse(Files.exists(pluginsDir.resolve("disabled.txt")), "the gate never writes disabled.txt")

        Files.delete(jar)
        val replaced = writeJar("market.jar", "market", "1", version = "1.1.0")

        manager.loadPlugin(replaced)
        manager.enablePlugin("market")

        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        assertEquals(Verdict.OK, manager.verdictOf("market"))

        manager.stopPlugins()
    }

    @Test
    fun `a plugin the admin switched off stays disabled after the update and can be enabled`() {
        Files.writeString(pluginsDir.resolve("disabled.txt"), "market\n")

        val jar = writeJar("market.jar", "market", "0", version = "1.0.0")
        val manager = booted()

        assertTrue(manager.isDisabledByAdmin("market"))
        assertEquals(Verdict.TOO_OLD, manager.verdictOf("market"))

        manager.stopPlugin("market")
        manager.disablePlugin("market")
        manager.unloadPlugin("market")

        Files.delete(jar)
        val replaced = writeJar("market.jar", "market", "1", version = "1.1.0")

        manager.loadPlugin(replaced)

        // InstallManager keeps it as the admin left it: loaded, not enabled, not started.
        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
        assertEquals(Verdict.OK, manager.verdictOf("market"))
        manager.requireCompatible("market")

        // and the admin can now switch it on
        assertTrue(manager.enablePlugin("market"))
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        assertFalse(manager.isDisabledByAdmin("market"))

        manager.stopPlugins()
    }

    private fun disabledTxt(): String? =
        pluginsDir.resolve("disabled.txt").takeIf { Files.exists(it) }?.let { Files.readString(it) }

    /** What InstallManager does to replace an installed plugin: stop, disable, unload, new jar, load, enable, start, lift. */
    private fun replaceLikeAnUpdate(manager: PluginManager, jar: Path, id: String, apiLevel: String): PluginState? {
        manager.stopPlugin(id)
        manager.disablePlugin(id)
        manager.unloadPlugin(id)

        Files.delete(jar)
        val replaced = writeJar(jar.fileName.toString(), id, apiLevel, version = "2.0.0")

        manager.loadPlugin(replaced)
        manager.enablePlugin(id)

        val state = manager.startPlugin(id)

        manager.liftHolds()

        return state
    }

    @Test
    fun `a plugin whose required dependency is held back is held back at boot and nothing is written to disabled txt`() {
        writeJar("old.jar", "old", "0")
        writeJar("dependent.jar", "dependent", "1", dependencies = "old")
        writeJar("free.jar", "free", "1")

        val manager = booted()

        assertEquals(PluginState.DISABLED, manager.getPlugin("old").pluginState)
        assertEquals(PluginState.DISABLED, manager.getPlugin("dependent").pluginState)
        assertEquals(PluginState.STARTED, manager.getPlugin("free").pluginState)

        val hold = manager.heldBy("dependent")!!

        assertEquals("old", hold.pluginId)
        assertEquals(Verdict.TOO_OLD, hold.verdict)
        assertEquals(null, manager.heldBy("old"), "the dependency is refused in its own right, not held")
        assertEquals(null, manager.heldBy("free"))
        assertEquals(Verdict.OK, manager.verdictOf("dependent"))
        assertEquals(null, disabledTxt(), "a hold is not the admin's choice")
        assertFalse(manager.isDisabledByAdmin("dependent"))

        // every start path stays closed
        assertFalse(manager.enablePlugin("dependent"))
        assertEquals(PluginState.DISABLED, manager.startPlugin("dependent"))
        assertEquals(PluginState.DISABLED, manager.reloadPlugin("dependent"))

        val error = assertThrows(PluginApiLevelUnsupported::class.java) { manager.requireCompatible("dependent") }

        assertTrue(error.encode().contains("\"dependencyId\":\"old\""), error.encode())
        assertEquals(null, disabledTxt())

        manager.stopPlugins()
    }

    @Test
    fun `a two step chain of required dependencies is held back as well`() {
        writeJar("old.jar", "old", "0")
        writeJar("middle.jar", "middle", "1", dependencies = "old")
        writeJar("top.jar", "top", "1", dependencies = "middle")

        val manager = booted()

        for (id in listOf("old", "middle", "top")) {
            assertEquals(PluginState.DISABLED, manager.getPlugin(id).pluginState, id)
        }

        val hold = manager.heldBy("top")!!

        assertEquals("old", hold.pluginId)
        assertEquals("middle", hold.via)
        assertEquals("old", manager.heldBy("middle")!!.pluginId)
        assertEquals(null, disabledTxt())

        val error = assertThrows(PluginApiLevelUnsupported::class.java) { manager.requireCompatible("top") }

        assertTrue(error.encode().contains("\"dependencyId\":\"old\""), error.encode())

        manager.stopPlugins()
    }

    @Test
    fun `an optional dependency never holds a plugin back`() {
        writeJar("old.jar", "old", "0")
        writeJar("dependent.jar", "dependent", "1", dependencies = "old?")

        val manager = booted()

        assertEquals(PluginState.STARTED, manager.getPlugin("dependent").pluginState)
        assertEquals(null, manager.heldBy("dependent"))
        manager.requireCompatible("dependent")

        manager.stopPlugins()
    }

    @Test
    fun `the dependent is started again in the same operation when the dependency is updated`() {
        val oldJar = writeJar("old.jar", "old", "0")
        writeJar("middle.jar", "middle", "1", dependencies = "old")
        writeJar("top.jar", "top", "1", dependencies = "middle")

        val manager = booted()

        assertEquals(PluginState.DISABLED, manager.getPlugin("top").pluginState)

        assertEquals(PluginState.STARTED, replaceLikeAnUpdate(manager, oldJar, "old", "1"))

        for (id in listOf("old", "middle", "top")) {
            assertEquals(PluginState.STARTED, manager.getPlugin(id).pluginState, id)
            assertEquals(null, manager.heldBy(id), id)
        }

        assertEquals(null, disabledTxt())

        manager.stopPlugins()
    }

    @Test
    fun `a dependent the admin disabled stays off after the dependency is updated`() {
        Files.writeString(pluginsDir.resolve("disabled.txt"), "dependent\n")

        val oldJar = writeJar("old.jar", "old", "0")
        writeJar("dependent.jar", "dependent", "1", dependencies = "old")
        writeJar("other.jar", "other", "1", dependencies = "old")

        val manager = booted()

        assertEquals(PluginState.STARTED, replaceLikeAnUpdate(manager, oldJar, "old", "1"))

        assertEquals(PluginState.DISABLED, manager.getPlugin("dependent").pluginState)
        assertTrue(manager.isDisabledByAdmin("dependent"))
        assertEquals(PluginState.STARTED, manager.getPlugin("other").pluginState)
        assertEquals("dependent", disabledTxt()!!.trim(), "disabled.txt is exactly what the admin wrote")

        manager.stopPlugins()
    }

    @Test
    fun `a dependency replaced between load and start frees its dependents for the bulk start`() {
        val oldJar = writeJar("old.jar", "old", "0")
        writeJar("dependent.jar", "dependent", "1", dependencies = "old")

        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("dependent").pluginState)

        // the boot reconcile: replace the dependency, then Main starts everything
        manager.stopPlugin("old")
        manager.disablePlugin("old")
        manager.unloadPlugin("old")
        Files.delete(oldJar)
        manager.loadPlugin(writeJar("old.jar", "old", "1", version = "2.0.0"))
        manager.liftHolds()
        manager.startPlugins()

        assertEquals(PluginState.STARTED, manager.getPlugin("old").pluginState)
        assertEquals(PluginState.STARTED, manager.getPlugin("dependent").pluginState)
        assertEquals(null, disabledTxt())

        manager.stopPlugins()
    }
}
