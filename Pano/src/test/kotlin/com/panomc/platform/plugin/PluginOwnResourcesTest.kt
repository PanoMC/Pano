package com.panomc.platform.plugin

import com.panomc.platform.PanoPluginWrapper
import com.panomc.platform.PluginManager
import com.panomc.platform.PluginUiManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import com.panomc.platform.util.FileResourceUtil.getResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.DefaultPluginDescriptor
import org.pf4j.DefaultPluginManager
import org.pf4j.PluginClassLoader
import org.pf4j.PluginDependency
import org.pf4j.PluginDescriptor
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** P-16.1: fixture plugins A <- B, B without resources: nothing of A may leak into B. */
class PluginOwnResourcesTest {
    @TempDir
    lateinit var tempDir: Path

    private val loaders = mutableMapOf<String, PluginClassLoader>()
    private lateinit var fixtureManager: DefaultPluginManager
    private lateinit var panoManager: PluginManager

    private class TestPlugin : PanoPlugin()

    private fun descriptor(id: String, dependsOn: String? = null): PluginDescriptor =
        DefaultPluginDescriptor(id, "", "", "1.0.0", "", "", "").also {
            if (dependsOn != null) it.addDependency(PluginDependency(dependsOn))
        }

    private fun jar(name: String, entries: Map<String, ByteArray>): File {
        val file = tempDir.resolve("$name.jar").toFile()

        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        return file
    }

    private fun classLoader(id: String, jar: File, dependsOn: String? = null): PluginClassLoader {
        val loader = PluginClassLoader(fixtureManager, descriptor(id, dependsOn), javaClass.classLoader)
        loader.addFile(jar)
        loaders[id] = loader
        return loader
    }

    private fun wrapper(id: String, loader: ClassLoader, dependsOn: String? = null) =
        PanoPluginWrapper(panoManager, descriptor(id, dependsOn), tempDir.resolve("$id.jar"), loader)

    @BeforeEach
    fun setUp() {
        fixtureManager = object : DefaultPluginManager(tempDir.resolve("plugins")) {
            override fun getPluginClassLoader(pluginId: String): ClassLoader? = loaders[pluginId]
        }
        panoManager = PluginManager(listOf(tempDir.resolve("plugins")))

        val aJar = jar(
            "a",
            mapOf(
                "config.conf" to "logo-file = \"a.png\"\n".toByteArray(),
                "locales/" to ByteArray(0),
                "locales/en-US.json" to "{\"a\":\"A\"}".toByteArray(),
                "locales/tr.json" to "{\"a\":\"A-tr\"}".toByteArray(),
                "plugin-ui.zip" to "a-ui".toByteArray(),
                "a.png" to byteArrayOf(1, 2, 3)
            )
        )
        val bJar = jar("b", mapOf("b-only.txt" to "b".toByteArray()))

        classLoader("a", aJar)
        classLoader("b", bJar, dependsOn = "a")
    }

    @AfterEach
    fun tearDown() {
        loaders.values.forEach { it.close() }
    }

    @Test
    fun `control - the platform getResource would leak A into B`() {
        // Documents the defect: the stock lookup walks Plugin -> Dependencies.
        assertNotNull(loaders.getValue("b").getResourceAsStream("config.conf"))
    }

    @Test
    fun `plugin A keeps its own resources`() {
        val a = wrapper("a", loaders.getValue("a"))

        assertEquals("a.png", a.config!!.getString("logo-file"))
        assertEquals(setOf("en-US", "tr"), a.pluginLocales.keys)
        assertEquals("A", a.pluginLocales.getValue("en-US").getString("a"))
        assertNotNull(a.getResource("a.png"))
        assertNotNull(a.getResource("plugin-ui.zip"))
    }

    @Test
    fun `plugin B without resources has no config, no locales, no logo and no UI zip`() {
        val b = wrapper("b", loaders.getValue("b"), dependsOn = "a")

        assertNull(b.config)
        assertTrue(b.pluginLocales.isEmpty())
        assertNull(b.getResource("a.png"))
        assertNull(b.getResource("plugin-ui.zip"))
        assertNull(loaders.getValue("b").getOwnResourceStream("config.conf"))
    }

    @Test
    fun `plugin B still sees its own resource`() {
        assertEquals("b", loaders.getValue("b").getOwnResourceStream("b-only.txt")!!.use { String(it.readBytes()) })
    }

    @Test
    fun `a plain class loader keeps the normal lookup`() {
        assertNotNull(javaClass.classLoader.getOwnResourceStream("com/panomc/platform/plugin/PluginOwnResourcesTest.class"))
    }

    @Test
    fun `no UI is registered for B`() {
        val manager = PluginUiManager()
        val a = TestPlugin().also { it.pluginId = "a" }
        val b = TestPlugin().also { it.pluginId = "b" }

        manager.calculatePluginUiHash(a, loaders.getValue("a"), isDevelopment = false)
        manager.calculatePluginUiHash(b, loaders.getValue("b"), isDevelopment = false)

        assertNotNull(manager.getRegisteredPlugin(a))
        assertNull(manager.getRegisteredPlugin(b))
    }

    @Test
    fun `dev-build hash only with a UI source dir`() {
        val manager = PluginUiManager()
        val b = TestPlugin().also { it.pluginId = "b" }

        manager.calculatePluginUiHash(b, loaders.getValue("b"), isDevelopment = true, hasUiSourceDir = { false })
        assertNull(manager.getRegisteredPlugin(b))

        manager.calculatePluginUiHash(b, loaders.getValue("b"), isDevelopment = true, hasUiSourceDir = { it == "b" })
        assertEquals("dev-build", manager.getRegisteredPlugin(b))

        val c = TestPlugin().also { it.pluginId = "c" }
        manager.calculatePluginUiHash(c, loaders.getValue("b"), isDevelopment = false, hasUiSourceDir = { true })
        assertNull(manager.getRegisteredPlugin(c))
    }
}
