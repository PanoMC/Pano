package com.panomc.node.task

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaRuntimeService
import com.panomc.node.server.MinecraftJavaVersions
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Puts the mod loader a modpack asks for into a server directory.
 *
 * A `.mrpack` is not a server: it is a list of mods and a line saying "this needs Fabric 0.16.5 on
 * 1.21.1". Something has to turn that line into a launchable jar, and it has to be this side,
 * because the answer depends on what the loader's own infrastructure is serving today and on a JVM
 * being available to run an installer.
 *
 * Two shapes, because the loaders genuinely differ. Fabric publishes a ready-made server jar per
 * (game, loader) pair, so that is a download and nothing more. Forge, NeoForge and Quilt ship an
 * installer that has to be *run* in the directory, which means finding a Java, spawning a process
 * and waiting on it — with a timeout, because an installer that hangs on a dead maven would
 * otherwise hang an import forever.
 */
class LoaderInstaller(
    private val javaLocator: JavaRuntimeLocator,
    private val logger: NodeLogger,
    /** Downloads the Java an installer needs when this host lacks it (SM-63). */
    private val javaService: JavaRuntimeService? = null
) {
    /** What the loader install produced. */
    data class Result(
        /** The jar to launch, relative to the server directory, or null when it left none. */
        val jar: String?,
        val software: String
    )

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Installs [loader] [loaderVersion] for [minecraftVersion] into [directory].
     *
     * Throws with a sentence a person can act on rather than returning null: a modpack whose
     * loader could not be installed is not a server, and reporting that as a failed import is more
     * useful than a directory full of mods nothing can load.
     */
    fun install(
        directory: File,
        loader: String,
        loaderVersion: String?,
        minecraftVersion: String?,
        onProgress: (Int, String) -> Unit
    ): Result = when (loader) {
        "fabric-loader" -> installFabric(directory, loaderVersion, minecraftVersion, onProgress)
        "quilt-loader" -> installQuilt(directory, loaderVersion, minecraftVersion, onProgress)
        "forge" -> installForge(directory, loaderVersion, minecraftVersion, onProgress)
        "neoforge" -> installNeoForge(directory, loaderVersion, onProgress)
        else -> throw IllegalStateException("This pack needs \"$loader\", which Pano cannot install.")
    }

    /**
     * Fabric's meta API serves a complete server launcher per (game, loader), so there is nothing
     * to run: the jar it hands back is the server.
     */
    private fun installFabric(
        directory: File,
        loaderVersion: String?,
        minecraftVersion: String?,
        onProgress: (Int, String) -> Unit
    ): Result {
        val game = minecraftVersion ?: throw IllegalStateException("The pack did not say which Minecraft it needs.")
        val version = loaderVersion ?: latestFabricLoader(game)

        val installer = latestFabricInstaller()

        val url = "$FABRIC_META/versions/loader/${encode(game)}/${encode(version)}/${encode(installer)}/server/jar"

        onProgress(LOADER_START_PERCENT, "Installing Fabric $version")

        Downloader.download(url, File(directory, SERVER_JAR)) { }

        return Result(SERVER_JAR, "fabric")
    }

    /** Quilt ships an installer jar; `install server` writes the launcher into the directory. */
    private fun installQuilt(
        directory: File,
        loaderVersion: String?,
        minecraftVersion: String?,
        onProgress: (Int, String) -> Unit
    ): Result {
        val game = minecraftVersion ?: throw IllegalStateException("The pack did not say which Minecraft it needs.")

        val installerUrl = latestQuiltInstaller()

        onProgress(LOADER_START_PERCENT, "Installing Quilt")

        val installer = File(directory, "quilt-installer.jar")

        Downloader.download(installerUrl, installer) { }

        val arguments = mutableListOf("install", "server", game)

        loaderVersion?.let { arguments.add(it) }

        arguments.add("--download-server")
        arguments.add("--install-dir=.")

        run(directory, installer, arguments, game, onProgress, "Quilt")

        installer.delete()

        // Quilt's launcher is named after what it launches, so the inspection that follows finds
        // it; nothing here has to guess at the name.
        return Result(null, "quilt")
    }

    private fun installForge(
        directory: File,
        loaderVersion: String?,
        minecraftVersion: String?,
        onProgress: (Int, String) -> Unit
    ): Result {
        val game = minecraftVersion ?: throw IllegalStateException("The pack did not say which Minecraft it needs.")
        val version = loaderVersion ?: throw IllegalStateException("The pack did not say which Forge it needs.")

        // Forge's own artifacts are versioned `<game>-<forge>`, and packs spell the dependency
        // either way round, so both are accepted.
        val full = if (version.startsWith("$game-")) version else "$game-$version"

        val url = "$FORGE_MAVEN/net/minecraftforge/forge/$full/forge-$full-installer.jar"

        runInstaller(directory, url, game, "Forge $version", onProgress)

        return Result(null, "forge")
    }

    private fun installNeoForge(
        directory: File,
        loaderVersion: String?,
        onProgress: (Int, String) -> Unit
    ): Result {
        val version = loaderVersion ?: throw IllegalStateException("The pack did not say which NeoForge it needs.")

        val url = "$NEOFORGE_MAVEN/net/neoforged/neoforge/$version/neoforge-$version-installer.jar"

        runInstaller(directory, url, null, "NeoForge $version", onProgress)

        return Result(null, "neoforge")
    }

    /** Downloads an installer jar and runs it with `--installServer`, then removes it. */
    private fun runInstaller(
        directory: File,
        url: String,
        minecraftVersion: String?,
        label: String,
        onProgress: (Int, String) -> Unit
    ) {
        onProgress(LOADER_START_PERCENT, "Downloading $label")

        val installer = File(directory, "installer.jar")

        Downloader.download(url, installer) { }

        if (!Downloader.isZip(installer)) {
            installer.delete()

            throw IllegalStateException("$label does not publish an installer at that version.")
        }

        run(directory, installer, listOf("--installServer"), minecraftVersion, onProgress, label)

        installer.delete()

        // The installer writes its own log next to the jar; it is noise in a server directory.
        File(directory, "installer.jar.log").delete()
    }

    private fun run(
        directory: File,
        installer: File,
        arguments: List<String>,
        minecraftVersion: String?,
        onProgress: (Int, String) -> Unit,
        label: String
    ) {
        val java = resolveJava(minecraftVersion, onProgress)

        onProgress(LOADER_RUN_PERCENT, "Running the $label installer")

        val command = mutableListOf(java.absolutePath, "-jar", installer.absolutePath)

        command.addAll(arguments)

        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .start()

        // Drained on its own thread, for two reasons: an installer that fills its pipe blocks
        // forever if nobody reads it, and reading it on this thread would mean waiting for EOF —
        // which is process exit — and so would make the timeout below unreachable.
        val output = StringBuilder()

        val drain = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (output.length < MAX_OUTPUT) {
                        output.append(line).append('\n')
                    }
                }
            } catch (_: Exception) {
                // The stream dies with the process; there is nothing left to read or to say.
            }
        }

        drain.isDaemon = true
        drain.start()

        if (!process.waitFor(INSTALLER_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()

            throw IllegalStateException("The $label installer did not finish within $INSTALLER_TIMEOUT_MINUTES minutes.")
        }

        drain.join(DRAIN_JOIN_MS)

        if (process.exitValue() != 0) {
            logger.warn("The $label installer exited ${process.exitValue()}: $output")

            throw IllegalStateException("The $label installer failed with exit code ${process.exitValue()}.")
        }
    }

    private fun resolveJava(minecraftVersion: String?, onProgress: (Int, String) -> Unit): File {
        // The version's own minimum is fetched first when it is missing (SM-63); a Forge installer
        // for 1.12 is exactly the kind of thing that refuses anything but Java 8.
        if (javaService?.enabled == true) {
            val needed = MinecraftJavaVersions.minimumFor(minecraftVersion)

            val outcome = javaService.ensure(needed) { _, message -> onProgress(LOADER_RUN_PERCENT, message) }

            if (outcome is JavaRuntimeService.Outcome.Failed) {
                logger.warn("Could not download Java $needed for the loader installer: ${outcome.message}")
            }
        }

        val runtime = javaLocator.resolveFor(minecraftVersion)
            ?: javaLocator.resolve(MinecraftJavaVersions.MODERN_JAVA)
            ?: javaLocator.discover().maxByOrNull { it.major }
            ?: throw IllegalStateException("No Java runtime on this host can run a loader installer.")

        return javaLocator.launcher(runtime)
    }

    private fun latestFabricLoader(game: String): String {
        val body = getJsonArray("$FABRIC_META/versions/loader/${encode(game)}")

        val entry = body?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.getJsonObject("loader")?.getBoolean("stable", false) == true }
            ?: body?.mapNotNull { it as? JsonObject }?.firstOrNull()

        return entry?.getJsonObject("loader")?.getString("version")
            ?: throw IllegalStateException("Fabric publishes no loader for $game.")
    }

    private fun latestFabricInstaller(): String {
        val body = getJsonArray("$FABRIC_META/versions/installer")

        val entry = body?.mapNotNull { it as? JsonObject }?.firstOrNull { it.getBoolean("stable", false) }
            ?: body?.mapNotNull { it as? JsonObject }?.firstOrNull()

        return entry?.getString("version") ?: throw IllegalStateException("Fabric publishes no installer.")
    }

    private fun latestQuiltInstaller(): String {
        val body = getJsonArray("$QUILT_META/versions/installer")

        val entry = body?.mapNotNull { it as? JsonObject }?.firstOrNull()

        return entry?.getString("url") ?: throw IllegalStateException("Quilt publishes no installer.")
    }

    private fun getJsonArray(url: String): JsonArray? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "pano-node")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            return null
        }

        return try {
            JsonArray(response.body())
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val FABRIC_META = "https://meta.fabricmc.net/v2"
        const val QUILT_META = "https://meta.quiltmc.org/v3"
        const val FORGE_MAVEN = "https://maven.minecraftforge.net"
        const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"

        const val SERVER_JAR = "server.jar"

        const val LOADER_START_PERCENT = 60
        const val LOADER_RUN_PERCENT = 70

        private const val INSTALLER_TIMEOUT_MINUTES = 15L
        private const val MAX_OUTPUT = 4000
        private const val DRAIN_JOIN_MS = 2000L
    }
}
