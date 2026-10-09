package com.panomc.platform

import com.google.gson.GsonBuilder
import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.access.FrontendKeyService
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.gate.ApiLevelGate
import com.panomc.platform.gate.Verdict
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.license.LicenseDeniedReason
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.license.ThemeLicenseFailure
import com.panomc.platform.model.Route
import com.panomc.platform.route.ApiPaths
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.HashUtil.computeStableFileFingerprint
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.OperatingSystem
import com.panomc.platform.util.UsageMode
import com.panomc.platform.util.adapter.StrictNotNullTypeAdapterFactory
import com.panomc.platform.ui.FrontendMode
import com.panomc.platform.ui.UpstreamTarget
import com.panomc.platform.util.annotation.StrictValidation
import com.panomc.platform.util.ForwardedProtoInterceptor
import com.panomc.platform.util.UpstreamRetryInterceptor
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.core.net.SocketAddress
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.proxy.handler.ProxyHandler
import io.vertx.httpproxy.HttpProxy
import io.vertx.httpproxy.OriginRequestProvider
import io.vertx.httpproxy.ProxyContext
import io.vertx.httpproxy.ProxyInterceptor
import io.vertx.httpproxy.ProxyOptions
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.*
import java.net.ServerSocket
import java.net.URL
import java.nio.file.*
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.name

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class UIManager(
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val httpClient: HttpClient,
    private val authProvider: AuthProvider,
    private val main: Main,
    private val applicationContext: ApplicationContext,
    private val vertx: io.vertx.core.Vertx
) {
    private val librariesFolderPath = System.getProperty("pano.librariesFolder", "libraries")
    private val setupUIFolderPath = System.getProperty("pano.setupUIFolder", "setup-ui")
    private val panelUIFolderPath = System.getProperty("pano.panelUIFolder", "panel-ui")
    private val defaultThemeFolderPath = THEMES_FOLDER_PATH + File.separator + DEFAULT_THEME_ID
    private val customAppsFolderPath = System.getProperty("pano.customAppsFolder", "custom-apps")

    /**
     * Lazy to break the construction cycle: [LicenseManager] also looks up [UIManager] lazily
     * for the active-premium-theme fallback callback ([fallbackToDefaultThemeBecausePremiumLicenseLost]).
     */
    private val licenseManager: LicenseManager by lazy {
        applicationContext.getBean(LicenseManager::class.java)
    }

    /** Lazy like [licenseManager]: the key service is only needed when a UI is spawned. */
    private val frontendKeyService: FrontendKeyService by lazy {
        applicationContext.getBean(FrontendKeyService::class.java)
    }

    /**
     * The Vert.x router for re-wiring the theme-UI proxy route during license-loss fallback.
     * Lazy because the bean is built later in startup; null until then (in which case the
     * fallback only stops the bun process and updates active-theme bookkeeping; routes get
     * re-bound on the next prepareUI() pass).
     */
    private val routerForFallback: Router? by lazy {
        runCatching { applicationContext.getBean(Router::class.java) }.getOrNull()
    }

    val manifestFileName = "manifest.json"

    private val themesFolder = File(THEMES_FOLDER_PATH)
    private val librariesFolder = File(librariesFolderPath)
    private val setupUIFolder = File(setupUIFolderPath)
    private val panelUIFolder = File(panelUIFolderPath)
    private val defaultThemeFolder = File(defaultThemeFolderPath)

    /** Where uploaded custom apps live, one folder per id (`custom-apps/<id>/index.js`). */
    val customAppsFolder = File(customAppsFolderPath)

    private val githubUrl = "https://github.com"

    private val bunVersion = "bun-v1.4.2"
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

    private val startedUIList = CopyOnWriteArrayList<LoadedUI>()
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

    /**
     * What the wildcard route serves right now (doc 05 §8). Set when a front-end is bound and when
     * the boot decides; [FrontendMode.THEME] until then, which is what every install was before modes.
     */
    @Volatile
    var frontendMode: FrontendMode = FrontendMode.THEME
        private set

    /** What is bound to the wildcard route: the mode, the id of the front-end and, for a proxy, its address. */
    class SiteBinding(val mode: FrontendMode, val id: String, val upstream: String?)

    @Volatile
    var siteBinding: SiteBinding? = null
        private set

    /**
     * The `id` of the descriptor of an `EXTERNAL` / `NONE` front-end (doc 05 §8.2), for
     * [activeFrontendId]. The descriptor itself is read by another unit; until it sets this the
     * answer is "external".
     */
    @Volatile
    var descriptorId: String? = null

    /** The id of a custom app that was started by [startCustomApp] and not stopped since. */
    @Volatile
    private var customAppProcessId: String? = null

    /**
     * Who serves the site: theme id | custom-app id | descriptor `id` | `"external"` (doc 05 §8).
     * For a theme this is [activeTheme], also while a dev server stands in for its process.
     */
    /** The theme `current-theme` names, which may differ from [activeTheme] when the API level gate refused it. */
    fun configuredTheme(): String = configManager.config.currentTheme

    fun activeFrontendId(): String = when (frontendMode) {
        FrontendMode.THEME -> activeTheme
        FrontendMode.CUSTOM_APP -> siteBinding?.id ?: configManager.config.effectiveFrontend.customApp
        FrontendMode.EXTERNAL, FrontendMode.NONE -> descriptorId?.takeIf { it.isNotBlank() } ?: EXTERNAL_FRONTEND_ID
    }

    /** Whether the app [id] is the front-end in use (bound, or configured while it is being started). */
    fun isCustomAppActive(id: String): Boolean {
        val frontend = configManager.config.effectiveFrontend

        return (frontend.parsedMode == FrontendMode.CUSTOM_APP && frontend.customApp == id) ||
            (frontendMode == FrontendMode.CUSTOM_APP && siteBinding?.id == id)
    }

    /**
     * The theme dev server (`frontend.dev-url`, doc 05 §8) while it applies: the mode is THEME, Development
     * Mode is on and the address parses. Null in every other case, where the field is ignored.
     */
    fun devServerTarget(assumeThemeMode: Boolean = false): UpstreamTarget? {
        val config = configManager.config

        if (!config.developmentMode) {
            return null
        }

        // [assumeThemeMode]: the caller is about to make THEME the stored mode (ThemeUiController.apply).
        if (!assumeThemeMode && config.effectiveFrontend.parsedMode != FrontendMode.THEME) {
            return null
        }

        return UpstreamTarget.parse(config.effectiveFrontend.devUrl)
    }

    private val systemClassLoader = ClassLoader.getSystemClassLoader()

    private fun findAvailablePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private var muslRequired = false

    fun parseThemeManifest(manifestFile: File): ThemeManifest {
        val text = manifestFile.readText()

        // A custom app is not a theme (doc 05 §8.1): its manifest would fail the strict check below
        // anyway for lack of panoVersion and screenshots, but "this is an app" is the useful answer.
        if (declaresCustomApp(text)) {
            throw IllegalArgumentException("This is a custom app (type \"custom-app\"), not a theme. Upload it under Front-end.")
        }

        return gson.fromJson(text, ThemeManifest::class.java)
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

    private fun parseInstalledTheme(manifestFile: File): InstalledTheme = parseInstalledThemeText(manifestFile.readText())

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

            URL(fullUrl).openConnection().let { connection ->
                connection.getInputStream().use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastDotAt = 0L
                        val dotInterval = 1024 * 1024 // 1 MB

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (totalRead - lastDotAt >= dotInterval) {
                                print(". ")
                                System.out.flush()
                                lastDotAt = totalRead
                            }
                        }
                    }
                }
            }
            println()

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
            throw StartupFailure(
                "Couldn't download Bun runtime: ${e.message ?: e.toString()}. " +
                        "Check that this machine can reach $githubUrl, then start Pano again."
            )
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
            throw StartupFailure("No file matching $id-*.zip was found!")
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
        val screenshots = themeManifest.screenshots.map { it to File(targetDir, it) }.mapNotNull {
            if (it.second.exists()) {
                it.first to it.second.inputStream().hash()
            } else {
                null
            }
        }.toMap()

        val installedTheme = InstalledTheme(
            themeManifest.id,
            themeManifest.title,
            themeManifest.description,
            themeManifest.version,
            themeManifest.author,
            themeManifest.license,
            themeManifest.sourceUrl,
            themeManifest.panoVersion,
            screenshots,
            hash,
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            InstalledBy.SYSTEM,
            themeManifest.premium,
            themeManifest.fileFingerprint,
            themeManifest.apiLevel
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

    private suspend fun startUI(
        id: String,
        uiFolder: String,
        port: Int = findAvailablePort(),
        licenseJwt: String? = null
    ) {
        val processBuilder = ProcessBuilder()

        processBuilder.redirectErrorStream(true)
        // `--smol` runs Bun (JSC) in low-memory mode — smaller heap, more frequent GC. It's the
        // portable lever that works on every platform Pano runs on (Linux/macOS/Windows/Android);
        // the per-UI hard ceiling is enforced separately by startUiMemoryWatchdog().
        processBuilder.command(bunFilePath, "--smol", "run", uiFolder + File.separator + "index.js")

        val environment = processBuilder.environment()

        val config = configManager.config
        val serverConfig = config.server
        val serverHost = serverConfig.host
        val serverPort = serverConfig.httpPort

        environment["PORT"] = port.toString()
        // The UI listens on loopback only: the port is reachable through Pano's proxy and nowhere
        // else, so nobody can talk to it directly and name any client address in X-Forwarded-For.
        environment["HOST"] = "127.0.0.1"
        // Built SvelteKit apps: production mode silences the theme's bot-noise error logging
        // (405 form-action probes) and puts every library on its production path. Dev UIs are
        // never spawned here (they run under vite from the theme repo), so this cannot leak
        // into a dev server.
        environment["NODE_ENV"] = "production"
        // adapter-node derives event.url from these headers; without them it uses the Host
        // header our proxy rewrote to the upstream port (and assumes https), so the app believed
        // it lived at https://0.0.0.0:<port>. Both headers are set by any reverse proxy in front
        // of Pano and, failing that, by our own proxy: vertx-http-proxy adds X-Forwarded-Host
        // whenever it rewrites the authority, and ForwardedProtoInterceptor fills
        // X-Forwarded-Proto from the inbound connection.
        environment["PROTOCOL_HEADER"] = "x-forwarded-proto"
        environment["HOST_HEADER"] = "x-forwarded-host"
        // The UI CONNECTS to this URL for SSR fetches. Wildcard LISTEN addresses
        // (0.0.0.0 / ::) are not connectable targets — Linux happens to route them to
        // loopback, but Windows/macOS refuse the connection outright, breaking every
        // SSR fetch of a packaged install there. Always hand the UI a loopback address
        // when the platform listens on a wildcard.
        val apiHost = when (serverHost) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "[::1]"
            else -> serverHost
        }
        val apiUrl = "http://${apiHost}:${serverPort}/api"

        environment["API_URL"] = apiUrl
        // Open front-end contract (doc 05 §3.1): the same URL under its new name, this boot's
        // internal key (id 0, never stored; sessions stay cookie sessions for it) and where
        // visitors are (the configured website URL; empty = same as the API host).
        environment["PANO_API_URL"] = apiUrl
        environment["PANO_FRONTEND_KEY"] = frontendKeyService.internalKey
        environment["PANO_SITE_URL"] = config.effectiveFrontend.siteUrl.trim().trimEnd('/')
            .ifEmpty { config.websiteUrl.trim().trimEnd('/') }
        environment["PANO_WEBSITE_URL"] = config.panoWebsiteUrl
        environment["PANO_WEBSITE_API_URL"] = config.panoApiUrl
        if (!licenseJwt.isNullOrBlank()) {
            // The premium theme's own bun process verifies the RS256 signature with the embedded
            // public key and inspects claims. Free themes ignore the variable (its presence is
            // not a leak — without the matching public key it can't be turned into proof of
            // license for an unrelated theme).
            environment["PANO_LICENSE_JWT"] = licenseJwt
            environment["PANO_LICENSE_ISSUER"] = config.resolvedLicenseJwtIssuer()
        }

        val process = processBuilder.start()

        while (!process.isAlive) {
            Thread.sleep(100)
        }

        redirectStreamToConsole(id, process.inputStream)

        val startedUI = LoadedUI(id, "127.0.0.1", port, process, uiFolder, licenseJwt)

        startedUIList.add(startedUI)

        // `process.isAlive` only means bun forked — the SvelteKit HTTP listener binds a beat
        // later. Routing to the port before that yields a 502 window at boot and after a
        // memory-watchdog restart, so hold "started" until the upstream actually answers.
        waitUntilUiResponds(id, "127.0.0.1", port)

        logger.info("\"$id\" started at port: {}", port)
    }

    /**
     * Readiness probe for a freshly-spawned UI upstream: GETs the UI's base path every
     * [UI_READINESS_POLL_INTERVAL_MS] until ANY HTTP response arrives (any status code means
     * the listener is up — SvelteKit answers 200/404/500 the moment it binds). Gives up after
     * [UI_READINESS_TIMEOUT_MS] with a warning and lets startup continue as before — a slow
     * UI must degrade to the old 502-until-up behavior, never block or fail the platform.
     *
     * suspend + non-blocking on purpose: callers reach this from the Vert.x eventloop
     * dispatcher (panel API handlers via the suspend [startUI]) as well as from
     * `Dispatchers.IO` bridges ([startUIBlocking], [restartUiKeepingPort]), so it must
     * `delay()`/`coAwait()` rather than sleep.
     */
    private suspend fun waitUntilUiResponds(id: String, host: String, port: Int) {
        // Probe a STATIC SvelteKit asset, not the page root: a page GET triggers SSR,
        // and during platform boot the UIs come up before our own HTTP server, so the
        // SSR's backend fetches fail and every boot logs a scary 500 + stack from the
        // theme. version.json is served by adapter-node without SSR and without any
        // backend dependency; ANY response (even 404) still proves the listener is up.
        val basePath = if (id == "panel-ui") "/panel/_app/version.json" else "/_app/version.json"
        val deadline = System.currentTimeMillis() + UI_READINESS_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            try {
                val request = httpClient.request(
                    RequestOptions()
                        .setMethod(HttpMethod.GET)
                        .setHost(host)
                        .setPort(port)
                        .setURI(basePath)
                        .setTimeout(2_000L)
                ).coAwait()

                val response = request.send().coAwait()
                // Do NOT call response.end() here: the head future resuming on our
                // dispatcher races the event loop that keeps delivering body+end, so
                // end() can observe an already-ended stream and throw (misclassifying
                // a ready UI as down) or await an end event that already fired. An
                // unconsumed response is drained and the connection recycled by Vert.x
                // automatically once the body handler defaults kick in.

                logger.info("\"$id\" is answering HTTP {} on port {}.", response.statusCode(), port)

                return
            } catch (e: Exception) {
                // Connection refused / reset — listener not bound yet. Poll again shortly.
                delay(UI_READINESS_POLL_INTERVAL_MS)
            }
        }

        logger.warn(
            "\"$id\" did not answer HTTP on port {} within {}s — continuing anyway, early requests may fail until it comes up.",
            port, UI_READINESS_TIMEOUT_MS / 1000
        )
    }

    /**
     * Suspend variant for callers in a coroutine context (panel API handlers, install flow,
     * etc.). For a free UI this is functionally identical to [startUIBlocking]. For a
     * premium theme it does the expensive blocking work — fingerprint hashing (~hundreds
     * of files) and the panomc.com license fetch — on Vert.x worker threads instead of
     * the event loop, so a slow API response or a big build folder never blocks the
     * eventloop (which used to trip the BlockedThreadChecker after ~2s).
     *
     * Throws [LicenseRequiredException] when a premium theme cannot be started; callers
     * deal with that explicitly (panel returns ThemeLicenseRequired, init() falls back to
     * vanilla, etc.).
     */
    suspend fun startUI(requestedId: String, port: Int = findAvailablePort()) {
        // Every way a theme process gets started passes here, so the API level verdict is checked once, here: a
        // theme this Pano cannot run is replaced by the bundled one (the configured name is not touched).
        val id = servedThemeId(requestedId, _installedThemeList)

        if (id != requestedId) {
            logger.warn(
                "Theme '{}' is not compatible with this Pano ({}; supported API level {} to {}); serving \"{}\" instead. The configured theme is kept.",
                requestedId, themeVerdict(requestedId), ApiLevel.MIN_SUPPORTED, ApiLevel.CURRENT, id
            )
        }

        val uiFolder = THEMES_FOLDER_PATH + File.separator + id
        val theme = _installedThemeList.find { it.id == id }

        if (theme != null && theme.premium) {
            // File-fingerprint cross-check BEFORE we contact panomc.com for a license.
            // The hash walks every file in the theme directory; for a 700-file vanilla-
            // sized tree that's ~50ms locally but seconds on slow disks, so we always push
            // it to a worker thread.
            val claimedFingerprint = theme.fileFingerprint?.trim().orEmpty()
            if (claimedFingerprint.isEmpty()) {
                // A premium theme without a fileFingerprint indicates the theme was packed
                // without the postbuild step (or the publisher stripped it). We hard-fail
                // — quietly accepting it would let the publisher ship un-verifiable builds.
                logger.warn(
                    "Premium theme '{}' v{} has no fileFingerprint in its manifest — refusing to start.",
                    theme.id, theme.version,
                )
                throw LicenseRequiredException(
                    theme.id,
                    LicenseDeniedReason.FILE_TAMPERED,
                    "manifest.json has no fileFingerprint; rebuild the theme with the postbuild step"
                )
            }

            val computed = vertx.executeBlocking<String> {
                computeStableFileFingerprint(File(uiFolder))
            }.coAwait()
            if (!computed.equals(claimedFingerprint, ignoreCase = true)) {
                val detail = "expected ${claimedFingerprint.take(12)}…, got ${computed.take(12)}…"
                logger.warn(
                    "Refusing to start premium theme '{}' v{} — file integrity violation ({})",
                    theme.id, theme.version, detail,
                )
                throw LicenseRequiredException(
                    theme.id,
                    LicenseDeniedReason.FILE_TAMPERED,
                    "theme files modified after install: $detail"
                )
            }

            // Premium theme: fetch (or reuse cached) RS256 license JWT from panomc.com via
            // the license manager. requireThemeLicense is suspend, so the HTTP call goes
            // through Vert.x's web client without blocking the eventloop here.
            //
            // Strip the leading "v" from the manifest version before sending to the API:
            // ResourceVersion.tag on the backend is stored without it (e.g. "1.0.0-dev.44"),
            // matching plugin behaviour where PluginBuildConstants.VERSION is "v"-less. The
            // theme's manifest.json keeps the user-facing "v…" form for the panel UI.
            val normalizedVersion = theme.version.removePrefix("v")
            val jwt = try {
                licenseManager.requireThemeLicense(theme.id, normalizedVersion, theme.hash.lowercase()).rawJwt
            } catch (e: LicenseRequiredException) {
                logger.warn(
                    "Refusing to start premium theme '{}' {}: {}",
                    theme.id, theme.version, e.message,
                )
                throw e
            }
            licenseManager.setActivePremiumTheme(theme.id, normalizedVersion, theme.hash.lowercase())
            // Persist the JWT into the theme folder so the bun process can re-read it
            // after the host's renewal sweep refreshes the cache mid-flight. Env vars are
            // a one-shot delivery channel; without this file the theme runtime would only
            // ever see the JWT it was launched with and would `expired` out at TTL.
            writeThemeLicenseFile(theme.id, jwt)
            startUI(id, uiFolder, port, licenseJwt = jwt)
            return
        }

        // Free theme (or non-theme UI like setup-ui / panel-ui): clear premium bookkeeping
        // so the renewal sweep does not try to refresh a theme that's no longer running.
        if (theme != null && !theme.premium) {
            licenseManager.clearActivePremiumTheme()
        }
        startUI(id, uiFolder, port)
    }

    /**
     * Blocking entry point for code paths that aren't in a coroutine (boot init,
     * ThemeCommands console handler). MUST wrap the suspend chain in `Dispatchers.IO`
     * — mirrors the pattern PanoPlugin.start() uses for the same reason:
     *
     * `runBlocking { ... }` uses the BlockingEventLoop dispatcher by default, which parks
     * the calling thread. Inside startUI() we make Vert.x WebClient calls via `coAwait()`
     * — those resume on the Vert.x eventloop thread. With a BlockingEventLoop dispatcher
     * the resumed coroutine never gets back to the parked thread, and boot hangs forever
     * (panomc.com license fetch for a premium active theme is the trigger we saw in the
     * wild).
     *
     * `Dispatchers.IO` is a thread-pool dispatcher; suspending and resuming the
     * coroutine across the Vert.x eventloop boundary works cleanly there.
     */
    fun startUIBlocking(id: String, port: Int = findAvailablePort()) = runBlocking {
        withContext(Dispatchers.IO) {
            startUI(id, port)
        }
    }

    /**
     * Spawns the custom app `custom-apps/<id>/index.js` (doc 05 §8.1) and returns its port. Same
     * process, environment and memory watchdog as a theme, but none of the theme steps: no licence,
     * no fingerprint. The route is bound separately ([activateCustomAppUI]).
     *
     * Throws when the app is not on disk, the runtime cannot start, or the process is gone by the
     * time it should answer, so a caller can leave the previous front-end serving.
     */
    suspend fun startCustomApp(id: String, port: Int = findAvailablePort()): Int {
        val appFolder = File(customAppsFolder, id)

        if (!File(appFolder, "index.js").isFile) {
            throw IllegalStateException("Custom app '$id' is not installed (no index.js in ${appFolder.path}).")
        }

        startUI(id, appFolder.path, port)

        val started = startedUIList.find { it.id == id }

        if (started == null || !started.process.isAlive) {
            started?.let { startedUIList.remove(it) }

            throw IllegalStateException("Custom app '$id' exited while starting.")
        }

        customAppProcessId = id

        // A premium theme is no longer the thing running; keep the renewal sweep from refreshing it.
        runCatching { licenseManager.clearActivePremiumTheme() }

        return port
    }

    /** Stops whatever process serves the site (a theme or a custom app); a proxy has none. */
    fun stopSiteProcess(): String? {
        val id = customAppProcessId ?: siteBinding?.takeIf { it.upstream == null && it.mode == FrontendMode.THEME }?.id
            ?: return null

        stopUI(id)

        if (customAppProcessId == id) {
            customAppProcessId = null
        }

        return id
    }

    /**
     * Callback invoked by [LicenseManager] when the active premium theme's license cannot be
     * renewed (revoked purchase, Pano account disconnect, expired-and-network-down, etc.).
     * Stops the bun process, swaps the active theme to [DEFAULT_THEME_ID] in config, and
     * re-binds the theme proxy route so requests start being served by the vanilla theme.
     *
     * suspend because callers run on the Vert.x eventloop dispatcher (renewal sweep,
     * Pano-disconnect flow). Calling `runBlocking` from there would deadlock.
     */
    suspend fun fallbackToDefaultThemeBecausePremiumLicenseLost(lostThemeId: String) {
        if (activeTheme != lostThemeId) {
            // Already swapped (or theme changed by the operator in the meantime). Best-effort stop.
            stopUI(lostThemeId)
            return
        }

        stopUI(lostThemeId)
        val router = routerForFallback
        if (router != null) {
            disableUIOnRoute(router, Route.Type.THEME_UI)
        }

        val config = configManager.config
        config.currentTheme = DEFAULT_THEME_ID
        try {
            configManager.saveConfig()
        } catch (t: Throwable) {
            logger.error("Failed to persist fallback theme in config: {}", t.message, t)
        }

        val defaultFolder = File(themesFolder.absolutePath + File.separator + DEFAULT_THEME_ID)
        if (!defaultFolder.exists()) {
            logger.error(
                "Default theme '{}' missing on disk during license fallback — cannot bring theme UI back online",
                DEFAULT_THEME_ID,
            )
            return
        }

        try {
            // Default theme is vanilla (free), so suspend startUI is essentially synchronous
            // here — no fingerprint walk, no panomc.com call. We're already in a suspend
            // context (renewal sweep / removePanoAccount) so we can call it directly.
            startUI(DEFAULT_THEME_ID)
            if (router != null) {
                activateThemeUI(router, DEFAULT_THEME_ID)
            }
        } catch (t: Throwable) {
            logger.error(
                "Failed to bring up default theme after license fallback: {}",
                t.message,
                t,
            )
        }
        logger.warn(
            "Premium theme '{}' has been forcibly replaced with '{}' because its license could not be validated/renewed",
            lostThemeId, DEFAULT_THEME_ID,
        )
    }

    /**
     * No-op callback invoked by [LicenseManager] after a periodic renewal succeeds for the
     * active premium theme. Kept as a seam so future work can hot-rotate the JWT into the
     * running bun process if it ever needs the freshest token in-flight (today the theme
     * caches the JWT it received at boot and is already covered by signature verify and an
     * expiration check; the next renewal cycle simply reissues a fresh token in the host
     * cache that the theme will pick up if it restarts).
     */
    fun onActiveThemeLicenseRenewed(themeId: String) {
        logger.debug("Theme license renewed for '{}'", themeId)
        // Push the fresh JWT into the theme folder so the running bun process picks it
        // up via license-runtime.js's getCurrentJwt() before the previously-injected
        // env-var JWT expires.
        val cached = licenseManager.getCachedThemeLicense(themeId) ?: return
        writeThemeLicenseFile(themeId, cached.rawJwt)
    }

    /**
     * Atomically writes the JWT into `<themeFolder>/.pano-license.jwt` so the theme's bun
     * process can re-read it as the host renews the cache. File is in the exclude list for
     * the cumulative file fingerprint (see [HashUtil.DEFAULT_THEME_FINGERPRINT_EXCLUDES]),
     * so writing it does NOT invalidate the integrity check.
     *
     * Permissions are tightened to 0600 on POSIX filesystems — the bun process runs as
     * the same user as Pano, so it can read the file; other local users on the box can't.
     * Falls back silently on filesystems without POSIX perms (Windows).
     */
    private fun writeThemeLicenseFile(themeId: String, jwt: String) {
        val themeDir = File(THEMES_FOLDER_PATH, themeId)
        if (!themeDir.exists() || !themeDir.isDirectory) return
        val target = File(themeDir, ".pano-license.jwt")
        val tmp = File(themeDir, ".pano-license.jwt.tmp")
        try {
            tmp.writeText(jwt, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                Files.setPosixFilePermissions(target.toPath(), perms)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (Windows); skip.
            } catch (_: Throwable) {
                // Best-effort.
            }
        } catch (t: Throwable) {
            logger.warn("Failed to write license JWT for theme '{}': {}", themeId, t.message)
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Throwable) {
            }
        }
    }

    fun stopUI(id: String) {
        val startedUI = startedUIList.find { it.id == id }

        startedUI?.let {
            it.process.destroyForcibly()

            startedUIList.remove(it)

            logger.info("\"${it.id}\" stopped at port: {}", it.port)
        }
    }

    /**
     * Caps the memory of the Bun-based UI runtimes (setup-ui, panel-ui, active theme) that Pano
     * spawns. There is no portable per-process hard cap (cgroup is Linux-only, Job Objects are
     * Windows-only, macOS has none), so Pano enforces it itself: every interval it reads each UI
     * process's resident memory cross-platform and restarts any that exceed the configured limit.
     * The UIs are stateless SSR renderers and the restart reuses the SAME port, so the already-bound
     * proxy route keeps working untouched. Disabled when server.ui-max-memory-mb <= 0. Combined with
     * the `--smol` launch flag the UIs normally stay well under the limit; this is the safety net.
     */
    private fun startUiMemoryWatchdog() {
        val capMb = configManager.config.server.uiMaxMemoryMb

        if (capMb <= 0) {
            logger.info("UI memory watchdog disabled (server.ui-max-memory-mb <= 0)")
            return
        }

        val capBytes = capMb.toLong() * 1024L * 1024L

        vertx.setPeriodic(UI_MEMORY_WATCHDOG_INTERVAL_MS) {
            // Probing /proc or shelling out + respawning is blocking work — keep it off the eventloop.
            vertx.executeBlocking<Unit> {
                for (ui in startedUIList) {
                    try {
                        val rssBytes = readProcessResidentBytes(ui.process.pid())

                        // Only act on a real overshoot; never restart on an unreadable/zero reading.
                        if (rssBytes > 0 && rssBytes > capBytes) {
                            logger.warn(
                                "UI \"{}\" using {}MB exceeds {}MB cap — restarting on port {}.",
                                ui.id, rssBytes / (1024L * 1024L), capMb, ui.port
                            )

                            restartUiKeepingPort(ui)
                        }
                    } catch (e: Exception) {
                        logger.error("UI memory watchdog failed for \"${ui.id}\"", e)
                    }
                }
            }
        }

        logger.info(
            "UI memory watchdog started: {}MB cap per UI, checked every {}s.",
            capMb, UI_MEMORY_WATCHDOG_INTERVAL_MS / 1000
        )
    }

    /**
     * Stops the over-limit UI process and starts it again on the SAME port, so the proxy route that
     * captured that port at bind time keeps pointing at it (no rebind needed). Reuses the stored
     * launch parameters, so it works for any UI — setup-ui, panel-ui or a (premium) theme.
     */
    private fun restartUiKeepingPort(ui: LoadedUI) {
        try {
            ui.process.destroyForcibly()
            // Wait for the process to actually exit so its port is released before we rebind it.
            ui.process.waitFor(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("Couldn't cleanly stop UI \"${ui.id}\" before restart: {}", e.message)
        }

        startedUIList.remove(ui)

        // Same `runBlocking { withContext(Dispatchers.IO) { ... } }` bridge as
        // [startUIBlocking] (see its doc comment for why Dispatchers.IO is mandatory):
        // we are on a Vert.x worker thread here (the watchdog's executeBlocking), and the
        // readiness probe inside startUI() coAwait()s HTTP calls that resume on the eventloop.
        runBlocking {
            withContext(Dispatchers.IO) {
                startUI(ui.id, ui.uiFolder, ui.port, ui.licenseJwt)
            }
        }
    }

    /**
     * Resident memory (RSS / working set) of a child process in bytes, cross-platform and with no
     * external dependency. Returns -1 when it can't be read (the watchdog then skips that process —
     * it never kills on a bad reading). Linux/Android read /proc directly; macOS/Windows shell out
     * to a cheap built-in (the watchdog runs infrequently, so the spawn cost is negligible).
     */
    private fun readProcessResidentBytes(pid: Long): Long {
        return try {
            when (Main.OPERATING_SYSTEM) {
                OperatingSystem.LINUX -> {
                    // /proc/<pid>/statm: "size resident shared ..." in pages. Field 2 = resident.
                    val statm = File("/proc/$pid/statm")
                    if (!statm.exists()) return -1
                    val residentPages =
                        statm.readText().trim().split(" ").getOrNull(1)?.toLongOrNull() ?: return -1
                    // 4 KiB pages on virtually all Linux/Android x64/arm64 targets. Larger pages would
                    // only make us under-count (a less eager cap), never a false kill — so it's safe.
                    residentPages * 4096L
                }

                OperatingSystem.DARWIN -> {
                    // ps reports RSS in KiB.
                    val kib = runMemoryProbe(listOf("ps", "-o", "rss=", "-p", pid.toString()))
                        ?.trim()?.toLongOrNull() ?: return -1
                    kib * 1024L
                }

                OperatingSystem.WINDOWS -> {
                    // WorkingSet64 is already in bytes.
                    runMemoryProbe(
                        listOf("powershell", "-NoProfile", "-Command", "(Get-Process -Id $pid).WorkingSet64")
                    )?.trim()?.toLongOrNull() ?: -1
                }
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Runs a short probe command and returns its stdout, or null on any failure/timeout. */
    private fun runMemoryProbe(command: List<String>): String? {
        return try {
            val probe = ProcessBuilder(command).redirectErrorStream(false).start()

            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                return null
            }

            // Output is a single number — safe to read after exit without a pipe-fill deadlock.
            if (probe.exitValue() == 0) probe.inputStream.bufferedReader().readText() else null
        } catch (e: Exception) {
            null
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

    /** The configured theme [id] became compatible while nothing is bound for it: it is the one to serve from now on. */
    fun resumeConfiguredTheme(id: String) {
        activeTheme = id
    }

    /** The API level verdict for an installed theme, see [Companion.themeVerdict]. */
    fun themeVerdict(id: String): Verdict = themeVerdict(id, installedThemeList)

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
                    logger.warn("Theme folder '{}' has a manifest.json Pano cannot read ({}); it is not listed.", themeFolder.name, e.message)

                    return@forEach
                }
            }

        logger.info("{} installed theme found.", _installedThemeList.size)
    }

    internal fun init() {
        val config = configManager.config

        startUiMemoryWatchdog()

        if (config.initUi) {
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
        }

        val currentTheme = config.currentTheme
        val currentThemeFolder = File(themesFolder.absolutePath + File.separator + currentTheme)

        val currentThemeValid = currentThemeFolder.exists() && currentThemeFolder.isDirectory

        if (!currentThemeValid) {
            logger.error("Current theme is not valid, defaulting to \"$DEFAULT_THEME_ID\"")
        }

        // Populate installedThemeList BEFORE attempting to start a premium theme, otherwise
        // startUI() can't see the manifest and treats it as a free theme (skipping the
        // license check). It also holds the API levels the gate below reads.
        reloadInstalledThemes()

        val configuredTheme = if (currentThemeValid) currentTheme else DEFAULT_THEME_ID

        // Gate 1 for themes (doc 04 section 7): a theme outside the supported API level runs the bundled one for
        // this boot. config.currentTheme is not rewritten, so the site returns to its theme after an update.
        val verdict = themeVerdict(configuredTheme)
        val theme = if (verdict == Verdict.OK) {
            configuredTheme
        } else {
            logger.warn(
                "Theme '{}' is not compatible with this Pano ({}; supported API level {} to {}); serving \"{}\" instead. The configured theme is kept.",
                configuredTheme, verdict, ApiLevel.MIN_SUPPORTED, ApiLevel.CURRENT, DEFAULT_THEME_ID
            )

            DEFAULT_THEME_ID
        }

        activeTheme = theme
        frontendMode = config.effectiveFrontend.parsedMode

        if (config.initUi) {
            try {
                // Same Dispatchers.IO bridge as [startUIBlocking] (see its doc comment):
                // startUI() is suspend now that it ends with the HTTP readiness probe.
                runBlocking {
                    withContext(Dispatchers.IO) {
                        if (!setupManager.isSetupDone()) {
                            startUI("setup-ui", setupUIFolder.absolutePath)
                        }
                        startUI("panel-ui", panelUIFolder.absolutePath)
                    }
                }
                if (!isThemeWanted()) {
                    // A servers-only install has no website, and the theme is a second Bun
                    // process (~200 MB) serving pages nobody can reach: every theme page is
                    // redirected to the panel and the panel now has its own login. Not starting
                    // it is the whole point of U-06 — the memory saving is the feature.
                    logger.info("Usage mode is SERVERS: not starting the theme process.")
                } else {
                    startFrontendAtBoot(theme)
                }
            } catch (e: Exception) {
                throw e as? StartupFailure ?: StartupFailure("Failed to start UI.", e)
            }
        }

        // Fire-and-forget: verify license for EVERY installed premium theme (not just the
        // active one) so the panel UI can show a correct licensed/unlicensed badge before
        // the operator tries to activate. The active theme is already cached above; this
        // sweep covers the others. Failures populate LicenseManager.themeFailures; the
        // periodic renewal sweep keeps caches fresh long-term.
        CoroutineScope(vertx.dispatcher()).launch {
            try {
                licenseManager.verifyAllInstalledPremiumThemesBestEffort()
            } catch (t: Throwable) {
                logger.debug("Initial premium theme license sweep failed: {}", t.message)
            }
        }
    }

    /**
     * Boot-time start of the configured front-end (doc 05 §8). Decides [frontendMode]: a custom app
     * that is not installed or will not start, or an `upstream-url` that does not parse, leaves the
     * site on its theme instead of a dead route (the config is not touched, so the cause is still
     * there to fix). `dev-url` stands in for the theme process while it applies.
     */
    private fun startFrontendAtBoot(theme: String) {
        val frontend = configManager.config.effectiveFrontend

        when (frontend.parsedMode) {
            FrontendMode.CUSTOM_APP -> {
                try {
                    runBlocking { withContext(Dispatchers.IO) { startCustomApp(frontend.customApp) } }

                    frontendMode = FrontendMode.CUSTOM_APP

                    return
                } catch (e: Exception) {
                    logger.error(
                        "Custom app '{}' cannot start ({}); serving theme '{}' instead.",
                        frontend.customApp, e.message, theme
                    )
                }
            }

            FrontendMode.EXTERNAL -> {
                if (UpstreamTarget.parse(frontend.upstreamUrl) != null) {
                    logger.info("Front-end mode is EXTERNAL: proxying pages to {}; not starting a theme.", frontend.upstreamUrl)

                    frontendMode = FrontendMode.EXTERNAL

                    return
                }

                logger.error("frontend.upstream-url '{}' is not a valid address; serving theme '{}' instead.", frontend.upstreamUrl, theme)
            }

            FrontendMode.NONE -> {
                logger.info("Front-end mode is NONE: no site pages are served.")

                frontendMode = FrontendMode.NONE

                return
            }

            FrontendMode.THEME -> Unit
        }

        frontendMode = FrontendMode.THEME

        val devServer = devServerTarget()

        if (devServer != null) {
            logger.info("Theme dev server {} is set and Development Mode is on: not starting theme '{}'.", devServer, theme)

            return
        }

        startThemeAtBoot(theme)
    }

    private fun startThemeAtBoot(theme: String) {
        try {
            startUIBlocking(theme)
        } catch (e: LicenseRequiredException) {
            // Premium theme cannot be licensed right now (no account connected, no
            // purchase, expired, network down, etc.). Persist a fallback to the bundled
            // vanilla theme so subsequent restarts also boot cleanly — the operator can
            // manually re-activate the premium theme from the panel once the license
            // issue is resolved.
            logger.warn(
                "Active theme '{}' has no valid license at boot ({}); booting with default theme '{}' instead",
                theme, e.reason.publicId, DEFAULT_THEME_ID,
            )
            activeTheme = DEFAULT_THEME_ID
            configManager.config.currentTheme = DEFAULT_THEME_ID
            try {
                configManager.saveConfig()
            } catch (t: Throwable) {
                logger.error("Failed to persist fallback theme at boot: {}", t.message, t)
            }
            startUIBlocking(DEFAULT_THEME_ID)
        }
    }

    /**
     * See [UpstreamRetryInterceptor]: re-sends GET/HEAD/OPTIONS when the Bun upstream drops the
     * pooled connection before answering (the proxy's own 502 path is silent). One instance per
     * proxy so the debug lines name the UI.
     */
    private fun upstreamRetryInterceptor(uiId: String) = UpstreamRetryInterceptor(logger, uiId)

    /** See [ForwardedProtoInterceptor]: the UIs read `X-Forwarded-Proto` to learn their scheme. */
    private val forwardedProtoInterceptor = ForwardedProtoInterceptor { configManager.config.server.trustedProxies }

    /**
     * Response-side cache policy stamped onto everything the UI reverse-proxies serve. The
     * Bun/SvelteKit upstreams only mark their own `/_app/immutable` assets; the rest ships
     * without Cache-Control, which lets intermediaries make bad guesses. Policy:
     *
     * - `text/html` → `no-cache`: SSR HTML must always revalidate so a stale document can
     *   never outlive the hashed assets (importmap URLs, runtime shim `?v=` params) it
     *   references.
     * - `/lib/<16-hex-hash>/…` (and `/panel/lib/…`) → immutable for a year: the bootstrap
     *   bundles are content-hashed, so a URL's payload can never change.
     * - `/runtime/…` (and `/panel/runtime/…`) → immutable for a year, but ONLY when requested
     *   with the `?v=` cache-busting param the importmap appends; the bare stable URL must
     *   stay `no-cache` — its content changes across theme/panel releases.
     * - Any NON-2xx answer on an asset path (`/_app/immutable`, `/lib`, `/runtime`, `/plugins`)
     *   → `no-store`: an upstream 404/5xx for a script chunk must never be held by a CDN or
     *   browser (Cloudflare keeps a bare 404 for 3 minutes), or every visitor of that edge gets
     *   a dead page until it expires. 304s stay header-less, see below.
     *
     * Immutable is additionally gated on a 2xx status so an upstream error/404 on those
     * paths can never be pinned into caches for a year.
     *
     * Registered via the single-arg [HttpProxy.addInterceptor], which marks the interceptor
     * as NOT supporting WebSocket upgrades — the proxy skips it entirely for upgrade
     * requests, so WebSocket traffic (Vite HMR etc.) passes through untouched.
     */
    private val uiCacheControlInterceptor = object : ProxyInterceptor {
        override fun handleProxyResponse(context: ProxyContext): Future<Void> {
            val response = context.response()
            val proxiedRequest = context.request().proxiedRequest()

            uiCachePolicy(
                path = proxiedRequest.path() ?: "",
                statusCode = response.statusCode,
                contentType = response.headers().get("Content-Type"),
                hasVersionParam = proxiedRequest.getParam("v") != null
            )?.let { response.putHeader("Cache-Control", it) }

            return context.sendResponse()
        }
    }

    fun activateSetupUI(router: Router) {
        if (_activatedUIList.containsKey(Route.Type.SETUP_UI)) {
            return
        }

        val setupUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)
        setupUI.addInterceptor(uiCacheControlInterceptor)
        setupUI.addInterceptor(forwardedProtoInterceptor)
        setupUI.addInterceptor(upstreamRetryInterceptor("setup-ui"))

        val startedSetupUI = startedUIList.find { it.id == "setup-ui" }
        val port = startedSetupUI?.port ?: 3002

        val config = configManager.config
        val serverConfig = config.server

        setupUI.origin(port, "127.0.0.1")

        val setupUIHandler = ProxyHandler.create(setupUI)

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.SETUP_UI)
            .handler(setupUIHandler)
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.SETUP_UI] = ActivatedUI(port, "127.0.0.1", setupUIHandler)
    }

    fun activatePanelUI(router: Router) {
        if (_activatedUIList.containsKey(Route.Type.PANEL_UI)) {
            return
        }

        val panelUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)
        panelUI.addInterceptor(uiCacheControlInterceptor)
        panelUI.addInterceptor(forwardedProtoInterceptor)
        panelUI.addInterceptor(upstreamRetryInterceptor("panel-ui"))

        val startedPanelUI = startedUIList.find { it.id == "panel-ui" }

        val config = configManager.config
        val serverConfig = config.server

        val port = startedPanelUI?.port ?: 3001

        panelUI.origin(port, "127.0.0.1")

        val panelUIHandler = ProxyHandler.create(panelUI)

        router.route("/panel/*")
            .order(4)
            .putMetadata("type", Route.Type.PANEL_UI)
            .handler { context ->
                val request = context.request()
                request.pause()

                CoroutineScope(context.vertx().dispatcher()).launch {
                    val isLoggedIn = authProvider.isLoggedIn(context)

                    if (context.response().closed()) {
                        return@launch
                    }

                    // A site token (a server-side front-end's session) never opens the panel.
                    try {
                        authProvider.requireNoSiteToken(context)
                    } catch (error: com.panomc.platform.model.Error) {
                        request.resume()

                        context.response()
                            .setStatusCode(error.getStatusCode())
                            .setStatusMessage(error.getStatusMessage())
                            .putHeader("content-type", "application/json; charset=utf-8")
                            .end(error.encode())

                        return@launch
                    }

                    // Permissions are attached for a signed-in visitor because the panel's own
                    // endpoints read them; the page itself is served either way.
                    if (isLoggedIn) {
                        authProvider.applyPermissionsTo(context)
                    }

                    request.resume()

                    // Everything under /panel is panel-ui's, signed in or not. It used to hand an
                    // unauthenticated visitor to the theme, because the login page lived there —
                    // which made the theme process mandatory even on an install with no website,
                    // and produced a 503 whenever the theme happened to be switching. panel-ui has
                    // its own /panel/login now, so this is one proxy with one owner.
                    panelUIHandler.handle(context)
                }
            }
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.PANEL_UI] = ActivatedUI(port, "127.0.0.1", panelUIHandler)
    }


    /**
     * Binds the site's wildcard route (the root wildcard, order 5, metadata type THEME_UI whatever the mode) to the
     * upstream at [host]:[port]. Theme, custom app, `EXTERNAL` and the theme dev server all go through
     * here, so they share the WebSocket support, the forwarded-proto header and the retry on a dropped
     * keep-alive connection.
     *
     * [external] marks a front-end Pano did not start (`EXTERNAL`, `dev-url`): its HTTP caching is its
     * own, and `/panel`, `/api` and `/_pano` never reach it (they answer 404 here when nothing before
     * them claimed the path). A theme or an app keeps the behaviour it always had.
     */
    fun activateSiteUI(router: Router, host: String, port: Int, ssl: Boolean, id: String, external: Boolean = false) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val siteUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)

        if (!external) {
            siteUI.addInterceptor(uiCacheControlInterceptor)
        }

        siteUI.addInterceptor(forwardedProtoInterceptor)
        siteUI.addInterceptor(upstreamRetryInterceptor(id))

        if (ssl) {
            siteUI.origin(OriginRequestProvider { proxyContext ->
                proxyContext.client().request(
                    RequestOptions()
                        .setServer(SocketAddress.inetSocketAddress(port, host))
                        .setHost(host)
                        .setPort(port)
                        .setSsl(true)
                )
            })
        } else {
            siteUI.origin(port, host)
        }

        val siteUIHandler = ProxyHandler.create(siteUI)

        val routeHandler: Handler<RoutingContext> = if (external) {
            Handler { context ->
                if (isReservedPath(context.normalizedPath())) {
                    notFoundHandler().handle(context)
                } else {
                    siteUIHandler.handle(context)
                }
            }
        } else {
            siteUIHandler
        }

        router.route("/*")
            .order(5)
            .putMetadata("type", Route.Type.THEME_UI)
            .handler(routeHandler)
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.THEME_UI] = ActivatedUI(port, host, siteUIHandler)
    }

    fun activateThemeUI(router: Router, id: String) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        // Activating a theme (the panel's theme list, the console, an upgrade) is choosing the THEME
        // mode, so the config follows when another mode was set.
        adoptThemeMode()

        bindThemeUI(router, id)
    }

    private fun bindThemeUI(router: Router, id: String) {
        activeTheme = id
        frontendMode = FrontendMode.THEME

        // The theme author's dev server stands in for the theme process (doc 05 §8); a process that
        // was started for the theme anyway is simply not routed to.
        devServerTarget()?.let { dev ->
            activateExternalUI(router, dev, DEV_SERVER_FRONTEND_ID)

            return
        }

        val port = startedUIList.find { it.id == id }?.port ?: 3000

        activateSiteUI(router, "127.0.0.1", port, false, id)

        siteBinding = SiteBinding(FrontendMode.THEME, id, null)
    }

    /** Binds the wildcard route to the running custom app [id] (see [startCustomApp]). */
    fun activateCustomAppUI(router: Router, id: String) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val port = startedUIList.find { it.id == id }?.port ?: 3000

        frontendMode = FrontendMode.CUSTOM_APP

        activateSiteUI(router, "127.0.0.1", port, false, id)

        siteBinding = SiteBinding(FrontendMode.CUSTOM_APP, id, null)
    }

    /**
     * Binds the wildcard route to a front-end Pano does not run: `EXTERNAL`, or the theme dev server
     * (then [id] is [DEV_SERVER_FRONTEND_ID] and the mode stays THEME).
     */
    fun activateExternalUI(router: Router, target: UpstreamTarget, id: String = EXTERNAL_FRONTEND_ID) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val mode = if (id == DEV_SERVER_FRONTEND_ID) FrontendMode.THEME else FrontendMode.EXTERNAL

        frontendMode = mode

        activateSiteUI(router, target.host, target.port, target.ssl, id, external = true)

        siteBinding = SiteBinding(mode, id, target.origin)
    }

    /** `NONE`: nothing is bound; the order-6 handlers answer ([com.panomc.platform.route.ServersModeRootHandler]). */
    fun useNoFrontend() {
        frontendMode = FrontendMode.NONE
        siteBinding = SiteBinding(FrontendMode.NONE, EXTERNAL_FRONTEND_ID, null)
    }

    /** Whether the wildcard route is bound to what [mode], [id] and [upstream] describe right now. */
    fun isBoundTo(mode: FrontendMode, id: String, upstream: String?): Boolean {
        val binding = siteBinding ?: return false

        if (mode == FrontendMode.NONE) {
            return binding.mode == FrontendMode.NONE
        }

        return _activatedUIList.containsKey(Route.Type.THEME_UI) &&
            binding.mode == mode && binding.id == id && binding.upstream == upstream
    }

    private fun adoptThemeMode() {
        val previousMode = frontendMode

        val config = configManager.config
        val frontend = config.effectiveFrontend

        if (frontend.parsedMode == FrontendMode.THEME) {
            return
        }

        if (previousMode == FrontendMode.CUSTOM_APP) {
            customAppProcessId?.let { stopUI(it) }
            customAppProcessId = null
        }

        frontend.mode = FrontendMode.THEME.name
        config.frontend = frontend

        try {
            configManager.saveConfig()
        } catch (t: Throwable) {
            logger.error("Failed to persist front-end mode THEME: {}", t.message, t)
        }
    }

    private fun isReservedPath(path: String) =
        RESERVED_SITE_PREFIXES.any { path == it || path.startsWith("$it/") }

    private fun notFoundHandler(): Handler<RoutingContext> = Handler { context ->
        context.response()
            .setStatusCode(404)
            .putHeader("Cache-Control", "no-store")
            .putHeader("Content-Type", "text/plain; charset=utf-8")
            .end("Not Found")
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

        if (UI == Route.Type.THEME_UI) {
            siteBinding = null
        }

        if (!_activatedUIList.containsKey(UI)) {
            return
        }

        _activatedUIList.remove(UI)
    }

    fun prepareUI(router: Router) {
        if (setupManager.isSetupDone()) {
            stopUI("setup-ui")

            disableUIOnRoute(router, Route.Type.SETUP_UI)

            // No theme process in SERVERS mode, so no proxy route to it either: binding one would
            // send every page request to a port nothing is listening on.
            if (isThemeWanted()) {
                activateConfiguredFrontend(router)
            }

            activatePanelUI(router)

            return
        }

        disableUIOnRoute(router, Route.Type.THEME_UI)
        disableUIOnRoute(router, Route.Type.PANEL_UI)

        activateSetupUI(router)
    }

    /** Binds the wildcard route for [frontendMode] (resolved at boot, see [startFrontendAtBoot]). */
    private fun activateConfiguredFrontend(router: Router) {
        val frontend = configManager.config.effectiveFrontend

        when (frontendMode) {
            // Not activateThemeUI: a boot that fell back to the theme must not rewrite the config.
            FrontendMode.THEME -> if (!_activatedUIList.containsKey(Route.Type.THEME_UI)) bindThemeUI(router, activeTheme)
            FrontendMode.CUSTOM_APP -> activateCustomAppUI(router, frontend.customApp)
            FrontendMode.EXTERNAL -> {
                val target = UpstreamTarget.parse(frontend.upstreamUrl)

                if (target == null) {
                    if (!_activatedUIList.containsKey(Route.Type.THEME_UI)) bindThemeUI(router, activeTheme)
                } else {
                    activateExternalUI(router, target)
                }
            }

            FrontendMode.NONE -> useNoFrontend()
        }
    }

    /**
     * Whether this install should be running a theme at all.
     *
     * SERVERS mode is a game-server panel with no public website: the gate redirects every theme
     * page to `/panel`, the panel signs people in by itself, and what is left is a Bun process
     * serving pages nobody will ever be routed to.
     */
    internal fun isThemeWanted(): Boolean = configManager.config.effectiveUsageMode != UsageMode.SERVERS

    internal fun shutdown() {
        startedUIList.forEach {
            stopUI(it.id)
        }
    }


    fun getStartedUIs(): List<LoadedUI> = Collections.unmodifiableList(startedUIList)

    fun getEmbeddedUIVersions(): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        listOf("setup-ui" to setupUIFolder, "panel-ui" to panelUIFolder, "vanilla-theme" to defaultThemeFolder).forEach { (id, folder) ->
            val manifestFile = File(folder, manifestFileName)
            if (manifestFile.exists()) {
                try {
                    val manifest = parseInstalledTheme(manifestFile)
                    versions[id] = manifest.version
                } catch (_: Exception) {}
            }
        }
        return versions
    }

    companion object {
        private const val UI_MEMORY_WATCHDOG_INTERVAL_MS = 30_000L

        /** [UIManager.activeFrontendId] of an `EXTERNAL` / `NONE` front-end without a descriptor. */
        const val EXTERNAL_FRONTEND_ID = "external"

        /** Proxy id of the theme dev server (`frontend.dev-url`). */
        const val DEV_SERVER_FRONTEND_ID = "theme-dev-server"

        /** Paths that belong to Pano and are never handed to an `EXTERNAL` front-end or the dev server. */
        private val RESERVED_SITE_PREFIXES = listOf("/panel", ApiPaths.BASE, "/_pano")

        /** Whether a manifest.json says `"type": "custom-app"`. */
        internal fun declaresCustomApp(manifestText: String): Boolean = try {
            com.google.gson.JsonParser.parseString(manifestText).asJsonObject.get("type")
                ?.takeIf { it.isJsonPrimitive }?.asString == "custom-app"
        } catch (_: Exception) {
            false
        }

        private const val UI_READINESS_TIMEOUT_MS = 20_000L
        private const val UI_READINESS_POLL_INTERVAL_MS = 250L

        private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

        /** Script/asset namespaces served by the UI upstreams (theme at `/`, panel at `/panel`). */
        private val ASSET_PATH_REGEX = Regex("^(/panel)?/(_app/immutable/|lib/|runtime/|plugins/)")

        /**
         * The `Cache-Control` value to stamp on a UI upstream response, or null to leave it as the
         * upstream sent it. Pure so the policy is unit-testable; see [uiCacheControlInterceptor].
         */
        internal fun uiCachePolicy(
            path: String,
            statusCode: Int,
            contentType: String?,
            hasVersionParam: Boolean
        ): String? {
            val isSuccess = statusCode in 200..299
            val isHtml = contentType?.contains("text/html", ignoreCase = true) == true

            return when {
                // A 304 carries no body; any header on it would freshen the client's stored copy.
                statusCode == 304 -> when {
                    RUNTIME_PATH_REGEX.containsMatchIn(path) -> null
                    isHtml -> "no-cache"
                    else -> null
                }

                !isSuccess && ASSET_PATH_REGEX.containsMatchIn(path) -> "no-store"

                isHtml -> "no-cache"

                LIB_HASHED_PATH_REGEX.containsMatchIn(path) -> if (isSuccess) IMMUTABLE_CACHE_CONTROL else null

                RUNTIME_PATH_REGEX.containsMatchIn(path) ->
                    if (isSuccess && hasVersionParam) IMMUTABLE_CACHE_CONTROL else "no-cache"

                else -> null
            }
        }

        /**
         * Catch-all behind the theme proxy route (order 5) for the moments no UI owns the wildcard
         * route: boot until the theme is activated, and the window while an admin switches
         * themes. Without it Vert.x answers its default 404 HTML with no cache headers, which
         * CDNs cache (3 min on Cloudflare) and browsers show as a real "not found" — for a script
         * chunk that kills hydration for everyone behind that edge. A 503 with `no-store` is
         * retried, never cached.
         */
        fun uiUnavailableHandler(): Handler<RoutingContext> = Handler { context ->
            context.response()
                .setStatusCode(503)
                .putHeader("Cache-Control", "no-store")
                .putHeader("Retry-After", "2")
                .putHeader("Content-Type", "text/plain; charset=utf-8")
                .end("The site UI is starting; retry shortly.")
        }

        /** Content-hashed bootstrap bundle dirs emitted by scripts/bundle-internal-libs.js. */
        private val LIB_HASHED_PATH_REGEX = Regex("^(/panel)?/lib/[0-9a-f]{16}/")

        /** Stable-URL runtime shims (scripts/generate-runtime-shims.js); versioned via `?v=`. */
        private val RUNTIME_PATH_REGEX = Regex("^(/panel)?/runtime/")

        private val gson by lazy {
            GsonBuilder()
                .registerTypeAdapterFactory(StrictNotNullTypeAdapterFactory())
                .setPrettyPrinting()
                .create()
        }

        fun InstalledTheme.encode(): String = gson.toJson(this)

        /**
         * Reads the `manifest.json` of an installed theme. A manifest laid down by an older Pano or copied from a theme
         * zip lists `screenshots` as an array and has no `apiLevel`; it reads here as a theme of level 0 instead of
         * failing, because a theme that cannot be read cannot be judged and would be started unchecked.
         */
        internal fun parseInstalledThemeText(text: String): InstalledTheme {
            val json = com.google.gson.JsonParser.parseString(text).asJsonObject
            val screenshots = json.get("screenshots")

            if (screenshots != null && screenshots.isJsonArray) {
                val map = com.google.gson.JsonObject()

                screenshots.asJsonArray.forEach { map.addProperty(it.asString, "") }

                json.add("screenshots", map)
            }

            return gson.fromJson(json, InstalledTheme::class.java)
        }

        /** The theme to start for [id]: [id] itself when its verdict is OK, else the bundled [DEFAULT_THEME_ID]. */
        internal fun servedThemeId(id: String, installed: List<InstalledTheme>): String =
            if (themeVerdict(id, installed) == Verdict.OK) id else DEFAULT_THEME_ID

        /**
         * The API level verdict (doc 04 section 7) for the theme [id] among the [installed] ones. The bundled default
         * theme always passes: it is the fallback and is built with this Pano. A theme with no readable manifest is
         * not judged here.
         */
        fun themeVerdict(id: String, installed: List<InstalledTheme>): Verdict {
            if (id == DEFAULT_THEME_ID) {
                return Verdict.OK
            }

            val theme = installed.find { it.id == id } ?: return Verdict.OK

            return ApiLevelGate.check(theme.apiLevel)
        }

        class LoadedUI(
            val id: String,
            val host: String,
            val port: Int,
            val process: Process,
            // Stored so the memory watchdog can respawn this UI on the same port if it grows too big.
            val uiFolder: String,
            val licenseJwt: String?
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
            val screenshots: List<String>,
            /**
             * Marks a theme as premium. Set by the theme's manifest.json at build time.
             * The host (LicenseManager + UIManager) refuses to start a premium theme without a
             * verifiable license from panomc.com; the theme itself performs an independent
             * RS256 signature check on the JWT it receives via env var. See [com.panomc.platform.license.LicenseManager.requireThemeLicense].
             */
            val premium: Boolean = false,
            /**
             * Cumulative SHA-256 of every file in the built theme (except manifest.json),
             * stamped here by the theme's vite postbuild plugin (`themeFingerprintPlugin`).
             * The host re-computes the hash when installing the theme and again every time
             * it starts the bun process, refusing to spawn a premium theme whose extracted
             * folder no longer matches what was shipped. See [com.panomc.platform.util.HashUtil.computeStableFileFingerprint].
             * Optional for backward compatibility with old free themes that ship without it.
             */
            val fileFingerprint: String? = null,
            /**
             * The extension contract level the theme needs (doc 04 section 7), stamped by the theme's build.
             * A theme without it reads as level 0, built before the cutover: the API level gate refuses it and
             * the bundled theme is served instead.
             */
            val apiLevel: Int = 0
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
            val screenshots: Map<String, String>,
            val hash: String,
            val createdAt: Long,
            val updatedAt: Long,
            val installedBy: InstalledBy,
            val premium: Boolean = false,
            val fileFingerprint: String? = null,
            val apiLevel: Int = 0
        )
    }
}