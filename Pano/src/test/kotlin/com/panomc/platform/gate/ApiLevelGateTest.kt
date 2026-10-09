package com.panomc.platform.gate

import com.google.gson.GsonBuilder
import com.panomc.platform.ApiLevel
import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.Main
import com.panomc.platform.PanoManifestPluginDescriptorFinder
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.SpringConfig
import com.panomc.platform.UIManager
import com.panomc.platform.UIManager.Companion.InstalledBy
import com.panomc.platform.UIManager.Companion.InstalledTheme
import com.panomc.platform.UIManager.Companion.ThemeManifest
import com.panomc.platform.UIManager.Companion.encode
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.adapter.StrictNotNullTypeAdapterFactory
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.CompoundPluginDescriptorFinder
import org.pf4j.DefaultPluginManager
import org.pf4j.Plugin
import org.pf4j.PluginState
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * PF-24: the API level and the compatibility gate (doc 04 section 7, gate 1).
 *
 * The plugin tests use fixture jars written into a temp folder: a manifest and nothing else, the plugin class comes
 * from the test classpath. The plain PF4J test shows PF4J itself honours `isPluginValid` in `startPlugins`, `startPlugin` and `enablePlugin`, which is what the real [PluginManager] relies on.
 */
class ApiLevelGateTest {
    @TempDir
    lateinit var tempDir: Path

    /** Started by the plain PF4J proof: no Pano base class needed. */
    class PlainPlugin : Plugin()

    /** Started by the real [PluginManager], whose factory only builds [PanoPlugin]s. */
    class FixturePlugin : PanoPlugin() {
        init {
            created.incrementAndGet()
        }

        companion object {
            val created = java.util.concurrent.atomic.AtomicInteger()
        }
    }

    private lateinit var vertx: Vertx
    private val pluginsDir: Path get() = tempDir.resolve("plugins")

    @BeforeEach
    fun setUp() {
        Files.createDirectories(pluginsDir)

        vertx = Vertx.vertx()

        // PluginFactory reads these for every plugin it builds; the test never uses them.
        SpringConfig.setDefaults(vertx, LoggerFactory.getLogger("api-level-gate-test"))
        Main.applicationContext = AnnotationConfigApplicationContext()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Writes `plugins/<file>` with the attributes PanoManifestPluginDescriptorFinder reads. [apiLevel] null = no attribute. */
    private fun writeJar(file: String, id: String, apiLevel: String?, mainClass: Class<*> = FixturePlugin::class.java, version: String = "1.0.0"): Path {
        val manifest = Manifest()
        val attributes = manifest.mainAttributes

        attributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        attributes[Attributes.Name("id")] = id
        attributes[Attributes.Name("name")] = id
        attributes[Attributes.Name("description")] = "fixture"
        attributes[Attributes.Name("pano-version")] = "1.0.0"
        attributes[Attributes.Name("main-class")] = mainClass.name
        attributes[Attributes.Name("version")] = version
        attributes[Attributes.Name("developer")] = "test"
        attributes[Attributes.Name("license")] = "MIT"

        if (apiLevel != null) {
            attributes[Attributes.Name("api-level")] = apiLevel
        }

        val path = pluginsDir.resolve(file)

        JarOutputStream(Files.newOutputStream(path), manifest).close()

        return path
    }

    private fun disabledTxt() = pluginsDir.resolve("disabled.txt")

    // ---- the gate -----------------------------------------------------------------------------

    @Test
    fun `levels are read from the build resource and current is not below min`() {
        assertEquals(1, ApiLevel.CURRENT)
        assertEquals(1, ApiLevel.MIN_SUPPORTED)
        assertEquals(3, ApiLevel.MIN_MC_PROTOCOL)
        assertTrue(ApiLevel.MIN_NODE_PROTOCOL > 5, "the node protocol of the cutover is one above the pre-cutover value")
    }

    @Test
    fun `level 0 is too old, 1 is ok, 2 is too new, absent reads as 0`() {
        assertEquals(Verdict.TOO_OLD, ApiLevelGate.check(0))
        assertEquals(Verdict.OK, ApiLevelGate.check(1))
        assertEquals(Verdict.TOO_NEW, ApiLevelGate.check(2))
        assertEquals(Verdict.TOO_OLD, ApiLevelGate.check(-1))
    }

    @Test
    fun `an explicit range includes both ends`() {
        assertEquals(Verdict.TOO_OLD, ApiLevelGate.check(2, 3, 5))
        assertEquals(Verdict.OK, ApiLevelGate.check(3, 3, 5))
        assertEquals(Verdict.OK, ApiLevelGate.check(5, 3, 5))
        assertEquals(Verdict.TOO_NEW, ApiLevelGate.check(6, 3, 5))
    }

    // ---- the descriptor -----------------------------------------------------------------------

    @Test
    fun `the descriptor reads the api-level attribute and treats a missing or malformed one as 0`() {
        val finder = PanoManifestPluginDescriptorFinder()

        fun level(file: String, value: String?) =
            (finder.find(writeJar(file, "p-$file", value)) as PanoPluginDescriptor).apiLevel

        assertEquals(1, level("one.jar", "1"))
        assertEquals(7, level("seven.jar", " 7 "))
        assertEquals(0, level("absent.jar", null))
        assertEquals(0, level("junk.jar", "latest"))
    }

    // ---- PF4J honours isPluginValid -----------------------------------------------------------

    /** Plain PF4J with the gate as `isPluginValid`: the mechanism the real manager uses, without Pano's own hooks. */
    private fun plainManager() = object : DefaultPluginManager(pluginsDir) {
        override fun createPluginDescriptorFinder() = CompoundPluginDescriptorFinder().add(PanoManifestPluginDescriptorFinder())

        override fun isPluginValid(pluginWrapper: PluginWrapper): Boolean =
            ApiLevelGate.check((pluginWrapper.descriptor as PanoPluginDescriptor).apiLevel) == Verdict.OK
    }

    @Test
    fun `PF4J holds a refused plugin disabled in startPlugins, startPlugin and enablePlugin and writes nothing`() {
        writeJar("old.jar", "old", "0", PlainPlugin::class.java)
        writeJar("new.jar", "new", "2", PlainPlugin::class.java)
        writeJar("ok.jar", "ok", "1", PlainPlugin::class.java)

        val manager = plainManager()

        manager.loadPlugins()

        // loadPlugin already wrapped the refused ones as DISABLED.
        assertEquals(PluginState.DISABLED, manager.getPlugin("old").pluginState)
        assertEquals(PluginState.DISABLED, manager.getPlugin("new").pluginState)
        assertEquals(PluginState.RESOLVED, manager.getPlugin("ok").pluginState)

        // startPlugins skips them and starts the compatible one.
        manager.startPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("old").pluginState)
        assertEquals(PluginState.DISABLED, manager.getPlugin("new").pluginState)
        assertEquals(PluginState.STARTED, manager.getPlugin("ok").pluginState)

        // startPlugin enables a disabled plugin first, and enablePlugin refuses.
        assertEquals(PluginState.DISABLED, manager.startPlugin("old"))
        assertEquals(PluginState.DISABLED, manager.startPlugin("new"))
        assertFalse(manager.enablePlugin("old"))
        assertFalse(manager.enablePlugin("new"))
        assertEquals(PluginState.DISABLED, manager.getPlugin("old").pluginState)

        // None of it was persisted.
        assertFalse(Files.exists(disabledTxt()), "a refusal must not write disabled.txt")
        assertFalse(Files.exists(pluginsDir.resolve("enabled.txt")))

        manager.stopPlugins()
    }

    // ---- the real manager ---------------------------------------------------------------------

    @Test
    fun `levels 0 and 2 are refused in memory by the real manager, 1 starts`() {
        writeJar("old.jar", "old", null)
        writeJar("zero.jar", "zero", "0")
        writeJar("new.jar", "new", "2")
        writeJar("ok.jar", "ok", "1")

        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()
        manager.startPlugins()

        assertEquals(PluginState.STARTED, manager.getPlugin("ok").pluginState)

        for (id in listOf("old", "zero", "new")) {
            assertEquals(PluginState.DISABLED, manager.getPlugin(id).pluginState, id)
            assertEquals(PluginState.DISABLED, manager.startPlugin(id), id)
            assertFalse(manager.enablePlugin(id), id)
            assertEquals(PluginState.DISABLED, manager.getPlugin(id).pluginState, id)
        }

        assertEquals(Verdict.TOO_OLD, manager.incompatible["old"])
        assertEquals(Verdict.TOO_OLD, manager.incompatible["zero"])
        assertEquals(Verdict.TOO_NEW, manager.incompatible["new"])
        assertNull(manager.incompatible["ok"])

        // Nothing was persisted as disabled.
        assertFalse(Files.exists(disabledTxt()), "a refusal must not write disabled.txt")

        manager.stopPlugins()
    }

    @Test
    fun `a refused plugin is never instantiated, so its hooks do not run`() {
        writeJar("zero.jar", "zero", "0")

        val manager = PluginManager(listOf(pluginsDir))

        FixturePlugin.created.set(0)

        manager.loadPlugins()
        manager.startPlugins()

        // enablePlugin and disablePlugin of the manager read wrapper.plugin, which builds the instance and runs load().
        assertFalse(manager.enablePlugin("zero"))
        assertTrue(manager.disablePlugin("zero"))
        assertEquals(PluginState.DISABLED, manager.startPlugin("zero"))

        assertEquals(0, FixturePlugin.created.get())
        assertFalse(Files.exists(disabledTxt()))
    }

    @Test
    fun `a refused plugin starts after its jar is replaced with a compatible one`() {
        val jar = writeJar("market.jar", "market", "0", version = "1.0.0")
        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()
        manager.startPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
        assertEquals(Verdict.TOO_OLD, manager.incompatible["market"])
        assertFalse(Files.exists(disabledTxt()))

        // The install path: unload, put the new jar in place, load again.
        assertTrue(manager.unloadPlugin("market"))
        assertNull(manager.incompatible["market"], "the verdict is cleared on unload")

        Files.delete(jar)
        val replaced = writeJar("market.jar", "market", "1", version = "1.1.0")

        assertEquals("market", manager.loadPlugin(replaced))
        assertNull(manager.incompatible["market"])
        assertEquals(PluginState.STARTED, manager.startPlugin("market"))
        assertEquals("1.1.0", manager.getPlugin("market").descriptor.version)
        assertFalse(Files.exists(disabledTxt()))

        manager.stopPlugins()
    }

    @Test
    fun `a plugin that became too new after an update is refused the same way`() {
        val jar = writeJar("market.jar", "market", "1")
        val manager = PluginManager(listOf(pluginsDir))

        manager.loadPlugins()
        manager.startPlugins()

        assertEquals(PluginState.STARTED, manager.getPlugin("market").pluginState)

        assertTrue(manager.unloadPlugin("market"))

        Files.delete(jar)
        manager.loadPlugin(writeJar("market.jar", "market", "2"))

        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
        assertEquals(Verdict.TOO_NEW, manager.incompatible["market"])
        assertFalse(Files.exists(disabledTxt()))
    }

    // ---- themes -------------------------------------------------------------------------------

    private val gson = GsonBuilder().registerTypeAdapterFactory(StrictNotNullTypeAdapterFactory()).create()

    private fun themeJson(apiLevel: Int?) = """
        { "id": "blaze-theme", "title": "Blaze", "version": "v1.0.0", "author": "x", "panoVersion": "1.0.0",
          "screenshots": []${if (apiLevel != null) ", \"apiLevel\": $apiLevel" else ""} }
    """

    @Test
    fun `a theme manifest carries apiLevel and a missing one reads as 0`() {
        assertEquals(1, gson.fromJson(themeJson(1), ThemeManifest::class.java).apiLevel)
        assertEquals(0, gson.fromJson(themeJson(null), ThemeManifest::class.java).apiLevel)
    }

    private fun installed(id: String, apiLevel: Int) = InstalledTheme(
        id, id, null, "v1.0.0", "x", null, null, "1.0.0", emptyMap(), "hash", 1L, 1L, InstalledBy.USER, false, null, apiLevel
    )

    @Test
    fun `the install-time rewrite keeps apiLevel`() {
        val rewritten = gson.fromJson(installed("blaze-theme", 1).encode(), InstalledTheme::class.java)

        assertEquals(1, rewritten.apiLevel)
    }

    @Test
    fun `a theme outside the range is refused, the bundled one always passes`() {
        val list = listOf(installed("old-theme", 0), installed("ok-theme", 1), installed("new-theme", 2), installed(DEFAULT_THEME_ID, 0))

        assertEquals(Verdict.TOO_OLD, UIManager.themeVerdict("old-theme", list))
        assertEquals(Verdict.OK, UIManager.themeVerdict("ok-theme", list))
        assertEquals(Verdict.TOO_NEW, UIManager.themeVerdict("new-theme", list))
        assertEquals(Verdict.OK, UIManager.themeVerdict(DEFAULT_THEME_ID, list), "the fallback theme is never gated")
        assertEquals(Verdict.OK, UIManager.themeVerdict("unknown", list), "a theme without a manifest is not judged here")
    }

}
