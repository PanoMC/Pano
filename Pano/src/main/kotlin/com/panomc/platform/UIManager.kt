package com.panomc.platform

import com.google.gson.GsonBuilder
import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Route
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.OperatingSystem
import com.panomc.platform.util.adapter.StrictNotNullTypeAdapterFactory
import com.panomc.platform.util.annotation.StrictValidation
import io.vertx.core.http.HttpClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.proxy.handler.ProxyHandler
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.ProxyOptions
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.*
import java.net.ServerSocket
import java.net.URL
import java.nio.file.*
import java.util.*
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.system.exitProcess

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UIManager(
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val httpClient: HttpClient,
    private val authProvider: AuthProvider
) {
    private val librariesFolderPath = System.getProperty("pano.librariesFolder", "libraries")
    private val setupUIFolderPath = System.getProperty("pano.setupUIFolder", "setup-ui")
    private val panelUIFolderPath = System.getProperty("pano.panelUIFolder", "panel-ui")
    private val defaultThemeFolderPath = THEMES_FOLDER_PATH + File.separator + DEFAULT_THEME_ID

    val manifestFileName = "manifest.json"

    private val themesFolder = File(THEMES_FOLDER_PATH)
    private val librariesFolder = File(librariesFolderPath)
    private val setupUIFolder = File(setupUIFolderPath)
    private val panelUIFolder = File(panelUIFolderPath)
    private val defaultThemeFolder = File(defaultThemeFolderPath)

    private val githubUrl = "https://github.com"

    private val bunVersion = "bun-v1.2.19"
    private val bunZipFileName by lazy {
        "bun-${Main.OPERATING_SYSTEM.name.lowercase()}-${Main.ARCHITECTURE.name.lowercase()}"
    }
    private val bunFileName by lazy {
        val suffix = if (Main.OPERATING_SYSTEM == OperatingSystem.WINDOWS) ".exe" else ""

        bunVersion + suffix
    }
    private val bunFilePath by lazy {
        librariesFolder.absolutePath + File.separator + bunFileName
    }

    private val startedUIList = mutableListOf<LoadedUI>()
    private var _activatedUIList = mutableMapOf<Route.Type, ActivatedUI>()

    // to make it read-only to public
    val activatedUIList: Map<Route.Type, ActivatedUI>
        get() = _activatedUIList

    private var _installedThemeList = mutableListOf<InstalledTheme>()

    // to make it read-only to public
    val installedThemeList: List<InstalledTheme>
        get() = _installedThemeList

    var activeTheme = ""
        private set

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private var muslRequired = false

    fun parseThemeManifest(manifestFile: File): ThemeManifest {
        return gson.fromJson(manifestFile.readText(), ThemeManifest::class.java)
    }

    fun getThemeFile(id: String, fileName: String): File? {
        val themeFolder = File(THEMES_FOLDER_PATH, id)

        if (!themeFolder.exists()) {
            return null
        }

        val file = File(themeFolder, fileName)

        if (!file.exists()) {
            return null
        }

        return file
    }

    fun getThemeFileAsStream(id: String, fileName: String): FileInputStream? = getThemeFile(id, fileName)?.inputStream()

    private fun parseInstalledTheme(manifestFile: File): InstalledTheme {
        return gson.fromJson(manifestFile.readText(), InstalledTheme::class.java)
    }

    private fun tryRun(path: String): Boolean {
        logger.info("Checking is downloaded Bun compatible with the system...")
        return try {
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (output.contains("' not found (required by bun)")) {
                muslRequired = true
            }

            output.contains("bun") || process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteOldBunRuntimes() {
        // Delete existing Bun files
        librariesFolder.listFiles { file ->
            file.name.startsWith("bun")
        }?.forEach { it.delete() }
    }

    //    Example: https://github.com/oven-sh/bun/releases/download/bun-v1.2.0/bun-darwin-x64-baseline-profile.zip
    private fun downloadBunRuntime(bunZipFileName: String) {
        deleteOldBunRuntimes()

        val fullUrl = "$githubUrl/oven-sh/bun/releases/download/$bunVersion/$bunZipFileName.zip"

        try {
            // Download the zip file
            val zipFile = File(librariesFolder, "$bunZipFileName.zip")

            URL(fullUrl).openStream().use { input ->
                Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            logger.info("Download complete.")
            logger.info("Extracting...")

            // Extract the zip file
            ZipFile(zipFile).use { zip ->
                val osSpecificBinaryName = when (Main.OPERATING_SYSTEM) {
                    OperatingSystem.WINDOWS -> "bun.exe"
                    else -> "bun"
                }

                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()

                    if (entry.name.endsWith(osSpecificBinaryName)) {
                        val targetFile = File(librariesFolder, bunFileName)

                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }

                        targetFile.setExecutable(true)

                        break
                    }
                }
            }

            // Clean up the zip file
            zipFile.delete()

            logger.info("Done.")
        } catch (e: Exception) {
            logger.error("Couldn't download Bun runtime: {}", e.message)
            exitProcess(1)
        }

        if (!tryRun(bunFilePath)) {
            val nextBunFileZipName = if (muslRequired && !bunZipFileName.contains("-musl")) {
                "${this.bunZipFileName}-musl"
            } else if (bunZipFileName.endsWith("-baseline") || bunZipFileName.endsWith("-musl")) {
                "$bunZipFileName-profile"
            } else {
                if (bunZipFileName.endsWith("-profile")) {
                    val split = bunZipFileName.split("-profile")

                    "${split[0]}-baseline-profile"
                } else {
                    "$bunZipFileName-baseline"
                }
            }

            logger.warn("Downloaded bun is not compatible will try with: {}", nextBunFileZipName)

            downloadBunRuntime(nextBunFileZipName)
        }
    }

    private fun getZipFile(id: String): Optional<Path> {
        val resourceDirUri = systemClassLoader.getResource("UIFiles")?.toURI()
        val dirPath = try {
            Paths.get(resourceDirUri)
        } catch (e: FileSystemNotFoundException) {
            // If this is thrown, then it means that we are running the JAR directly (example: not from an IDE)
            val env = mutableMapOf<String, String>()
            FileSystems.newFileSystem(resourceDirUri, env).getPath("UIFiles")
        }

        // Find the ZIP file matching the pattern setup-ui-*.zip
        val optionalZipFile = Files.list(dirPath).filter { file ->
            file.name.startsWith("$id-") && file.name.endsWith(".zip")
        }.findFirst()

        if (!optionalZipFile.isPresent) {
            logger.error("No file matching $id-*.zip was found!")

            exitProcess(1)
        }

        return optionalZipFile
    }

    private fun getZipAsStream(optionalZipFile: Optional<Path>): InputStream {
        return systemClassLoader.getResourceAsStream("UIFiles/" + optionalZipFile.get().name)!!
    }

    private fun unzipUIFiles(id: String, targetDir: File) {
        val optionalZipFile = getZipFile(id)

        logger.info("Found file: ${optionalZipFile.get().name}")

        val zipAsStream = getZipAsStream(optionalZipFile)

        // Extract the ZIP file
        ZipInputStream(zipAsStream).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile.mkdirs()
                    outFile.outputStream().use { output ->
                        zipStream.copyTo(output)
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }

        zipAsStream.close()

        logger.info("File successfully extracted to: ${targetDir.absolutePath}")

        val zipAsStreamForHash = getZipAsStream(optionalZipFile)
        val hash = zipAsStreamForHash.hash()
        zipAsStreamForHash.close()

        // Create manifest file
        val manifestFile = File(targetDir, manifestFileName)
        val themeManifest = parseThemeManifest(manifestFile)

        val installedTheme = InstalledTheme(
            themeManifest.id,
            themeManifest.title,
            themeManifest.description,
            themeManifest.version,
            themeManifest.author,
            themeManifest.license,
            themeManifest.sourceUrl,
            themeManifest.panoVersion,
            themeManifest.screenshots,
            hash,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            InstalledBy.SYSTEM
        )

        manifestFile.writeText(installedTheme.encode())
    }

    private fun redirectStreamToConsole(id: String, inputStream: InputStream) {
        val logger = LoggerFactory.getLogger("UI | $id")
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lines().forEach { logger.info(it) }
            }
        }
    }

    private fun startUI(id: String, uiFolder: String, port: Int = findAvailablePort()) {
        val processBuilder = ProcessBuilder()

        processBuilder.redirectErrorStream(true)
        processBuilder.command(bunFilePath, "run", uiFolder + File.separator + "index.js")

        val environment = processBuilder.environment()

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host
        val serverPort = serverConfig.port

        environment["PORT"] = port.toString()
        environment["HOST"] = serverHost
        environment["API_URL"] = "http://${serverHost}:${serverPort}/api"
        environment["PANO_WEBSITE_URL"] = config.panoWebsiteUrl

        val process = processBuilder.start()

        while (!process.isAlive) {
            Thread.sleep(100)
        }

        redirectStreamToConsole(id, process.inputStream)

        val startedUI = LoadedUI(id, serverHost, port, process)

        startedUIList.add(startedUI)

        logger.info("\"$id\" started at port: {}", port)
    }

    fun startUI(id: String, port: Int = findAvailablePort()) {
        val uiFolder = THEMES_FOLDER_PATH + File.separator + id

        startUI(id, uiFolder, port)
    }

    fun stopUI(id: String) {
        val startedUI = startedUIList.find { it.id == id }

        startedUI?.let {
            it.process.destroyForcibly()

            startedUIList.remove(it)

            logger.info("\"${it.id}\" stopped at port: {}", it.port)
        }
    }

    private fun initUiFolders() {
        if (!setupUIFolder.exists()) {
            logger.warn("Setup UI not found, installing...")

            unzipUIFiles("setup-ui", setupUIFolder)
        }

        if (!panelUIFolder.exists()) {
            logger.warn("Panel UI not found, installing...")

            unzipUIFiles("panel-ui", panelUIFolder)
        }

        if (!defaultThemeFolder.exists()) {
            logger.warn("Default vanilla theme not found, installing...")

            unzipUIFiles("vanilla-theme", defaultThemeFolder)
        }
    }

    private fun upgradeEmbeddedUi(id: String, targetDir: File) {
        targetDir.deleteRecursively()

        logger.warn("Upgrading found for: {}", id)

        unzipUIFiles(id, targetDir)
    }

    private fun checkEmbeddedUiUpgrade(id: String, targetDir: File) {
        val manifestFile = File(targetDir, manifestFileName)

        if (!manifestFile.exists()) {
            upgradeEmbeddedUi(id, targetDir)

            return
        }

        val manifest: InstalledTheme

        try {
            manifest = parseInstalledTheme(manifestFile)
        } catch (e: Exception) {
            upgradeEmbeddedUi(id, targetDir)

            return
        }

        val optionalZipFile = getZipFile(id)
        val zipFileAsStream = getZipAsStream(optionalZipFile)
        val hash = zipFileAsStream.hash()

        zipFileAsStream.close()

        if (manifest.hash == hash) {
            return
        }

        upgradeEmbeddedUi(id, targetDir)
    }

    private fun checkEmbeddedUiUpgrades() {
        checkEmbeddedUiUpgrade("setup-ui", setupUIFolder)
        checkEmbeddedUiUpgrade("panel-ui", panelUIFolder)
        checkEmbeddedUiUpgrade("vanilla-theme", defaultThemeFolder)
    }

    fun reloadInstalledThemes() {
        logger.info("Reloading installed themes...")
        _installedThemeList = mutableListOf()

        if (!themesFolder.exists()) {
            return
        }

        themesFolder.listFiles()
            ?.filter { themeFolder ->
                themeFolder.name.matches(Regex("^[a-zA-Z0-9-]+$"))
            }
            ?.forEach { themeFolder ->
                val manifestFile = File(themeFolder.absolutePath, manifestFileName)

                if (!manifestFile.exists()) {
                    return@forEach
                }

                try {
                    val installedTheme = parseInstalledTheme(manifestFile)

                    _installedThemeList.add(installedTheme)
                } catch (e: Exception) {
                    return@forEach
                }
            }

        logger.info("{} amount of installed theme found.", _installedThemeList.size)
    }

    internal fun init() {
        val config = configManager.config

        if (!config.initUi) {
            reloadInstalledThemes()

            return
        }

        if (!librariesFolder.exists()) {
            librariesFolder.mkdirs()
        }

        initUiFolders()

        checkEmbeddedUiUpgrades()

        logger.info("Verifying Bun runtime...")

        if (!File(bunFilePath).exists()) {
            logger.warn("Required Bun runtime is not found.")
            logger.warn("Downloading Bun runtime, may take a while...")

            downloadBunRuntime(bunZipFileName)
        }

        val currentTheme = config.currentTheme
        val currentThemeFolder = File(themesFolder.absolutePath + File.separator + currentTheme)

        val currentThemeValid = currentThemeFolder.exists() && currentThemeFolder.isDirectory

        if (!currentThemeValid) {
            logger.error("Current theme is not valid, defaulting to \"$DEFAULT_THEME_ID\"")
        }

        val theme = if (currentThemeValid) currentTheme else DEFAULT_THEME_ID

        activeTheme = theme

        try {
            startUI("setup-ui", setupUIFolder.absolutePath)
            startUI("panel-ui", panelUIFolder.absolutePath)
            startUI(theme)
        } catch (e: Exception) {
            logger.error("Failed to start UI.", e)

            exitProcess(1)
        }

        reloadInstalledThemes()
    }

    fun activateSetupUI(router: Router) {
        if (_activatedUIList.containsKey(Route.Type.SETUP_UI)) {
            return
        }

        val setupUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedSetupUI = startedUIList.find { it.id == "setup-ui" }
        val port = startedSetupUI?.port ?: 3002

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host

        setupUI.origin(port, serverHost)

        val setupUIHandler = ProxyHandler.create(setupUI)

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.SETUP_UI)
            .handler(setupUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.SETUP_UI] = ActivatedUI(port, serverHost, setupUIHandler)
    }

    fun activatePanelUI(router: Router) {
        if (_activatedUIList.containsKey(Route.Type.PANEL_UI)) {
            return
        }

        val panelUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedPanelUI = startedUIList.find { it.id == "panel-ui" }

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host

        val port = startedPanelUI?.port ?: 3001

        panelUI.origin(port, serverHost)

        val panelUIHandler = ProxyHandler.create(panelUI)

        router.route("/panel/*")
            .order(4)
            .putMetadata("type", Route.Type.PANEL_UI)
            .handler { context ->
                val request = context.request()
                request.pause()

                CoroutineScope(context.vertx().dispatcher()).launch {
                    val isLoggedIn = authProvider.isLoggedIn(context)

                    if (isLoggedIn) {
                        val hasAccessPanel = authProvider.hasAccessPanel(context)

                        if (hasAccessPanel) {
                            request.resume()
                            panelUIHandler.handle(context)

                            return@launch
                        }
                    }

                    request.resume()
                    _activatedUIList[Route.Type.THEME_UI]!!.proxyHandler.handle(context)
                }
            }
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.PANEL_UI] = ActivatedUI(port, serverHost, panelUIHandler)
    }


    fun activateThemeUI(router: Router, id: String) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val themeUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedThemeUI = startedUIList.find { it.id == id }
        activeTheme = id

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host

        val port = startedThemeUI?.port ?: 3000

        themeUI.origin(port, serverHost)

        val themeUIHandler = ProxyHandler.create(themeUI)

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.THEME_UI)
            .handler(themeUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.THEME_UI] = ActivatedUI(port, serverHost, themeUIHandler)
    }

    fun disableUIOnRoute(router: Router, UI: Route.Type) {
        val foundUI = router.routes.firstOrNull {
            val metadata = it.metadata() ?: mapOf()

            val type = metadata.getOrDefault("type", null)

            type != null && (type as Route.Type) == UI
        }

        foundUI?.let {
            it.disable()
            it.remove()
        }

        if (!_activatedUIList.containsKey(UI)) {
            return
        }

        _activatedUIList.remove(UI)
    }

    fun prepareUI(router: Router) {
        if (setupManager.isSetupDone()) {
            disableUIOnRoute(router, Route.Type.SETUP_UI)

            activateThemeUI(router, activeTheme)
            activatePanelUI(router)

            return
        }

        disableUIOnRoute(router, Route.Type.THEME_UI)
        disableUIOnRoute(router, Route.Type.PANEL_UI)

        activateSetupUI(router)
    }

    internal fun shutdown() {
        startedUIList.forEach {
            it.process.destroyForcibly()

            startedUIList.remove(it)
        }
    }

    companion object {
        private val gson by lazy {
            GsonBuilder()
                .registerTypeAdapterFactory(StrictNotNullTypeAdapterFactory())
                .setPrettyPrinting()
                .create()
        }

        fun InstalledTheme.encode(): String = gson.toJson(this)

        class LoadedUI(
            val id: String,
            val host: String,
            val port: Int,
            val process: Process
        )

        class ActivatedUI(
            val port: Int,
            val host: String,
            val proxyHandler: ProxyHandler
        )

        enum class InstalledBy {
            SYSTEM,
            USER
        }

        @StrictValidation
        open class ThemeManifest(
            val id: String,
            val title: String,
            val description: String? = null,
            val version: String,
            val author: String,
            val license: String? = null,
            val sourceUrl: String? = null,
            val panoVersion: String,
            val screenshots: List<String>
        )

        @StrictValidation
        data class InstalledTheme(
            val id: String,
            val title: String,
            val description: String? = null,
            val version: String,
            val author: String,
            val license: String? = null,
            val sourceUrl: String? = null,
            val panoVersion: String,
            val screenshots: List<String>,
            val hash: String,
            val createdAt: Long,
            val updatedAt: Long,
            val installedBy: InstalledBy
        )
    }
}