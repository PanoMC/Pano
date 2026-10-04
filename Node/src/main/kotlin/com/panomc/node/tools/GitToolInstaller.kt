package com.panomc.node.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.node.java.JavaRuntimeInstaller
import com.panomc.node.java.JavaTarget
import com.panomc.node.java.JavaVersionOrder
import com.panomc.node.java.TarGzExtractor
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
import java.util.concurrent.TimeUnit

/**
 * What `<tool>/.pano-managed.json` says about a git this node downloaded: the release it came
 * from, the exact bytes, and the version the binary reported. Its presence is what separates a
 * git the node may use and replace from a directory somebody else put there.
 */
data class ManagedToolMarker(
    val tool: String? = null,
    val version: String? = null,
    val release: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val installedAt: Long? = null
) {
    companion object {
        const val FILE = ".pano-managed.json"
    }
}

/** A git this node installed under `<data>/tools/git/`, ready to be put on a `PATH`. */
data class ManagedGit(
    /** The tree's root: `bin/git`, `libexec/git-core/`, `share/git-core/templates/`. */
    val home: File,
    /** What `git --version` said, e.g. `2.55.0`. */
    val version: String
) {
    /** The one directory a process needs on its `PATH` to find this git. */
    val binDir: File get() = File(home, "bin")
}

/** No release publishes a portable git for this host (Windows, 32-bit ARM, …). */
class GitUnavailableException(message: String) : IllegalStateException(message)

/**
 * Downloads, verifies and installs a portable git into `<data>/tools/git/` for BuildTools
 * (SM-67, §2.4.32), with the same guarantees as the Java installer it borrows its parts from
 * ([JavaRuntimeInstaller], [TarGzExtractor], [Downloader]):
 *
 * 1. download into `<data>/cache/tools/` and check the published SHA-256 -- a mismatch deletes the
 *    file and fails, because this is an executable every Spigot build runs;
 * 2. extract into `<data>/tools/git/.tmp-<random>/` (dot-prefixed, so [installed] never sees it)
 *    through the pure-JVM tar reader and its path checks, and strip the one top-level directory;
 * 3. run `bin/git --version` from there, which both proves the binary runs on this host and names
 *    the version;
 * 4. write the marker, then rename the tree to `<data>/tools/git/<os>-<arch>-<version>/`.
 *
 * Nothing outside the data directory is touched: the system's git, PATH and config stay as they
 * were, and the tree is only ever put on the PATH of the BuildTools process itself
 * ([pathWith]). Installs are serialised -- there is one git per node, and BuildTools builds are
 * one at a time anyway.
 */
class GitToolInstaller(
    private val dataDir: File,
    private val resolver: GitToolResolver,
    private val logger: NodeLogger,
    private val target: JavaTarget = JavaTarget.current,
    /** Fetches [GitPackage.url] to a file and verifies it; a seam so tests stay offline. */
    private val fetch: (pkg: GitPackage, target: File, onFraction: (Double) -> Unit) -> Unit = { pkg, file, onFraction ->
        Downloader.download(pkg.url, file, sha256 = pkg.sha256, onProgress = onFraction)
    },
    /**
     * Runs `<home>/bin/git --version` and returns the version it printed, throwing when it does
     * not run. A seam because tests spawn nothing.
     */
    private val probe: (home: File) -> String = ::runGitVersion
) {
    /** `<data>/tools/git`, where every managed git lives. */
    val gitRoot: File = File(File(dataDir, "tools"), "git")

    /** `<data>/cache/tools`, where a tarball waits between download and extraction. */
    val cacheDir: File = File(File(dataDir, "cache"), "tools")

    private val lock = Any()

    /**
     * The newest complete git this node installed for this host, or null. Only directories named
     * for this os/arch, carrying the marker and an executable `bin/git`, count: a data directory
     * copied from another machine does not hand this one a binary it cannot run.
     */
    fun installed(): ManagedGit? {
        val prefix = "${target.os}-${target.arch}-"

        return gitRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(prefix) }
            .mapNotNull { directory ->
                val marker = readMarker(directory) ?: return@mapNotNull null
                val git = File(File(directory, "bin"), GIT)

                if (!git.isFile || !git.canExecute()) {
                    return@mapNotNull null
                }

                ManagedGit(directory, marker.version ?: directory.name.removePrefix(prefix))
            }
            .maxWithOrNull(compareBy(JavaVersionOrder) { it.version })
    }

    /**
     * The managed git, installing it first when there is none. [onProgress] gets 0..100 of this
     * install alone and a sentence; the caller scales it into its own task.
     *
     * Throws [GitUnavailableException] when no release builds git for this host, and any other
     * exception when the lookup, download, checksum, extraction or `git --version` failed.
     */
    fun ensure(onProgress: (Int, String) -> Unit = { _, _ -> }): ManagedGit = synchronized(lock) {
        installed()?.let { return it }

        doInstall(onProgress)
    }

    private fun doInstall(progress: (Int, String) -> Unit): ManagedGit {
        progress(0, "Looking up a portable git for $target")

        val pkg = resolver.resolve()
            ?: throw GitUnavailableException("No portable git is published for $target")

        gitRoot.mkdirs()
        cacheDir.mkdirs()

        cleanup()

        val archive = PathSafety.resolveUnder(cacheDir, JavaRuntimeInstaller.sanitise(pkg.fileName))
        val temporary = PathSafety.resolveUnder(gitRoot, TMP_PREFIX + UUID.randomUUID().toString().take(8))

        try {
            val downloading = "Downloading git (${pkg.fileName}, ${pkg.release})"

            progress(DOWNLOAD_START, downloading)

            archive.delete()

            fetch(pkg, archive) { fraction ->
                val percent = DOWNLOAD_START + (fraction.coerceIn(0.0, 1.0) * (DOWNLOAD_END - DOWNLOAD_START)).toInt()

                progress(percent, downloading)
            }

            // Checked here as well as in the seam: the checksum is the promise, not the downloader.
            Downloader.verify(archive, sha256 = pkg.sha256)

            progress(EXTRACT_PERCENT, "Extracting git")

            temporary.mkdirs()

            TarGzExtractor.extract(archive, temporary)

            val root = JavaRuntimeInstaller.singleTopLevel(temporary)

            if (!File(File(root, "bin"), GIT).isFile) {
                throw IllegalStateException("The git archive has no bin/$GIT.")
            }

            makeExecutables(root)

            progress(CHECK_PERCENT, "Checking git")

            val version = probe(root)

            writeMarker(root, pkg, version)

            val destination = PathSafety.resolveUnder(
                gitRoot,
                JavaRuntimeInstaller.sanitise("${target.os}-${target.arch}-$version")
            )

            if (destination.exists()) {
                destination.deleteRecursively()
            }

            moveIntoPlace(root, destination)

            logger.info("Installed git $version (${pkg.release}) into ${destination.absolutePath}.")

            return ManagedGit(destination, version)
        } finally {
            archive.delete()

            if (temporary.exists()) {
                temporary.deleteRecursively()
            }
        }
    }

    /** Whatever an interrupted install left: `.tmp-*` trees and archives in the cache. */
    fun cleanup() {
        gitRoot.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(TMP_PREFIX) }
            ?.forEach { it.deleteRecursively() }

        cacheDir.listFiles()
            ?.filter { it.name.startsWith(ASSET_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun readMarker(directory: File): ManagedToolMarker? {
        val file = File(directory, ManagedToolMarker.FILE)

        if (!file.isFile) {
            return null
        }

        return try {
            Gson().fromJson(file.readText(), ManagedToolMarker::class.java)?.takeIf { it.tool == TOOL }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMarker(root: File, pkg: GitPackage, version: String) {
        val marker = ManagedToolMarker(
            tool = TOOL,
            version = version,
            release = pkg.release,
            url = pkg.url,
            sha256 = pkg.sha256,
            installedAt = System.currentTimeMillis()
        )

        File(root, ManagedToolMarker.FILE).writeText(gson.toJson(marker))
    }

    private fun moveIntoPlace(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    companion object {
        const val TOOL = "git"
        const val GIT = "git"

        const val TMP_PREFIX = ".tmp-"

        /** Every release asset of this kind starts with it; the cache sweep deletes only those. */
        const val ASSET_PREFIX = "pano-git-"

        private const val DOWNLOAD_START = 2
        private const val DOWNLOAD_END = 85
        private const val EXTRACT_PERCENT = 87
        private const val CHECK_PERCENT = 95

        private const val PROBE_TIMEOUT_SECONDS = 10L

        private val gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * [current] with [directories] in front, joined by [separator].
         *
         * Front, so the managed git wins over anything later on the PATH; the rest of the PATH is
         * kept, because BuildTools' patch script needs the host's `sh`, `sed` and friends. A
         * blank or missing PATH gives just the new directories rather than a trailing separator,
         * which some shells read as "the current directory".
         */
        fun pathWith(directories: List<File>, current: String?, separator: String = File.pathSeparator): String {
            val front = directories.map { it.absolutePath }

            val rest = current
                ?.split(separator)
                ?.filter { it.isNotEmpty() && it !in front }
                .orEmpty()

            return (front + rest).joinToString(separator)
        }

        /** `git version 2.55.0` → `2.55.0`; anything else is not a git that ran. */
        fun parseVersion(output: String): String? =
            Regex("git version (\\S+)").find(output)?.groupValues?.get(1)

        /**
         * Sets the execute bits a git tree cannot work without, whatever the archive said:
         * `bin/` and `libexec/git-core/` (where `git am` and friends find their helpers).
         */
        fun makeExecutables(home: File) {
            if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                return
            }

            val executables: List<File> = File(home, "bin").listFiles().orEmpty().toList() +
                File(File(home, "libexec"), "git-core").listFiles().orEmpty().toList()

            executables
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                    try {
                        val permissions = Files.getPosixFilePermissions(file.toPath()).toMutableSet()

                        permissions.add(PosixFilePermission.OWNER_READ)
                        permissions.add(PosixFilePermission.OWNER_EXECUTE)

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
         * `<home>/bin/git --version`, which has to exit 0 within ten seconds and name a version.
         *
         * The check a checksum cannot make: the right bytes can still be the wrong binary for this
         * host (an arm64 build on an x64 machine), and that fails here rather than ten minutes
         * into a Spigot build.
         */
        fun runGitVersion(home: File): String {
            val git = File(File(home, "bin"), GIT)

            val process = ProcessBuilder(git.absolutePath, "--version")
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

            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()

                throw IllegalStateException("The downloaded git did not answer `git --version` within ${PROBE_TIMEOUT_SECONDS}s.")
            }

            drain.join(1_000)

            val said = output.toString()

            if (process.exitValue() != 0) {
                throw IllegalStateException(
                    "The downloaded git does not run on this host (exit ${process.exitValue()}: " +
                        said.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(200) + ")."
                )
            }

            return parseVersion(said)
                ?: throw IllegalStateException("The downloaded git printed no version: ${said.trim().take(200)}")
        }
    }
}
