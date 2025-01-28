package com.panomc.platform

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.model.Route
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.OperatingSystem
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URL
import java.nio.file.*
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.system.exitProcess

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
open class UIManager(
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val httpClient: HttpClient,
    @Lazy private val authProvider: AuthProvider
) {
    private val themesFolderPath = System.getProperty("pano.themesFolder", "themes")
    private val librariesFolderPath = System.getProperty("pano.librariesFolder", "libraries")
    private val setupUIFolderPath = System.getProperty("pano.setupUIFolder", "setup-ui")
    private val panelUIFolderPath = System.getProperty("pano.panelUIFolder", "panel-ui")
    private val defaultThemeName = "Vanilla"
    private val defaultThemeFolderPath = themesFolderPath + File.separator + defaultThemeName

    private val themesFolder = File(themesFolderPath)
    private val librariesFolder = File(librariesFolderPath)
    private val setupUIFolder = File(setupUIFolderPath)
    private val panelUIFolder = File(panelUIFolderPath)
    private val defaultThemeFolder = File(defaultThemeFolderPath)

    private val githubUrl = "https://github.com"

    private val bunVersion = "bun-v1.2.0"
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
    private var activatedUIList = mutableMapOf<Route.Type, ProxyHandler>()

    private var activeTheme = ""

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun deleteOldBunRuntimes() {
        // Delete existing Bun files
        librariesFolder.listFiles { file ->
            file.name.startsWith("bun")
        }?.forEach { it.delete() }
    }

    //    Example: https://github.com/oven-sh/bun/releases/download/bun-v1.2.0/bun-darwin-x64-baseline-profile.zip
    private fun downloadBunRuntime() {
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
    }

    private fun unzipUIFiles(uiName: String, targetDir: File) {
        val resourceDirUri = ClassLoader.getSystemClassLoader().getResource("UIFiles")?.toURI()
        val dirPath = try {
            Paths.get(resourceDirUri)
        } catch (e: FileSystemNotFoundException) {
            // If this is thrown, then it means that we are running the JAR directly (example: not from an IDE)
            val env = mutableMapOf<String, String>()
            FileSystems.newFileSystem(resourceDirUri, env).getPath("UIFiles")
        }

        // Find the ZIP file matching the pattern setup-ui-*.zip
        val optionalZipFile = Files.list(dirPath).filter { file ->
            file.name.startsWith("$uiName-") && file.name.endsWith(".zip")
        }.findFirst()

        if (!optionalZipFile.isPresent) {
            logger.error("No file matching $uiName-*.zip was found!")

            exitProcess(1)
        }

        val zipFile =
            ClassLoader.getSystemClassLoader().getResource("UIFiles/" + optionalZipFile.get().name).openStream()

        logger.info("Found file: ${optionalZipFile.get().name}")

        // Extract the ZIP file
        ZipInputStream(zipFile).use { zipStream ->
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

        logger.info("File successfully extracted to: ${targetDir.absolutePath}")
    }

    private fun redirectStreamToConsole(uiName: String, inputStream: InputStream) {
        val logger = LoggerFactory.getLogger("UI | $uiName")
        val executor = Executors.newSingleThreadExecutor()

        executor.submit {
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lines().forEach { logger.info(it) }
            }
        }
    }

    private fun startUI(uiName: String, uiFolder: String, port: Int = findAvailablePort()) {
        val processBuilder = ProcessBuilder()

        processBuilder.redirectErrorStream(true)
        processBuilder.command(bunFilePath, "run", uiFolder + File.separator + "index.js")

        val environment = processBuilder.environment()

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host
        val serverPort = serverConfig.port

        environment["PORT"] = port.toString()
        environment["HOST"] = "127.0.0.1"
        environment["API_URL"] = "http://${serverHost}:${serverPort}/api"

        val process = processBuilder.start()

        while (!process.isAlive) {
            Thread.sleep(100)
        }

        redirectStreamToConsole(uiName, process.inputStream)

        val startedUI = LoadedUI(uiName, port, process)

        startedUIList.add(startedUI)

        logger.info("\"$uiName\" started at port: {}", port)
    }

    internal fun init() {
        val config = configManager.config

        if (!config.initUi) {
            return
        }

        if (!librariesFolder.exists()) {
            librariesFolder.mkdirs()
        }

        if (!setupUIFolder.exists()) {
            logger.warn("Setup UI not found, installing...")

            unzipUIFiles("setup-ui", setupUIFolder)
        }

        if (!panelUIFolder.exists()) {
            logger.warn("Panel UI not found, installing...")

            unzipUIFiles("panel-ui", panelUIFolder)
        }

        if (!defaultThemeFolder.exists()) {
            logger.warn("Default Vanilla theme not found, installing...")

            unzipUIFiles("vanilla-theme", defaultThemeFolder)
        }

        logger.info("Verifying Bun runtime...")

        if (!File(bunFilePath).exists()) {
            logger.warn("Required Bun runtime is not found.")
            logger.warn("Downloading Bun runtime, may take a while...")

            deleteOldBunRuntimes()

            downloadBunRuntime()
        }

        val currentTheme = config.currentTheme
        val currentThemeFolder = File(themesFolder.absolutePath + File.separator + currentTheme)

        val currentThemeValid = currentThemeFolder.exists() && currentThemeFolder.isDirectory

        if (!currentThemeValid) {
            logger.error("Current theme is not valid, defaulting to \"$defaultThemeName\"")
        }

        val themeFolder = if (currentThemeValid) currentThemeFolder.absolutePath else defaultThemeFolder.absolutePath

        val theme = if (currentThemeValid) currentTheme else defaultThemeName

        activeTheme = theme

        try {
            startUI("setup-ui", setupUIFolder.absolutePath)
            startUI("panel-ui", panelUIFolder.absolutePath)
            startUI(theme, themeFolder)
        } catch (e: Exception) {
            logger.error("Failed to start UI.", e)

            exitProcess(1)
        }
    }

    fun activateSetupUI(router: Router) {
        if (activatedUIList.containsKey(Route.Type.SETUP_UI)) {
            return
        }

        val setupUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedSetupUI = startedUIList.find { it.name == "setup-ui" }

        setupUI.origin(startedSetupUI?.port ?: 3002, "127.0.0.1")

        val setupUIHandler = ProxyHandler.create(setupUI)

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.SETUP_UI)
            .handler(setupUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        activatedUIList[Route.Type.SETUP_UI] = setupUIHandler
    }

    fun activatePanelUI(router: Router) {
        if (activatedUIList.containsKey(Route.Type.PANEL_UI)) {
            return
        }

        val panelUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedPanelUI = startedUIList.find { it.name == "panel-ui" }

        panelUI.origin(startedPanelUI?.port ?: 3001, "127.0.0.1")

        val panelUIHandler = ProxyHandler.create(panelUI)

        router.route("/panel/*")
            .order(4)
            .putMetadata("type", Route.Type.PANEL_UI)
            .handler { context ->
                CoroutineScope(context.vertx().dispatcher()).launch {
                    val hasAccessPanel = authProvider.hasAccessPanel(context)

                    if (hasAccessPanel) {
                        context.next()

                        return@launch
                    }

                    activatedUIList[Route.Type.THEME_UI]!!.handle(context)
                }
            }
            .handler(panelUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        activatedUIList[Route.Type.PANEL_UI] = panelUIHandler
    }

    fun activateThemeUI(router: Router) {
        if (activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val themeUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(false), httpClient)

        val startedThemeUI = startedUIList.find { it.name == activeTheme }

        themeUI.origin(startedThemeUI?.port ?: 3000, "127.0.0.1")

        val themeUIHandler = ProxyHandler.create(themeUI)

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.THEME_UI)
            .handler(themeUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        activatedUIList[Route.Type.THEME_UI] = themeUIHandler
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

        if (!activatedUIList.containsKey(UI)) {
            return
        }

        activatedUIList.remove(UI)
    }

    fun prepareUI(router: Router) {
        if (setupManager.isSetupDone()) {
            disableUIOnRoute(router, Route.Type.SETUP_UI)

            activateThemeUI(router)
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
        class LoadedUI(
            val name: String,
            val port: Int,
            val process: Process
        )
    }
}