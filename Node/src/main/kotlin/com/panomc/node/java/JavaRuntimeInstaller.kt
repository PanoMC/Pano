package com.panomc.node.java

import com.google.gson.GsonBuilder
import com.panomc.node.host.HostPlatform
import com.panomc.node.host.JavaRuntime
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * What `<runtime>/.pano-managed.json` says: this directory was put here by the node, from where,
 * and which bytes. Its presence is the whole difference between a runtime the node may update
 * and remove and one an operator installed and the node must leave alone.
 */
data class ManagedRuntimeMarker(
    val vendor: String? = null,
    val major: Int? = null,
    val version: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val installedAt: Long? = null
) {
    companion object {
        const val FILE = ".pano-managed.json"
    }
}

/** A runtime of a major no source builds for this host. The message names the host. */
class JavaUnavailableException(val major: Int, message: String) : IllegalStateException(message)

/**
 * Downloads, verifies and installs Java runtimes into `<data>/java/` (SM-63, §2.4.28).
 *
 * The order is what makes a half-finished install harmless:
 *
 * 1. download into `<data>/cache/java/` and check the vendor's SHA-256 -- a mismatch deletes the
 *    file and fails, because a JRE is code every managed server will run;
 * 2. extract into `<data>/java/.tmp-<random>/`, which the locator never looks at (dot-prefixed);
 * 3. strip the single top-level directory both vendors wrap their archives in;
 * 4. write `.pano-managed.json` and run `bin/java -version` once, from the temporary place;
 * 5. only then rename the finished directory to `<data>/java/<vendor>-<version>/`.
 *
 * So the locator either sees a complete, verified runtime or nothing, and whatever a crash leaves
 * behind is a dot-directory [cleanup] removes on the next boot.
 *
 * One install per major at a time: a second request for Java 21 while Java 21 is downloading
 * joins the first (and is told its progress) instead of downloading the same 50 MB twice into the
 * same directory. Different majors run side by side.
 */
class JavaRuntimeInstaller(
    private val dataDir: File,
    private val resolver: JavaPackageResolver,
    private val locator: JavaRuntimeLocator,
    private val logger: NodeLogger,
    /** Fetches [JavaPackage.url] to a file and verifies its SHA-256; a seam so tests stay offline. */
    private val fetch: (pkg: JavaPackage, target: File, onFraction: (Double) -> Unit) -> Unit = { pkg, target, onFraction ->
        Downloader.download(pkg.url, target, sha256 = pkg.sha256, onProgress = onFraction)
    },
    /** Throws when the runtime at the given home cannot run; a seam because tests spawn nothing. */
    private val sanityCheck: (home: File) -> Unit = ::runJavaVersion
) {
    /** How one install ended. */
    data class Installed(
        val runtime: JavaRuntime,
        val pkg: JavaPackage?,
        /** True when nothing was downloaded because the newest version was already here. */
        val alreadyCurrent: Boolean
    )

    private class InFlight {
        val future = CompletableFuture<Installed>()
        val listeners = CopyOnWriteArrayList<(Int, String) -> Unit>()

        @Volatile
        var last: Pair<Int, String>? = null
    }

    private val inFlight = ConcurrentHashMap<Int, InFlight>()

    /** `<data>/java`, where every managed runtime lives and where the locator looks too. */
    val javaRoot: File = File(dataDir, "java")

    /** `<data>/cache/java`, where archives wait between download and extraction. */
    val cacheDir: File = File(File(dataDir, "cache"), "java")

    /** Whether an install of [major] is running right now. */
    fun isInstalling(major: Int): Boolean = inFlight.containsKey(major)

    /**
     * Makes sure the newest Java [major] the sources offer is installed, and returns it.
     *
     * [onProgress] gets 0..100 and a sentence; a caller that joined an install already running
     * gets that install's progress from where it is. Throws [JavaUnavailableException] when no
     * source builds this major for this host, and any other exception when the lookup, download,
     * checksum, extraction or sanity check failed.
     */
    fun install(major: Int, onProgress: (Int, String) -> Unit = { _, _ -> }): Installed {
        val mine = InFlight()
        val running = inFlight.putIfAbsent(major, mine)
        val flight = running ?: mine

        flight.listeners.add(onProgress)

        flight.last?.let { (percent, message) -> onProgress(percent, message) }

        if (running != null) {
            try {
                return running.future.get()
            } catch (exception: ExecutionException) {
                throw exception.cause ?: exception
            } finally {
                running.listeners.remove(onProgress)
            }
        }

        try {
            val result = doInstall(major) { percent, message ->
                mine.last = percent to message

                mine.listeners.forEach { listener ->
                    try {
                        listener(percent, message)
                    } catch (_: Exception) {
                    }
                }
            }

            mine.future.complete(result)

            return result
        } catch (exception: Throwable) {
            mine.future.completeExceptionally(exception)

            throw exception
        } finally {
            inFlight.remove(major, mine)
        }
    }

    private fun doInstall(major: Int, progress: (Int, String) -> Unit): Installed {
        progress(0, "Looking up Java $major")

        val pkg = resolver.resolve(major)
            ?: throw JavaUnavailableException(
                major,
                "Java $major is not available for ${resolver.host} from Temurin or Zulu"
            )

        val label = describe(pkg)

        // Already here, or something newer is: an update request for a runtime that is current is
        // answered without touching the network again.
        newestManaged(major)?.let { current ->
            if (!JavaVersionOrder.isNewer(pkg.version, current.version)) {
                return Installed(current, pkg, alreadyCurrent = true)
            }
        }

        val directoryName = directoryName(pkg)

        // A directory of that name without a marker is not ours; one with a marker is this exact
        // build, installed by a node that lost track of it (the locator did not list it because
        // it is broken). Either way the rename below needs the name free.
        val destination = PathSafety.resolveUnder(javaRoot, directoryName)

        javaRoot.mkdirs()
        cacheDir.mkdirs()

        val archive = PathSafety.resolveUnder(cacheDir, sanitise(pkg.fileName))
        val temporary = PathSafety.resolveUnder(javaRoot, TMP_PREFIX + UUID.randomUUID().toString().take(8))

        try {
            val downloading = "Downloading Java $major ($label)"

            progress(DOWNLOAD_START, downloading)

            archive.delete()

            fetch(pkg, archive) { fraction ->
                val percent = DOWNLOAD_START + (fraction.coerceIn(0.0, 1.0) * (DOWNLOAD_END - DOWNLOAD_START)).toInt()

                progress(percent, downloading)
            }

            // Checked again here rather than trusted to the seam: this is the promise §2.4.28 makes
            // (and the one a test of a tampered archive exercises).
            Downloader.verify(archive, sha256 = pkg.sha256)

            progress(EXTRACT_PERCENT, "Extracting Java $major ($label)")

            temporary.mkdirs()

            if (pkg.archive == JavaTarget.ZIP) {
                extractZip(archive, temporary)
            } else {
                TarGzExtractor.extract(archive, temporary)
            }

            val root = singleTopLevel(temporary)
            val home = homeOf(root)

            if (!File(File(home, "bin"), HostPlatform.javaExecutable).isFile) {
                throw IllegalStateException("The Java $major archive has no bin/${HostPlatform.javaExecutable}.")
            }

            makeExecutables(home)

            writeMarker(root, pkg)

            progress(CHECK_PERCENT, "Checking Java $major ($label)")

            sanityCheck(home)

            if (destination.exists()) {
                discard(destination)
            }

            moveIntoPlace(root, destination)

            logger.info("Installed Java $major ($label) into ${destination.absolutePath}.")

            val runtime = locator.inspectHome(homeOf(destination))
                ?: throw IllegalStateException("Java $major was installed but could not be read back.")

            return Installed(runtime, pkg, alreadyCurrent = false)
        } finally {
            archive.delete()

            if (temporary.exists()) {
                temporary.deleteRecursively()
            }
        }
    }

    /**
     * Deletes one managed runtime: renamed out of the way first, so the locator stops seeing it
     * at once, then deleted. Windows can hold a file for a moment after the last process using it
     * exited, so the delete is retried and a trash directory that still resists is left for the
     * next boot's [cleanup].
     */
    fun remove(runtimeDirectory: File) {
        discard(runtimeDirectory)
    }

    /**
     * The boot sweep: whatever an interrupted install or removal left behind, and managed runtimes
     * an update superseded that nothing was running from last time (§2.4.28 "removed at the next
     * boot when unused"). [inUse] answers for a runtime path whether a live server runs from it.
     *
     * Returns whether any runtime disappeared, so the caller knows to announce the new list.
     */
    fun cleanup(inUse: (String) -> Boolean = { false }): Boolean {
        javaRoot.listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith(TMP_PREFIX) || it.name.startsWith(TRASH_PREFIX)) }
            ?.forEach { leftover ->
                if (!leftover.deleteRecursively()) {
                    logger.warn("Could not delete ${leftover.absolutePath}; trying again on the next start.")
                }
            }

        cacheDir.listFiles()?.forEach { stale -> stale.deleteRecursively() }

        var removed = false

        superseded().forEach { runtime ->
            if (inUse(runtime.path)) {
                return@forEach
            }

            logger.info("Removing Java ${runtime.version} (${runtime.path}); a newer Java ${runtime.major} replaced it.")

            try {
                remove(runtimeDirectoryOf(runtime))

                removed = true
            } catch (exception: Exception) {
                logger.warn("Could not remove ${runtime.path}: ${exception.message}")
            }
        }

        return removed
    }

    /** Managed runtimes that are not the newest managed one of their major. */
    fun superseded(): List<JavaRuntime> = locator.discover()
        .filter { it.managed }
        .groupBy { it.major }
        .values
        .flatMap { sameMajor ->
            val newest = sameMajor.maxWithOrNull(compareBy(JavaVersionOrder) { it.version })

            sameMajor.filter { it !== newest }
        }

    /** The newest runtime of [major] this node installed itself, if any. */
    fun newestManaged(major: Int): JavaRuntime? = locator.discover()
        .filter { it.managed && it.major == major }
        .maxWithOrNull(compareBy(JavaVersionOrder) { it.version })

    /**
     * The directory that holds [runtime] as a whole: its home, or the bundle two levels above a
     * macOS `Contents/Home`. That is what gets renamed and deleted, never just the home inside it.
     */
    fun runtimeDirectoryOf(runtime: JavaRuntime): File {
        val home = File(runtime.path)

        return if (home.name == "Home" && home.parentFile?.name == "Contents") {
            home.parentFile.parentFile
        } else {
            home
        }
    }

    private fun discard(directory: File) {
        if (!directory.exists()) {
            return
        }

        val trash = File(javaRoot, TRASH_PREFIX + UUID.randomUUID().toString().take(8))

        javaRoot.mkdirs()

        val target = try {
            Files.move(directory.toPath(), trash.toPath(), StandardCopyOption.ATOMIC_MOVE)

            trash
        } catch (_: Exception) {
            // Another file system, or Windows refusing the rename of a directory in use: delete in
            // place, which is what the rename was only there to make instant.
            directory
        }

        repeat(DELETE_ATTEMPTS) { attempt ->
            if (target.deleteRecursively() || !target.exists()) {
                return
            }

            if (attempt < DELETE_ATTEMPTS - 1) {
                Thread.sleep(DELETE_RETRY_MILLIS)
            }
        }

        if (target == directory) {
            throw IllegalStateException("Could not delete ${directory.absolutePath}.")
        }

        logger.warn("Could not delete ${target.absolutePath} yet; it is removed on the next start.")
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun writeMarker(root: File, pkg: JavaPackage) {
        val marker = ManagedRuntimeMarker(
            vendor = pkg.vendor,
            major = pkg.major,
            version = pkg.version,
            url = pkg.url,
            sha256 = pkg.sha256,
            installedAt = System.currentTimeMillis()
        )

        File(root, ManagedRuntimeMarker.FILE).writeText(gson.toJson(marker))
    }

    /** Unpacks a Windows JRE zip, every entry through the same path checks the tar reader uses. */
    private fun extractZip(archive: File, target: File) {
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val relative = TarGzExtractor.entryPath(entry.name) ?: return@forEach

                val destination = PathSafety.resolveRelative(target, relative)

                if (entry.isDirectory) {
                    destination.mkdirs()

                    return@forEach
                }

                destination.parentFile?.mkdirs()

                zip.getInputStream(entry).use { input ->
                    Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    companion object {
        const val TMP_PREFIX = ".tmp-"
        const val TRASH_PREFIX = ".trash-"

        private const val DOWNLOAD_START = 2
        private const val DOWNLOAD_END = 85
        private const val EXTRACT_PERCENT = 87
        private const val CHECK_PERCENT = 95

        private const val DELETE_ATTEMPTS = 5
        private const val DELETE_RETRY_MILLIS = 400L

        private const val SANITY_TIMEOUT_SECONDS = 10L

        private val gson = GsonBuilder().setPrettyPrinting().create()

        /** `Temurin 21.0.12.1+1`: what a task message and a console line call a package. */
        fun describe(pkg: JavaPackage): String =
            "${pkg.vendor.replaceFirstChar { it.uppercase() }} ${pkg.version}"

        /** `<vendor>-<version>`, with anything outside `[A-Za-z0-9._+-]` replaced by `_`. */
        fun directoryName(pkg: JavaPackage): String = sanitise("${pkg.vendor}-${pkg.version}")

        fun sanitise(value: String): String {
            val cleaned = value.replace(Regex("[^A-Za-z0-9._+-]"), "_").replace("..", "_")

            return cleaned.trimStart('.').ifEmpty { "runtime" }
        }

        /** The archive's own top directory when it has exactly one entry and that is a directory. */
        fun singleTopLevel(directory: File): File {
            val children = directory.listFiles().orEmpty()

            return children.singleOrNull()?.takeIf { it.isDirectory } ?: directory
        }

        /** Where `bin/java` is: the directory itself, or `Contents/Home` inside a macOS bundle. */
        fun homeOf(root: File): File {
            val bundled = File(File(root, "Contents"), "Home")

            return if (bundled.isDirectory) bundled else root
        }

        /**
         * Sets the execute bits the JRE cannot run without, whatever the archive said: every file
         * in `bin` and `lib/jspawnhelper` (which the JDK forks every child process through). A zip
         * has no modes at all, so on a Unix host this is the only thing making a zip install work.
         */
        fun makeExecutables(home: File) {
            if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            val executables = File(home, "bin").listFiles().orEmpty().filter { it.isFile } +
                listOf(File(File(home, "lib"), "jspawnhelper")).filter { it.isFile }

            executables.forEach { file ->
                try {
                    val permissions = Files.getPosixFilePermissions(file.toPath()).toMutableSet()

                    permissions.add(PosixFilePermission.OWNER_EXECUTE)
                    permissions.add(PosixFilePermission.OWNER_READ)

                    if (PosixFilePermission.GROUP_READ in permissions) {
                        permissions.add(PosixFilePermission.GROUP_EXECUTE)
                    }

                    if (PosixFilePermission.OTHERS_READ in permissions) {
                        permissions.add(PosixFilePermission.OTHERS_EXECUTE)
                    }

                    Files.setPosixFilePermissions(file.toPath(), permissions)
                } catch (_: Exception) {
                }
            }
        }

        /**
         * `<home>/bin/java -version`, which has to exit 0 within ten seconds.
         *
         * The one check a checksum cannot make: the archive can be exactly what the vendor
         * published and still be the wrong one for this host (a glibc build on musl, an x64 build
         * on an ARM host without emulation), and that fails here rather than at the first start.
         */
        fun runJavaVersion(home: File) {
            val java = File(File(home, "bin"), HostPlatform.javaExecutable)

            val process = ProcessBuilder(java.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()

            val drain = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (output.length < 4096) output.append(line).append('\n')
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }

            drain.start()

            if (!process.waitFor(SANITY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()

                throw IllegalStateException("The downloaded Java did not answer `java -version` within ${SANITY_TIMEOUT_SECONDS}s.")
            }

            drain.join(1_000)

            if (process.exitValue() != 0) {
                val said = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200)

                throw IllegalStateException(
                    "The downloaded Java does not run on this host (exit ${process.exitValue()}" +
                        (said?.let { ": $it" } ?: "") + ")."
                )
            }
        }
    }
}
