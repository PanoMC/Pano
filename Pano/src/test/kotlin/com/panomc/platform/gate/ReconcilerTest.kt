package com.panomc.platform.gate

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.Main
import com.panomc.platform.PanoManifestPluginDescriptorFinder
import com.panomc.platform.PanoPluginDescriptor
import com.panomc.platform.PluginManager
import com.panomc.platform.SpringConfig
import com.panomc.platform.api.ExternalUrl
import com.panomc.platform.api.ExternalUrlProvider
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.db.model.Node
import com.panomc.platform.db.model.Server
import com.panomc.platform.node.NodeKind
import com.panomc.platform.route.api.panel.compatibility.CompatibilityPayload
import com.panomc.platform.server.ServerStatus
import com.panomc.platform.server.ServerType
import com.panomc.platform.update.CompatibilityStore
import com.panomc.platform.update.CompatibleHit
import com.panomc.platform.update.CompatibleQuery
import com.panomc.platform.update.DownloadedResource
import com.panomc.platform.update.PanoCompatibilityStore
import com.panomc.platform.update.QueriedResource
import com.panomc.platform.update.ReleaseApiLevel
import com.panomc.platform.update.ReleaseInfo
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PF-25: gate 0, the compatibility reconcile at boot (doc 04 section 7), against a fake store. The plugin cases use a
 * real [PluginManager] and fixture jars (a manifest and nothing else, the plugin class comes from the test
 * classpath); the fake installer does what the install code does to PF4J (stop, unload, replace the jar, load,
 * start), so "installed and started" is the real PF4J state.
 */
class ReconcilerTest {
    @TempDir
    lateinit var tempDir: Path

    class FixturePlugin : PanoPlugin()

    private lateinit var vertx: Vertx
    private val pluginsDir: Path get() = tempDir.resolve("plugins")
    private val themesDir: Path get() = tempDir.resolve("themes")
    private val downloads: File get() = tempDir.resolve("downloads").toFile()

    @BeforeEach
    fun setUp() {
        Files.createDirectories(pluginsDir)
        Files.createDirectories(themesDir)

        vertx = Vertx.vertx()

        SpringConfig.setDefaults(vertx, LoggerFactory.getLogger("reconciler-test"))
        Main.applicationContext = AnnotationConfigApplicationContext()
    }

    @AfterEach
    fun tearDown() {
        vertx.close().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun writeJar(dir: Path, file: String, id: String, apiLevel: Int?, version: String): Path {
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
            attributes[Attributes.Name("api-level")] = apiLevel.toString()
        }

        Files.createDirectories(dir)

        val path = dir.resolve(file)

        JarOutputStream(Files.newOutputStream(path), manifest).close()

        return path
    }

    private fun installedJar(id: String, apiLevel: Int?, version: String = "1.0.0") =
        writeJar(pluginsDir, "$id.jar", id, apiLevel, version)

    private fun writeThemeFolder(id: String, apiLevel: Int?, version: String = "v1.0.0") {
        val folder = themesDir.resolve(id)

        Files.createDirectories(folder)

        val manifest = JsonObject().put("id", id).put("title", id).put("version", version)

        apiLevel?.let { manifest.put("apiLevel", it) }

        Files.writeString(folder.resolve("manifest.json"), manifest.encode())
    }

    private fun themeZip(file: File, id: String, apiLevel: Int, version: String): File {
        file.parentFile.mkdirs()

        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JsonObject().put("id", id).put("version", version).put("apiLevel", apiLevel).encode().toByteArray())
            zip.closeEntry()
        }

        return file
    }

    private fun hit(id: String, version: String = "v1.1.0", type: ResourceType = ResourceType.PLUGIN) =
        CompatibleHit(id, type, "version-$id", version, apiLevel = 1)

    /** The store: answers [hits], and writes the file [fileFor] says into the target folder on download. */
    private inner class FakeStore(
        var hits: List<CompatibleHit> = emptyList(),
        var queryFailure: Exception? = null,
        var queryDelayMs: Long = 0,
        var downloadFailureFor: Set<String> = emptySet(),
        var fileFor: (CompatibleHit, File) -> File = { hit, folder ->
            if (hit.type == ResourceType.PLUGIN) {
                writeJar(folder.toPath(), "${hit.id}.jar", hit.id, 1, hit.version.removePrefix("v")).toFile()
            } else {
                themeZip(File(folder, "${hit.id}.zip"), hit.id, 1, hit.version)
            }
        }
    ) : CompatibilityStore {
        val queries = CopyOnWriteArrayList<CompatibleQuery>()
        val downloaded = CopyOnWriteArrayList<String>()

        override suspend fun newestCompatible(query: CompatibleQuery): List<CompatibleHit> {
            queries += query

            if (queryDelayMs > 0) {
                delay(queryDelayMs)
            }

            queryFailure?.let { throw it }

            return hits.filter { h -> query.resources.any { it.id == h.id } }
        }

        override suspend fun download(hit: CompatibleHit, targetFolder: File): DownloadedResource {
            downloaded += hit.id

            if (hit.id in downloadFailureFor) {
                throw IllegalStateException("HTTP 500")
            }

            return DownloadedResource(fileFor(hit, targetFolder), hit.hash)
        }
    }

    /** What the install code does to PF4J for a plugin; for a theme it replaces the folder. */
    private inner class FakeInstaller(
        private val manager: PluginManager,
        var failureAfterInstall: String? = null,
        var skip: Boolean = false
    ) : ResourceInstaller {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun install(file: File, type: ResourceType, hash: String?): String? {
            val inspected = PanoResourceCatalog(manager) { themesDir.toFile() }.inspect(file, type)!!

            calls += inspected.id

            if (skip) {
                return "not installed"
            }

            if (type == ResourceType.PLUGIN) {
                val path = manager.getPlugin(inspected.id).pluginPath

                manager.stopPlugin(inspected.id)
                manager.disablePlugin(inspected.id)
                manager.unloadPlugin(inspected.id)

                Files.copy(file.toPath(), path, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(file.toPath())

                manager.loadPlugin(path)
                manager.startPlugin(inspected.id)
            } else {
                val folder = themesDir.resolve(inspected.id)
                val zipManifest = java.util.zip.ZipFile(file).use { it.getInputStream(it.getEntry("manifest.json")).readBytes() }

                Files.createDirectories(folder)
                Files.write(folder.resolve("manifest.json"), zipManifest)
                file.delete()
            }

            return failureAfterInstall
        }
    }

    private class CountingNotifier : RefusalNotifier {
        val counts = CopyOnWriteArrayList<Int>()

        override suspend fun refused(count: Int) {
            counts += count
        }
    }

    private fun reconciler(
        manager: PluginManager,
        store: CompatibilityStore,
        installer: ResourceInstaller,
        notifier: RefusalNotifier = CountingNotifier(),
        queryBudgetMs: Long = 5_000,
        downloadBudgetMs: Long = 5_000
    ) = CompatibilityReconciler(
        PanoResourceCatalog(manager) { themesDir.toFile() },
        store,
        installer,
        notifier,
        { downloads },
        queryBudgetMs,
        downloadBudgetMs
    )

    private fun disabledTxt() = pluginsDir.resolve("disabled.txt")

    private fun pluginManager() = PluginManager(listOf(pluginsDir))

    // ---- plugins ------------------------------------------------------------------------------

    @Test
    fun `a hit is downloaded, installed and started between loadPlugins and startPlugins`() = runBlocking {
        installedJar("market", 0, "1.0.0")
        installedJar("blog", 1, "2.0.0")

        val manager = pluginManager()
        val store = FakeStore(hits = listOf(hit("market", "v1.1.0")))
        val installer = FakeInstaller(manager)
        val reconciler = reconciler(manager, store, installer)

        manager.loadPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)

        val report = reconciler.reconcile(atBoot = true)

        assertEquals(listOf("market"), report.installed.map { it.id })
        assertEquals(emptyList<ReconcileEntry>(), report.refused)
        assertEquals("1.1.0", report.installed.single().installedVersion)
        assertTrue(report.storeReachable == true)
        assertEquals(listOf("market"), installer.calls.toList(), "only the refused plugin is touched")

        // The query carries this Pano's range and only the refused resource.
        val query = store.queries.single()
        assertEquals(1, query.apiLevel)
        assertEquals(1, query.minApiLevel)
        assertEquals(listOf(QueriedResource("market", ResourceType.PLUGIN, "1.0.0")), query.resources)

        // The plugin is compatible and started; startPlugins then starts the other one and leaves it alone.
        assertEquals(PluginState.STARTED, manager.getPlugin("market").pluginState)
        assertEquals("1.1.0", manager.getPlugin("market").descriptor.version)

        manager.startPlugins()

        assertEquals(PluginState.STARTED, manager.getPlugin("market").pluginState)
        assertEquals(PluginState.STARTED, manager.getPlugin("blog").pluginState)
        assertTrue(manager.incompatible.isEmpty())
        assertFalse(Files.exists(disabledTxt()), "nothing was ever written as disabled")
        assertFalse(downloads.resolve("market.jar").exists(), "the downloaded file is not left behind")

        manager.stopPlugins()
    }

    @Test
    fun `a failure the install code reports after the plugin is running does not undo the success`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val reconciler = reconciler(manager, FakeStore(hits = listOf(hit("market"))), FakeInstaller(manager, failureAfterInstall = "NO_DATABASE"))

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertEquals(ReconcileOutcome.INSTALLED, report.entries.single().outcome)
        assertEquals(PluginState.STARTED, manager.getPlugin("market").pluginState)

        manager.stopPlugins()
    }

    @Test
    fun `an install that did nothing is a refusal with the reason, however the installer answered`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val reconciler = reconciler(manager, FakeStore(hits = listOf(hit("market"))), FakeInstaller(manager, skip = true))

        manager.loadPlugins()

        val entry = reconciler.reconcile(atBoot = true).entries.single()

        assertEquals(ReconcileOutcome.REFUSED, entry.outcome)
        assertTrue(entry.hasCompatibleUpdate)
        assertEquals("INSTALL_FAILED: not installed", entry.lastError)
        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
    }

    @Test
    fun `a miss leaves the plugin refused and reported, and nothing is persisted`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val notifier = CountingNotifier()
        val reconciler = reconciler(manager, FakeStore(hits = emptyList()), FakeInstaller(manager), notifier)

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)
        val entry = report.entries.single()

        assertEquals(ReconcileOutcome.REFUSED, entry.outcome)
        assertFalse(entry.hasCompatibleUpdate)
        assertNull(entry.lastError, "the store answered; it simply has nothing")
        assertTrue(report.storeReachable == true)

        manager.startPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
        assertEquals(Verdict.TOO_OLD, manager.incompatible["market"])
        assertFalse(Files.exists(disabledTxt()))

        // One notification for the run, however often it is asked for.
        reconciler.announceRefusals()
        reconciler.announceRefusals()

        assertEquals(listOf(1), notifier.counts.toList())
        assertTrue(reconciler.report.notified)

        // The panel sees it with the verdict.
        val rows = CompatibilityPayload.resources(reconciler.installed(), reconciler.report)

        assertEquals(1, rows.size)
        assertEquals("market", rows.single()["id"])
        assertEquals("TOO_OLD", rows.single()["verdict"])
        assertEquals(false, rows.single()["hasCompatibleUpdate"])
    }

    @Test
    fun `a store that does not answer within the budget leaves the plugin refused and says so`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val store = FakeStore(hits = listOf(hit("market")), queryDelayMs = 10_000)
        val notifier = CountingNotifier()
        val reconciler = reconciler(manager, store, FakeInstaller(manager), notifier, queryBudgetMs = 150)

        manager.loadPlugins()

        val started = System.nanoTime()
        val report = reconciler.reconcile(atBoot = true)
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(tookMs < 5_000, "the budget bounds the boot, took $tookMs ms")
        assertEquals("STORE_TIMEOUT", report.entries.single().lastError)
        assertEquals(false, report.storeReachable)
        assertTrue(store.downloaded.isEmpty())

        manager.startPlugins()

        assertEquals(PluginState.DISABLED, manager.getPlugin("market").pluginState)
        assertFalse(Files.exists(disabledTxt()))

        reconciler.announceRefusals()

        assertEquals(listOf(1), notifier.counts.toList())
    }

    @Test
    fun `a store without the compatibility query is told apart from an unreachable one`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val reconciler = reconciler(
            manager,
            FakeStore(queryFailure = com.panomc.platform.update.CompatibilityQueryUnsupported(404)),
            FakeInstaller(manager)
        )

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertEquals("STORE_QUERY_UNSUPPORTED: HTTP 404", report.entries.single().lastError)
        assertEquals(ReconcileOutcome.REFUSED, report.entries.single().outcome)
        assertEquals(false, report.storeReachable)
    }

    @Test
    fun `only a missing query is unsupported, other statuses stay outages`() {
        assertTrue(com.panomc.platform.update.queryFailure(404) is com.panomc.platform.update.CompatibilityQueryUnsupported)
        assertTrue(com.panomc.platform.update.queryFailure(501) is com.panomc.platform.update.CompatibilityQueryUnsupported)
        assertFalse(com.panomc.platform.update.queryFailure(500) is com.panomc.platform.update.CompatibilityQueryUnsupported)
        assertEquals("HTTP 503", com.panomc.platform.update.queryFailure(503).message)
    }

    @Test
    fun `an unread notice for the same resources is not announced again`() {
        val unread = listOf(JsonObject().put("count", 2).put("resources", JsonArray().add("b").add("a")))

        assertTrue(PanoRefusalNotifier.alreadyAnnounced(unread, 2, listOf("a", "b")))
        assertFalse(PanoRefusalNotifier.alreadyAnnounced(unread, 3, listOf("a", "b", "c")))
        assertFalse(PanoRefusalNotifier.alreadyAnnounced(unread, 2, listOf("a", "c")))
        assertFalse(PanoRefusalNotifier.alreadyAnnounced(emptyList(), 2, listOf("a", "b")))
        // A row from before the resources were recorded does not match.
        assertFalse(PanoRefusalNotifier.alreadyAnnounced(listOf(JsonObject().put("count", 2)), 2, listOf("a", "b")))
    }

    @Test
    fun `a store that fails is told apart from one that has nothing`() = runBlocking {
        installedJar("market", 0)

        val manager = pluginManager()
        val reconciler = reconciler(
            manager,
            FakeStore(queryFailure = java.net.ConnectException("connection refused")),
            FakeInstaller(manager)
        )

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertEquals("STORE_UNREACHABLE: connection refused", report.entries.single().lastError)
        assertEquals(false, report.storeReachable)
    }

    @Test
    fun `one failing download does not stop the others`() = runBlocking {
        installedJar("alpha", 0)
        installedJar("beta", 0)

        val manager = pluginManager()
        val store = FakeStore(hits = listOf(hit("alpha"), hit("beta")), downloadFailureFor = setOf("alpha"))
        val reconciler = reconciler(manager, store, FakeInstaller(manager))

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertEquals(listOf("beta"), report.installed.map { it.id })
        assertEquals(listOf("alpha"), report.refused.map { it.id })
        assertEquals("DOWNLOAD_FAILED: HTTP 500", report.refused.single().lastError)
        assertTrue(report.refused.single().hasCompatibleUpdate)
        assertEquals(PluginState.STARTED, manager.getPlugin("beta").pluginState)
        assertEquals(PluginState.DISABLED, manager.getPlugin("alpha").pluginState)

        manager.stopPlugins()
    }

    @Test
    fun `a file that is not what the store promised is not installed`() = runBlocking {
        installedJar("market", 0)
        installedJar("blog", 0)

        val manager = pluginManager()
        val store = FakeStore(
            hits = listOf(hit("market"), hit("blog")),
            fileFor = { hit, folder ->
                when (hit.id) {
                    // Another plugin under the market's name.
                    "market" -> writeJar(folder.toPath(), "x.jar", "something-else", 1, "1.1.0").toFile()
                    // The right plugin, still on level 0.
                    else -> writeJar(folder.toPath(), "y.jar", "blog", 0, "1.1.0").toFile()
                }
            }
        )
        val installer = FakeInstaller(manager)
        val reconciler = reconciler(manager, store, installer)

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertTrue(installer.calls.isEmpty(), "nothing was handed to the install code")
        assertTrue(report.refused.all { it.lastError!!.startsWith("DOWNLOAD_MISMATCH") }, report.refused.toString())
        assertEquals(2, report.refused.size)
    }

    @Test
    fun `a boot with nothing refused does not ask the store`() = runBlocking {
        installedJar("market", 1)
        writeThemeFolder("blaze-theme", 1)

        val manager = pluginManager()
        val store = FakeStore()
        val reconciler = reconciler(manager, store, FakeInstaller(manager))

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertTrue(report.entries.isEmpty())
        assertTrue(store.queries.isEmpty())
        assertNull(report.storeReachable)

        reconciler.announceRefusals()
    }

    @Test
    fun `a plugin the admin disabled is left alone`() = runBlocking {
        installedJar("market", 0)
        Files.writeString(disabledTxt(), "# off\nmarket\n")

        val manager = pluginManager()
        val store = FakeStore(hits = listOf(hit("market")))
        val reconciler = reconciler(manager, store, FakeInstaller(manager))

        manager.loadPlugins()

        val report = reconciler.reconcile(atBoot = true)

        assertTrue(report.entries.isEmpty())
        assertTrue(store.queries.isEmpty())
        assertTrue(reconciler.installed().single { it.id == "market" }.disabledByAdmin)
        assertTrue(CompatibilityPayload.resources(reconciler.installed(), report).isEmpty())
    }

    @Test
    fun `a plugin too new for this Pano is replaced by an older compatible version`() = runBlocking {
        installedJar("market", 2, "3.0.0")

        val manager = pluginManager()
        val reconciler = reconciler(manager, FakeStore(hits = listOf(hit("market", "v1.5.0"))), FakeInstaller(manager))

        manager.loadPlugins()

        assertEquals(Verdict.TOO_NEW, manager.incompatible["market"])

        val report = reconciler.reconcile(atBoot = true)

        assertEquals("1.5.0", report.installed.single().installedVersion)
        assertEquals(PluginState.STARTED, manager.getPlugin("market").pluginState)

        manager.stopPlugins()
    }

    // ---- themes -------------------------------------------------------------------------------

    @Test
    fun `a theme is replaced at boot and needs a restart when replaced later`() = runBlocking {
        writeThemeFolder("blaze-theme", 0, "v1.0.0")
        writeThemeFolder("frost-theme", 0, "v1.0.0")
        writeThemeFolder("ok-theme", 1)

        val manager = pluginManager()
        val store = FakeStore(hits = listOf(hit("blaze-theme", "v2.0.0", ResourceType.THEME), hit("frost-theme", "v2.0.0", ResourceType.THEME)))
        val installer = FakeInstaller(manager)

        val atBoot = reconciler(manager, store, installer).reconcile(atBoot = true)

        assertEquals(listOf("blaze-theme", "frost-theme"), atBoot.installed.map { it.id })
        assertTrue(atBoot.installed.none { it.restartRequired })
        assertEquals(
            listOf(ResourceType.THEME),
            store.queries.single().resources.map { it.type }.distinct()
        )

        // The catalog now reads the new levels from the replaced manifests.
        assertEquals(setOf(Verdict.OK), PanoResourceCatalog(manager) { themesDir.toFile() }.installed().map { it.verdict }.toSet())

        writeThemeFolder("blaze-theme", 0, "v1.0.0")

        val later = reconciler(manager, store, installer).reconcile(atBoot = false)

        assertTrue(later.installed.single().restartRequired)
    }

    // ---- the catalog --------------------------------------------------------------------------

    @Test
    fun `the catalog lists plugins and themes with their levels, and never judges the bundled theme`() {
        installedJar("market", 0)
        installedJar("blog", 1)
        writeThemeFolder("blaze-theme", null)
        writeThemeFolder("vanilla-theme", 0)
        Files.createDirectories(themesDir.resolve("empty-folder"))

        val manager = pluginManager()

        manager.loadPlugins()

        val catalog = PanoResourceCatalog(manager) { themesDir.toFile() }
        val byId = catalog.installed().associateBy { it.id }

        assertEquals(setOf("market", "blog", "blaze-theme"), byId.keys)
        assertEquals(Verdict.TOO_OLD, byId.getValue("market").verdict)
        assertEquals(Verdict.OK, byId.getValue("blog").verdict)
        assertEquals(Verdict.TOO_OLD, byId.getValue("blaze-theme").verdict)
        assertEquals(0, byId.getValue("blaze-theme").apiLevel)
        assertFalse(byId.getValue("market").running)
        assertEquals(ResourceType.THEME, byId.getValue("blaze-theme").type)

        manager.stopPlugins()
    }

    @Test
    fun `the catalog reads a downloaded jar and a theme zip`() {
        val jar = writeJar(tempDir.resolve("in"), "a.jar", "market", 1, "1.2.3").toFile()
        val zip = themeZip(tempDir.resolve("in/t.zip").toFile(), "blaze-theme", 1, "v2.0.0")
        val catalog = PanoResourceCatalog(pluginManager()) { themesDir.toFile() }

        assertEquals(InspectedFile("market", "1.2.3", 1), catalog.inspect(jar, ResourceType.PLUGIN))
        assertEquals(InspectedFile("blaze-theme", "v2.0.0", 1), catalog.inspect(zip, ResourceType.THEME))
        assertNull(catalog.inspect(zip, ResourceType.PLUGIN))
        assertNull(catalog.inspect(jar, ResourceType.THEME))
        assertNull(catalog.inspect(File(tempDir.toFile(), "missing.jar"), ResourceType.PLUGIN))
    }

    // ---- the panel body -----------------------------------------------------------------------

    private fun server(id: Long, protocol: Int, granted: Boolean = true) = Server(
        id = id, name = "Survival", motd = "", host = "h", port = 25565, playerCount = 0, maxPlayerCount = 0,
        type = ServerType.PAPER, version = "1.21", favicon = "", permissionGranted = granted, status = ServerStatus.OFFLINE,
        startTime = 0, aesKey = "k", protocolVersion = protocol, pluginVersion = "alpha.65"
    )

    private fun node(id: Long, protocol: Int, approved: Boolean = true, agent: Boolean = false) = Node(
        id = id, uuid = "u$id", name = "node-$id", kind = NodeKind.REMOTE, approved = approved, version = "1.0.0",
        protocolVersion = protocol, aesKey = "k", agent = agent
    )

    @Test
    fun `agents are the servers and nodes below the minimum protocol`() {
        val rows = CompatibilityPayload.agents(
            listOf(server(1, 2), server(2, 3), server(3, 1, granted = false)),
            listOf(node(1, 5), node(2, 6), node(3, 4, approved = false), node(4, 5, agent = true))
        )

        assertEquals(listOf("SERVER", "NODE", "AGENT"), rows.map { it["type"] })

        val serverRow = rows[0]

        assertEquals(1L, serverRow["id"])
        assertEquals("Survival", serverRow["name"])
        assertEquals(2, serverRow["protocolVersion"])
        assertEquals("alpha.65", serverRow["pluginVersion"])
        assertEquals("MANUAL_JAR", serverRow["action"])
        assertEquals("/api/v1/panel/servers/1/pano-plugin/jar", serverRow["downloadPath"])
        assertEquals("/api/v1/node/pano-node.jar", rows[1]["downloadPath"])
        assertEquals("/api/v1/node/pano-agent.jar", rows[2]["downloadPath"])
    }

    @Test
    fun `the local node is Pano's own and is not asked for a jar by hand unless its jar is pinned`() {
        val local = node(7, 5).copy(kind = NodeKind.LOCAL)
        val nodes = listOf(local, node(8, 5))

        assertEquals(listOf(8L), CompatibilityPayload.agents(emptyList(), nodes).map { it["id"] })
        assertEquals(listOf(7L, 8L), CompatibilityPayload.agents(emptyList(), nodes, localNodeManaged = false).map { it["id"] })
    }

    @Test
    fun `external urls come from the plugins' providers and a throwing provider is skipped`() {
        val good = object : ExternalUrlProvider {
            override fun urls() = listOf(ExternalUrl("Microsoft redirect URL", "https://example.com/callback"))
        }
        val bad = object : ExternalUrlProvider {
            override fun urls(): List<ExternalUrl> = throw IllegalStateException("boom")
        }

        val rows = CompatibilityPayload.externalUrls(
            mapOf("pano-plugin-premium-login" to listOf(good, bad), "pano-plugin-empty" to emptyList())
        )

        assertEquals(1, rows.size)
        assertEquals("pano-plugin-premium-login", rows.single()["pluginId"])
        assertEquals("Microsoft redirect URL", rows.single()["label"])
        assertEquals("https://example.com/callback", rows.single()["url"])
    }

    @Test
    fun `the compatibility body has the documented shape`() = runBlocking {
        installedJar("market", 0, "1.0.0")

        val manager = pluginManager()
        val reconciler = reconciler(manager, FakeStore(queryFailure = java.io.IOException("offline")), FakeInstaller(manager))

        manager.loadPlugins()
        reconciler.reconcile(atBoot = true)

        val body = CompatibilityPayload.build(reconciler, listOf(server(3, 2)), emptyList(), emptyMap())

        assertEquals(setOf("apiLevel", "resources", "agents", "externalUrls", "reconcile"), body.keys)
        assertEquals(mapOf("min" to 1, "current" to 1), body["apiLevel"])

        @Suppress("UNCHECKED_CAST")
        val resource = (body["resources"] as List<Map<String, Any?>>).single()

        assertEquals(
            setOf("id", "type", "title", "version", "apiLevel", "verdict", "hasCompatibleUpdate", "lastError", "heldBy"),
            resource.keys
        )
        assertEquals("PLUGIN", resource["type"])
        assertEquals(null, resource["heldBy"])
        assertEquals("STORE_UNREACHABLE: offline", resource["lastError"])
        assertEquals(1, (body["agents"] as List<*>).size)
        assertEquals(false, (body["reconcile"] as Map<*, *>)["running"])
    }

    // ---- release info -------------------------------------------------------------------------

    @Test
    fun `a release carries its API levels through JSON, and a level file is parsed leniently`() {
        val release = ReleaseInfo.fromJson(
            JsonObject().put("tag", "v1.0.0-alpha.600").put("apiLevel", 1).put("minApiLevel", 1)
        )!!

        assertEquals(1, release.apiLevel)
        assertEquals(1, release.minApiLevel)
        assertEquals(1, ReleaseInfo.fromJson(release.toJson())!!.apiLevel)

        val old = ReleaseInfo.fromJson(JsonObject().put("tag", "v1.0.0-alpha.500"))!!

        assertNull(old.apiLevel)
        assertNull(old.minApiLevel)
        assertNull(ReleaseInfo.fromJson(JsonObject().put("tag", "v1.0.0").put("apiLevel", "one"))!!.apiLevel)
        assertNull(ReleaseInfo.fromJson(JsonObject().put("tag", "v1.0.0").put("apiLevel", -3))!!.apiLevel)

        assertEquals(ReleaseApiLevel(2, 1), ReleaseApiLevel.parse("""{"apiLevel":2,"minApiLevel":1}"""))
        assertEquals(ReleaseApiLevel(3, 2), ReleaseApiLevel.parse("""{"current":3,"min":2}"""))
        assertEquals(ReleaseApiLevel(1, null), ReleaseApiLevel.parse("""{"apiLevel":1}"""))
        assertNull(ReleaseApiLevel.parse("""{"other":1}"""))
        assertNull(ReleaseApiLevel.parse("not json"))
        assertNull(ReleaseApiLevel.parse(null))

        assertEquals(1, old.withLevels(ReleaseApiLevel(1, 1)).apiLevel)
        assertNull(old.withLevels(null).apiLevel)
    }

    // ---- the real store client, against a fake Pano API ---------------------------------------

    @Nested
    inner class HttpStore {
        private lateinit var server: HttpServer
        private lateinit var web: WebClient
        private var port = 0

        @Volatile
        private var linkedStatus = 200

        @Volatile
        private var anonymousStatus = 200

        private val requests = CopyOnWriteArrayList<String>()
        private val authSeen = CopyOnWriteArrayList<String?>()
        private val queryBodies = CopyOnWriteArrayList<JsonObject>()

        @BeforeEach
        fun start(): Unit = runBlocking {
            web = WebClient.create(vertx)

            val router = Router.router(vertx)

            router.get("/platform/api/store/resources/versions").handler { ctx ->
                requests += "linked ${ctx.request().query()}"
                authSeen += ctx.request().getHeader("Authorization")

                if (linkedStatus != 200) {
                    ctx.response().setStatusCode(linkedStatus).end()
                    return@handler
                }

                ctx.response().end(
                    JsonObject().put("result", "ok").put(
                        "data",
                        JsonArray()
                            .add(JsonObject().put("id", "market").put("type", "PLUGIN").put("version", "v1.1.0").put("versionId", "vid-market").put("hash", "abc").put("apiLevel", 1))
                            // A level outside the asked range and a resource nobody asked for are ignored.
                            .add(JsonObject().put("id", "blog").put("type", "PLUGIN").put("version", "v9.0.0").put("versionId", "vid-blog").put("apiLevel", 7))
                            .add(JsonObject().put("id", "stranger").put("type", "PLUGIN").put("version", "v1.0.0").put("versionId", "vid-s").put("apiLevel", 1))
                    ).encode()
                )
            }

            router.post("/platform/api/store/compatible-versions").handler(io.vertx.ext.web.handler.BodyHandler.create()).handler { ctx ->
                requests += "anonymous"
                authSeen += ctx.request().getHeader("Authorization")
                queryBodies += ctx.body().asJsonObject()

                if (anonymousStatus != 200) {
                    ctx.response().setStatusCode(anonymousStatus).end()
                    return@handler
                }

                ctx.response().end(
                    JsonObject().put("result", "ok").put(
                        "data",
                        JsonObject().put(
                            "items",
                            JsonArray().add(JsonObject().put("id", "blog").put("versionId", "vid-blog").put("version", "v2.0.0").put("apiLevel", 1))
                        )
                    ).encode()
                )
            }

            router.get("/platform/api/store/versions/:vid/file").handler { ctx ->
                requests += "download-linked ${ctx.pathParam("vid")}"
                authSeen += ctx.request().getHeader("Authorization")
                ctx.response().setStatusCode(302).putHeader("Location", "http://127.0.0.1:$port/storage/${ctx.pathParam("vid")}").end()
            }

            router.get("/storage/:vid").handler { ctx ->
                requests += "storage ${ctx.pathParam("vid")}"
                authSeen += ctx.request().getHeader("Authorization")
                ctx.response().end("jar-bytes-${ctx.pathParam("vid")}")
            }

            router.get("/resources/:id/versions/:vid/file").handler { ctx ->
                requests += "download-public ${ctx.pathParam("id")} ${ctx.pathParam("vid")}"
                authSeen += ctx.request().getHeader("Authorization")

                if (ctx.pathParam("id") == "gone") {
                    ctx.response().setStatusCode(404).end()
                    return@handler
                }

                ctx.response().end("public-bytes")
            }

            server = vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").coAwait()
            port = server.actualPort()
        }

        @AfterEach
        fun stop(): Unit = runBlocking {
            server.close().coAwait()
        }

        private fun store(token: String?) = PanoCompatibilityStore(vertx, web, { "http://127.0.0.1:$port/" }, { token })

        private val query = CompatibleQuery(
            1, 1,
            listOf(
                QueriedResource("market", ResourceType.PLUGIN, "1.0.0"),
                QueriedResource("blog", ResourceType.PLUGIN, "1.0.0")
            )
        )

        @Test
        fun `linked answers first, the anonymous query fills in what it did not know`() = runBlocking {
            val hits = store("secret").newestCompatible(query)

            assertEquals(listOf("market", "blog"), hits.map { it.id })
            assertTrue(hits[0].linked)
            assertEquals("abc", hits[0].hash)
            assertEquals("vid-market", hits[0].versionId)
            assertFalse(hits[1].linked)
            assertNull(hits[1].hash)

            assertTrue(requests[0].startsWith("linked ") && requests[0].contains("apiLevel=1") && requests[0].contains("minApiLevel=1"), requests[0])
            assertEquals("Bearer secret", authSeen[0])

            // Only what the linked answer lacked is asked anonymously, and without the token.
            assertEquals(listOf("blog"), queryBodies.single().getJsonArray("resources").map { (it as JsonObject).getString("id") })
            assertEquals(1, queryBodies.single().getInteger("apiLevel"))
            assertEquals(1, queryBodies.single().getInteger("minApiLevel"))
            assertNull(authSeen[1])
        }

        @Test
        fun `not linked, only the anonymous query is asked`() = runBlocking {
            val hits = store(null).newestCompatible(query)

            assertEquals(listOf("blog"), hits.map { it.id })
            assertEquals(listOf("anonymous"), requests.toList())
            assertEquals(2, queryBodies.single().getJsonArray("resources").size())
        }

        @Test
        fun `a failing linked query falls back to the anonymous one`() = runBlocking {
            linkedStatus = 500

            val hits = store("secret").newestCompatible(query)

            assertEquals(listOf("blog"), hits.map { it.id })
        }

        @Test
        fun `when nothing can be asked the store is unreachable`() {
            anonymousStatus = 503

            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { store(null).newestCompatible(query) }
            }

            assertEquals("HTTP 503", failure.message)
        }

        @Test
        fun `a linked download follows the redirect without the token`() = runBlocking {
            val hit = CompatibleHit("market", ResourceType.PLUGIN, "vid-market", "v1.1.0", 1, "abc", linked = true)

            val downloaded = store("secret").download(hit, downloads)

            assertEquals("jar-bytes-vid-market", downloaded.file.readText())
            assertTrue(downloaded.file.name.endsWith(".jar"))
            assertEquals("abc", downloaded.hash)
            assertEquals("Bearer secret", authSeen[requests.indexOf("download-linked vid-market")])
            assertNull(authSeen[requests.indexOf("storage vid-market")], "the file host never sees the token")
            assertTrue(downloads.listFiles()!!.none { it.name.endsWith(".part") })
        }

        @Test
        fun `an anonymous download uses the public version file route`() = runBlocking {
            val hit = CompatibleHit("blog", ResourceType.THEME, "vid-blog", "v2.0.0", 1)

            val downloaded = store("secret").download(hit, downloads)

            assertEquals("public-bytes", downloaded.file.readText())
            assertTrue(downloaded.file.name.endsWith(".zip"))
            assertNull(authSeen[requests.indexOf("download-public blog vid-blog")])
        }

        @Test
        fun `a failed download leaves no file and an unsafe id is refused before any request`() {
            val missing = CompatibleHit("market", ResourceType.PLUGIN, "no/such", "v1", 1)

            assertThrows(IllegalArgumentException::class.java) { runBlocking { store(null).download(missing, downloads) } }
            assertTrue(requests.isEmpty())

            val gone = CompatibleHit("gone", ResourceType.PLUGIN, "vid-gone", "v1", 1)
            val failure = assertThrows(IllegalStateException::class.java) { runBlocking { store(null).download(gone, downloads) } }

            assertEquals("HTTP 404", failure.message)
            assertTrue(downloads.listFiles().orEmpty().isEmpty(), "no partial file is left")
        }
    }
}
