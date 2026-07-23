package com.panomc.platform

import com.google.gson.GsonBuilder
import com.panomc.platform.AppConstants.DEFAULT_THEME_ID
import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.license.LicenseDeniedReason
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.LicenseRequiredException
import com.panomc.platform.license.ThemeLicenseFailure
import com.panomc.platform.model.Route
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.HashUtil.computeStableFileFingerprint
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.OperatingSystem
import com.panomc.platform.util.adapter.StrictNotNullTypeAdapterFactory
import com.panomc.platform.util.annotation.StrictValidation
import io.vertx.core.Future
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.proxy.handler.ProxyHandler
import io.vertx.httpproxy.HttpProxy
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

    /**
     * Lazy to break the construction cycle: [LicenseManager] also looks up [UIManager] lazily
     * for the active-premium-theme fallback callback ([fallbackToDefaultThemeBecausePremiumLicenseLost]).
     */
    private val licenseManager: LicenseManager by lazy {
        applicationContext.getBean(LicenseManager::class.java)
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

    private val githubUrl = "https://github.com"

    private val bunVersion = "bun-v1.3.9"
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
            logger.error("Couldn't download Bun runtime: {}", e.message)
            System.exit(1)
            return
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

            System.exit(1)
            return Optional.empty()
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
            themeManifest.fileFingerprint
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
        environment["HOST"] = serverHost
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
        environment["API_URL"] = "http://${apiHost}:${serverPort}/api"
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

        val startedUI = LoadedUI(id, serverHost, port, process, uiFolder, licenseJwt)

        startedUIList.add(startedUI)

        // `process.isAlive` only means bun forked — the SvelteKit HTTP listener binds a beat
        // later. Routing to the port before that yields a 502 window at boot and after a
        // memory-watchdog restart, so hold "started" until the upstream actually answers.
        waitUntilUiResponds(id, serverHost, port)

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
    suspend fun startUI(id: String, port: Int = findAvailablePort()) {
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

        val theme = if (currentThemeValid) currentTheme else DEFAULT_THEME_ID

        activeTheme = theme

        // Populate installedThemeList BEFORE attempting to start a premium theme, otherwise
        // startUI() can't see the manifest and treats it as a free theme (skipping the
        // license check).
        reloadInstalledThemes()

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
                    config.currentTheme = DEFAULT_THEME_ID
                    try {
                        configManager.saveConfig()
                    } catch (t: Throwable) {
                        logger.error("Failed to persist fallback theme at boot: {}", t.message, t)
                    }
                    startUIBlocking(DEFAULT_THEME_ID)
                }
            } catch (e: Exception) {
                logger.error("Failed to start UI.", e)

                System.exit(1)
                return
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
            val path = proxiedRequest.path() ?: ""

            val contentType = response.headers().get("Content-Type")
            val isSuccess = response.statusCode in 200..299

            when {
                contentType != null && contentType.contains("text/html", ignoreCase = true) ->
                    response.putHeader("Cache-Control", "no-cache")

                LIB_HASHED_PATH_REGEX.containsMatchIn(path) -> {
                    if (isSuccess) {
                        response.putHeader("Cache-Control", IMMUTABLE_CACHE_CONTROL)
                    }
                }

                RUNTIME_PATH_REGEX.containsMatchIn(path) -> {
                    if (isSuccess && proxiedRequest.getParam("v") != null) {
                        response.putHeader("Cache-Control", IMMUTABLE_CACHE_CONTROL)
                    } else if (response.statusCode != 304) {
                        // A bare 304 must stay header-less: per RFC 9111 clients freshen the
                        // stored response with any headers on the 304, so stamping no-cache
                        // here would permanently downgrade a correctly-immutable cache entry.
                        response.putHeader("Cache-Control", "no-cache")
                    }
                }
            }

            return context.sendResponse()
        }
    }

    fun activateSetupUI(router: Router) {
        if (_activatedUIList.containsKey(Route.Type.SETUP_UI)) {
            return
        }

        val setupUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)
        setupUI.addInterceptor(uiCacheControlInterceptor)

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

        val panelUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)
        panelUI.addInterceptor(uiCacheControlInterceptor)

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

                    if (context.response().closed()) {
                        return@launch
                    }

                    if (isLoggedIn) {
                        authProvider.applyPermissionsTo(context)

                        val hasAccessPanel = authProvider.hasAccessPanel(context)

                        if (hasAccessPanel) {
                            request.resume()
                            panelUIHandler.handle(context)

                            return@launch
                        }
                    }

                    request.resume()
                    // Theme UI may be transiently unbound while an admin is switching themes
                    // (route gets disabled, new theme bun process starts, route gets re-bound).
                    // A panel request landing in that window used to NPE on `!!`; serve a 503
                    // briefly instead so the client can retry.
                    val themeUi = _activatedUIList[Route.Type.THEME_UI]
                    if (themeUi == null) {
                        context.response()
                            .setStatusCode(503)
                            .putHeader("retry-after", "1")
                            .end("Theme UI is being switched; retry shortly.")
                    } else {
                        themeUi.proxyHandler.handle(context)
                    }
                }
            }
            .failureHandler { it.failure().printStackTrace() }

        _activatedUIList[Route.Type.PANEL_UI] = ActivatedUI(port, serverHost, panelUIHandler)
    }


    fun activateThemeUI(router: Router, id: String) {
        if (_activatedUIList.containsKey(Route.Type.THEME_UI)) {
            return
        }

        val themeUI = HttpProxy.reverseProxy(ProxyOptions().setSupportWebSocket(true), httpClient)
        themeUI.addInterceptor(uiCacheControlInterceptor)

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
            stopUI("setup-ui")

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

        private const val UI_READINESS_TIMEOUT_MS = 20_000L
        private const val UI_READINESS_POLL_INTERVAL_MS = 250L

        private const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"

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
            val fileFingerprint: String? = null
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
            val fileFingerprint: String? = null
        )
    }
}