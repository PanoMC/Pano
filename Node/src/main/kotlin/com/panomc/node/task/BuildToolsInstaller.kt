package com.panomc.node.task

import com.panomc.node.host.HostPlatform
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaRuntimeService
import com.panomc.node.tools.GitToolInstaller
import com.panomc.node.tools.GitUnavailableException
import com.panomc.node.util.Downloader
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Compiles a Spigot server jar on this host, because nobody is allowed to hand you one.
 *
 * SpigotMC may not redistribute CraftBukkit or Spigot, so there is no download URL for Pano to
 * resolve: the only lawful install is to run their BuildTools here, which clones Mojang's server,
 * applies the Spigot patches and compiles the result. That takes about ten minutes the first time
 * and produces a jar that is byte-for-byte the same on every host, which is exactly why the output
 * is cached per revision — the second server on this node gets a file copy.
 *
 * Three things make this different from every other install step and shape everything below:
 * - it needs `git` and a JDK of the *right vintage*, so both are sorted out before anything
 *   long-running starts: a missing JDK is downloaded (SM-63) and so is a missing git (SM-67, see
 *   [prepareGit]) -- into the node's data directory, never onto the system;
 * - it is minutes of output rather than a percentage, so the log is streamed as progress and the
 *   percent is a coarse phase marker;
 * - it is the heaviest thing this node will ever do, so only one runs at a time.
 *
 * Under the Docker runtime the build still happens here, on the host, with the host's git and
 * JDK (or the node's own) — only the finished jar ends up inside a container.
 */
class BuildToolsInstaller(
    private val dataDir: File,
    private val javaLocator: JavaRuntimeLocator,
    private val logger: NodeLogger,
    private val runner: Runner = SystemRunner(),
    /** Downloads the JDK the build asks for when this host lacks it (SM-63). */
    private val javaService: JavaRuntimeService? = null,
    /**
     * The node's own portable git, for a Linux or macOS host without one (SM-67). Null keeps the
     * pre-SM-67 behaviour: no git on the PATH is a failed install naming the package to install.
     */
    private val gitInstaller: GitToolInstaller? = null,
    /** Whether a missing tool may be downloaded right now (`node.tool-auto-download`). */
    private val toolAutoDownload: () -> Boolean = { true },
    /** Whether this host is Windows, where BuildTools provisions its own git (see [prepareGit]). */
    private val windows: Boolean = HostPlatform.isWindows,
    /** The daemon's own `PATH`, which the managed git is put in front of for the build only. */
    private val hostPath: () -> String? = { System.getenv("PATH") }
) {
    /**
     * Everything the installer does that leaves the JVM, behind one seam.
     *
     * Not an abstraction for its own sake: a test that actually ran BuildTools would clone three
     * git repositories and compile Minecraft, so the process, the download and the `git` probe are
     * the three things a test replaces to exercise the rest for real.
     */
    interface Runner {
        /**
         * Whether `git` is on the daemon's PATH. A system git always wins: when this is true
         * nothing is downloaded and the build's PATH is left alone.
         */
        fun hasGit(): Boolean

        /** Downloads the tool jar. Separate from the build so a test never reaches the network. */
        fun download(url: String, target: File)

        /**
         * Runs [command] in [directory] and feeds every stdout and stderr line to [onLine].
         *
         * [onHeartbeat] is called about every [HEARTBEAT_SECONDS] seconds whether or not the
         * process said anything, which is what keeps a task Pano would otherwise time out alive
         * through a silent Maven download. Returns the exit code; throws when [timeoutMinutes]
         * pass without the process finishing.
         */
        fun run(
            command: List<String>,
            directory: File,
            environment: Map<String, String>,
            timeoutMinutes: Long,
            onLine: (String) -> Unit,
            onHeartbeat: () -> Unit
        ): Int
    }

    /**
     * Puts a Spigot [rev] jar into [serverDirectory] as `server.jar`, building it if it has to.
     *
     * Throws with a sentence a person can act on, which the install task reports as its failure:
     * a `git` that is missing and could not be downloaded, a JDK BuildTools refuses, or the last
     * error line the build printed.
     */
    fun install(
        serverDirectory: File,
        rev: String,
        toolUrl: String?,
        javaMajor: Int?,
        onProgress: (Int, String) -> Unit
    ) {
        if (!PathSafety.isSafeSegment(rev) || !REVISION.matches(rev)) {
            throw IllegalStateException("\"$rev\" is not a revision BuildTools can build.")
        }

        val cache = cacheDir()

        cache.mkdirs()

        val cached = File(cache, "spigot-$rev.jar")
        val target = File(serverDirectory, SERVER_JAR)

        if (useCached(cached, target, rev, onProgress)) {
            return
        }

        // One build at a time on a node, and the wait is reported rather than looking like a
        // hung install: two BuildTools runs on the same machine fight over CPU, over the same
        // Maven repository and -- when they are the same revision -- over the same output file.
        if (!BUILD_LOCK.tryLock()) {
            onProgress(WAIT_PERCENT, "Waiting for another BuildTools build on this node to finish")

            BUILD_LOCK.lock()
        }

        try {
            // The build we waited for may have been this very revision, which is the common case
            // when several Spigot servers of one version are created at once.
            if (useCached(cached, target, rev, onProgress)) {
                return
            }

            val startedAt = System.currentTimeMillis()

            build(cache, rev, toolUrl, javaMajor, onProgress)

            val built = findBuiltJar(cache, rev, startedAt)
                ?: throw IllegalStateException("BuildTools finished without producing a Spigot $rev jar.")

            // BuildTools names its output after the version it actually built, which for a moving
            // revision is not the string it was given; the cache is keyed by what was asked for.
            if (built.absolutePath != cached.absolutePath) {
                built.copyTo(cached, overwrite = true)
            }

            copyIntoServer(cached, target)

            logger.info("Built Spigot $rev with BuildTools and cached it at ${cached.absolutePath}.")
        } finally {
            BUILD_LOCK.unlock()
        }
    }

    /** `<data>/cache/spigot`, where every revision this node has ever built stays. */
    fun cacheDir(): File = File(File(dataDir, "cache"), "spigot")

    private fun useCached(cached: File, target: File, rev: String, onProgress: (Int, String) -> Unit): Boolean {
        // The zip check is what stops a half-written cache entry -- a node killed mid-copy -- from
        // being installed forever after.
        if (!cached.isFile || !Downloader.isZip(cached)) {
            return false
        }

        onProgress(CACHE_PERCENT, "Using the Spigot $rev build from cache")

        copyIntoServer(cached, target)

        return true
    }

    private fun copyIntoServer(jar: File, target: File) {
        target.parentFile?.mkdirs()

        jar.copyTo(target, overwrite = true)
    }

    private fun build(
        cache: File,
        rev: String,
        toolUrl: String?,
        javaMajor: Int?,
        onProgress: (Int, String) -> Unit
    ) {
        // Sorted out before the BuildTools download and before the JDK lookup: finding out ten
        // minutes in that there is no git is not a diagnosis.
        val gitEnvironment = prepareGit(onProgress)

        val toolsDir = File(cache, "buildtools")
        val workDir = File(toolsDir, "work-$rev")

        workDir.mkdirs()

        val tool = File(toolsDir, TOOL_JAR)

        onProgress(START_PERCENT, "Downloading BuildTools")

        runner.download(toolUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL_URL, tool)

        if (!Downloader.isZip(tool)) {
            tool.delete()

            throw IllegalStateException("BuildTools did not download as a jar.")
        }

        val java = resolveJava(javaMajor, onProgress)

        val command = listOf(
            javaLocator.launcher(java).absolutePath,
            "-jar",
            tool.absolutePath,
            "--rev",
            rev,
            // Straight into the cache: the jar BuildTools writes *is* the cache entry, so a
            // successful build never has to be copied anywhere to be reusable.
            "--output-dir",
            cache.absolutePath,
            "--compile",
            "SPIGOT"
        )

        // HOME is the tools directory rather than whatever the daemon's user has, so Maven's
        // repository and git's config land in the cache and are shared by every revision -- and
        // so a node running as a user with no home directory can build at all.
        //
        // PATH is only set when the node's own git is used, and only for this process: the daemon,
        // the servers and the host keep whatever PATH they had.
        val environment = mapOf(
            "HOME" to toolsDir.absolutePath,
            "JAVA_HOME" to java.path
        ) + gitEnvironment

        val log = File(workDir, LOG_FILE)

        onProgress(START_PERCENT, "Compiling Spigot $rev with BuildTools (this takes a few minutes)")

        logger.info("Running BuildTools for Spigot $rev with Java ${java.major} in ${workDir.absolutePath}.")

        var percent = START_PERCENT
        var phaseMessage = "Compiling Spigot $rev with BuildTools"
        var lastForwarded = 0L
        var lastError: String? = null
        // Maven's own explanation, which comes before BuildTools' "Error running command" summary
        // and says what actually went wrong; see [mavenCause].
        var mavenCause: String? = null
        var mavenGoalFailure: String? = null

        val exit = log.bufferedWriter().use { writer ->
            runner.run(
                command,
                workDir,
                environment,
                TIMEOUT_MINUTES,
                onLine = { line ->
                    // Every line goes to the file; only some of them go on the wire. The file is
                    // the thing somebody reads after a failure, and it has to be complete.
                    writer.write(line)
                    writer.newLine()
                    writer.flush()

                    failureLine(line)?.let { lastError = it }

                    if (mavenCause == null) {
                        mavenCause(line)?.let { mavenCause = it }
                    }

                    if (mavenGoalFailure == null) {
                        mavenGoalFailure(line)?.let { mavenGoalFailure = it }
                    }

                    val next = percentFor(line, percent)
                    val phaseChanged = next != percent

                    percent = next

                    if (phaseChanged) {
                        phaseMessage = clean(line) ?: phaseMessage
                    }

                    // BuildTools prints thousands of Maven lines; a frame per line would be a
                    // flood Pano forwards to every open panel. A phase is always worth saying.
                    val now = System.currentTimeMillis()

                    if (phaseChanged || now - lastForwarded >= PROGRESS_INTERVAL_MS) {
                        lastForwarded = now

                        onProgress(percent, clean(line) ?: phaseMessage)
                    }
                },
                onHeartbeat = {
                    // A task that keeps reporting is never timed out by Pano; a ten-minute silent
                    // Maven download would otherwise kill a build that is working perfectly well.
                    onProgress(percent, phaseMessage)
                }
            )
        }

        if (exit != 0) {
            throw IllegalStateException(failureMessage(mavenCause ?: mavenGoalFailure ?: lastError, exit, log))
        }
    }

    /**
     * Makes sure BuildTools will find a `git`, and returns the environment the build needs for it
     * (empty, or a `PATH` with the node's own git in front).
     *
     * What BuildTools actually needs, verified 2026-09-24 against BuildTools #201 building 1.21.8
     * (§9): it clones and fetches with its bundled JGit, and the git *processes* it starts are
     * `git --version`, JGit's `git config --system --list` probe and the local operations of
     * Spigot's `applyPatches.sh` (clone/fetch from a sibling directory, `reset --hard`,
     * `am --3way`). None of them talks to a remote, which is why a git built without curl or
     * OpenSSL -- the one Pano's release CI publishes -- is enough.
     *
     * In order:
     * - a git on the daemon's PATH is used as it is;
     * - on Windows nothing is needed: BuildTools checks for `sh`, and without one it downloads a
     *   checksum-pinned PortableGit into its own working directory and runs through its git-bash.
     *   A MinGit on the PATH would be worse than nothing -- it has `sh` but no `bash`, so it would
     *   switch that tested path off and run the patch script through a shell BuildTools does not
     *   expect;
     * - a git this node downloaded before is used even with downloads switched off -- it is
     *   already here;
     * - otherwise it is downloaded when `node.tool-auto-download` allows, and a failure says why
     *   and still names the package that would fix it.
     */
    private fun prepareGit(onProgress: (Int, String) -> Unit): Map<String, String> {
        if (runner.hasGit()) {
            return emptyMap()
        }

        if (windows) {
            onProgress(START_PERCENT, GIT_WINDOWS)

            return emptyMap()
        }

        val installer = gitInstaller ?: throw IllegalStateException(GIT_MISSING)

        val git = installer.installed() ?: run {
            if (!toolAutoDownload()) {
                throw IllegalStateException(GIT_DOWNLOAD_DISABLED)
            }

            try {
                installer.ensure { percent, message ->
                    // The build's own percentage starts after this, so the git download is told
                    // in words and keeps the bar where it is.
                    onProgress(START_PERCENT, if (percent in 1..99) "$message ($percent%)" else message)
                }
            } catch (exception: GitUnavailableException) {
                throw IllegalStateException(gitDownloadFailed(exception.message))
            } catch (exception: Exception) {
                logger.warn("Could not download git for BuildTools: ${exception.message}")

                throw IllegalStateException(gitDownloadFailed(exception.message ?: exception.javaClass.simpleName))
            }
        }

        onProgress(START_PERCENT, "Using the node's own git ${git.version}")

        return mapOf(PATH to GitToolInstaller.pathWith(listOf(git.binDir), hostPath()))
    }

    /**
     * The JDK the build runs on.
     *
     * The exact major BuildTools asks for, or the nearest one above it, which is
     * [JavaRuntimeLocator.resolve]'s own rule. Nothing is second-guessed past that: BuildTools
     * checks the JDK itself and refuses an unsuitable one with a message naming the range, and
     * surfacing that beats this daemon inventing its own answer.
     */
    private fun resolveJava(javaMajor: Int?, onProgress: (Int, String) -> Unit): com.panomc.node.host.JavaRuntime {
        // The exact major is downloaded first when it is missing and downloads are on (SM-63):
        // BuildTools names the range it accepts and "the nearest one above" is often outside it.
        // A JDK, not the JRE a server gets: BuildTools compiles, and on a JRE Maven fails at the
        // very end with "No compiler is provided in this environment".
        if (javaMajor != null && javaService?.enabled == true) {
            val outcome = javaService.ensure(javaMajor, jdk = true) { _, message -> onProgress(START_PERCENT, message) }

            if (outcome is JavaRuntimeService.Outcome.Failed) {
                logger.warn("Could not download a Java $javaMajor JDK for BuildTools: ${outcome.message}")
            }
        }

        val jdks = javaLocator.discover().filter { it.hasCompiler }

        return javaMajor
            ?.let { major -> javaLocator.exactJdk(major) ?: jdks.filter { it.major > major }.minByOrNull { it.major } }
            ?: jdks.takeIf { javaMajor == null }?.maxByOrNull { it.major }
            ?: throw IllegalStateException(
                "BuildTools needs a JDK (Java ${javaMajor ?: "8 or newer"} with javac) and this node has none" +
                    (if (javaService?.enabled == true) " and could not download one" else "; install one or turn Java downloads on") +
                    "."
            )
    }

    private fun clean(line: String): String? = line
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .takeIf { it.isNotEmpty() }
        ?.take(MAX_MESSAGE)

    private fun failureMessage(lastError: String?, exit: Int, log: File): String {
        val reason = lastError ?: "BuildTools exited with code $exit"

        return "$reason -- the full build log is at ${log.absolutePath}"
    }

    /** The real host: a `git` probe, a download and a process with its output drained. */
    class SystemRunner : Runner {
        override fun hasGit(): Boolean = try {
            val process = ProcessBuilder(listOf(GIT, "--version"))
                .redirectErrorStream(true)
                .start()

            process.inputStream.use { it.readBytes() }

            process.waitFor(GIT_PROBE_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            // An IOException here is "there is no git on the PATH", which is the answer.
            false
        }

        override fun download(url: String, target: File) {
            Downloader.download(url, target) { }
        }

        override fun run(
            command: List<String>,
            directory: File,
            environment: Map<String, String>,
            timeoutMinutes: Long,
            onLine: (String) -> Unit,
            onHeartbeat: () -> Unit
        ): Int {
            val builder = ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)

            builder.environment().putAll(environment)

            val process = builder.start()

            // Drained on its own thread for the reason every drain in this daemon is: a process
            // that fills its pipe blocks forever if nobody reads it, and reading it on this
            // thread would mean waiting for EOF and never reaching the timeout below.
            val drain = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine(onLine)
                } catch (_: Exception) {
                    // The stream dies with the process; there is nothing left to read or to say.
                }
            }

            drain.isDaemon = true
            drain.start()

            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes)

            while (System.currentTimeMillis() < deadline) {
                if (process.waitFor(HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                    drain.join(DRAIN_JOIN_MS)

                    return process.exitValue()
                }

                onHeartbeat()
            }

            process.destroyForcibly()

            throw IllegalStateException("BuildTools did not finish within $timeoutMinutes minutes.")
        }

        private companion object {
            const val GIT = "git"
            const val GIT_PROBE_SECONDS = 10L
            const val DRAIN_JOIN_MS = 2000L
        }
    }

    companion object {
        /** What a host without git is told, naming the three package managers it might have. */
        const val GIT_MISSING =
            "Git is not installed on this node; BuildTools needs it " +
                "(apt install git / pacman -S git / brew install git)."

        /** The same, on a node whose operator switched tool downloads off. */
        const val GIT_DOWNLOAD_DISABLED =
            "Git is not installed on this node and automatic tool download is disabled; BuildTools needs it " +
                "(apt install git / pacman -S git / brew install git)."

        /** Said on Windows, where BuildTools fetches its own portable Git for Windows. */
        const val GIT_WINDOWS = "No git on this node; BuildTools downloads its own portable Git for Windows"

        /** A download that failed still ends in the command that fixes it for good. */
        fun gitDownloadFailed(reason: String?): String =
            "Git is not installed on this node and downloading a portable git failed" +
                (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
                "; install it (apt install git / pacman -S git / brew install git) or try again."

        private const val PATH = "PATH"

        const val SERVER_JAR = "server.jar"

        const val TOOL_JAR = "BuildTools.jar"

        const val LOG_FILE = "buildtools.log"

        /** Used when Pano sent no tool URL, so an older platform can still drive a build. */
        const val DEFAULT_TOOL_URL =
            "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"

        /**
         * How long a build may take before it is killed.
         *
         * A cold first build on a small VPS is a good twenty minutes; 45 is the point past which
         * something is wrong rather than slow.
         */
        const val TIMEOUT_MINUTES = 45L

        /** How often a running build reports even when it has printed nothing. */
        const val HEARTBEAT_SECONDS = 30L

        /** Where a cached revision lands on the install's own 0..100. */
        const val CACHE_PERCENT = 20

        /** Reported while queued behind another build. */
        const val WAIT_PERCENT = 6

        const val START_PERCENT = 10
        const val PULL_PERCENT = 30
        const val PATCH_PERCENT = 50
        const val COMPILE_PERCENT = 70
        const val SUCCESS_PERCENT = 90

        /** How much of a log line is worth putting on a progress frame. */
        const val MAX_MESSAGE = 200

        /** At most one frame per second out of a log that prints hundreds. */
        const val PROGRESS_INTERVAL_MS = 1000L

        private val BUILD_LOCK = ReentrantLock()

        /** `1.21.8`, `1.8.8`, `26.3` -- and never a path, an option or `latest`. */
        private val REVISION = Regex("^\\d+\\.\\d+(\\.\\d+)?$")

        /**
         * Where a log line puts the build on its way from 10 to 90.
         *
         * Four phases, because those are the four things BuildTools actually announces, and a
         * percentage that moves with the work is worth more than an invented one. It never goes
         * backwards: Maven prints "Compiling" for each of a dozen modules and the patch phase
         * mentions itself again on its way out.
         */
        fun percentFor(line: String, current: Int): Int {
            val text = line.lowercase()

            val phase = when {
                text.contains("success!") -> SUCCESS_PERCENT
                text.contains("compiling") -> COMPILE_PERCENT
                text.contains("applying patches") -> PATCH_PERCENT
                text.contains("pulling") -> PULL_PERCENT
                else -> current
            }

            return maxOf(current, phase)
        }

        /**
         * The jar this build produced, or null when it produced none.
         *
         * The expected name first; then, for a revision whose name BuildTools resolves into a
         * version of its own, the newest `spigot-*.jar` written *since the build started* -- which
         * is what keeps an older cached revision sitting in the same directory from being
         * installed as if it were this one.
         */
        fun findBuiltJar(outputDir: File, rev: String, since: Long = 0L): File? {
            val exact = File(outputDir, "spigot-$rev.jar")

            if (exact.isFile) {
                return exact
            }

            return outputDir.listFiles()
                ?.filter {
                    it.isFile && it.name.startsWith(JAR_PREFIX) && it.name.endsWith(JAR_SUFFIX) &&
                        it.lastModified() >= since
                }
                ?.maxByOrNull { it.lastModified() }
        }

        /**
         * [line] when it is the kind of line a failure is explained by, else null.
         *
         * The *last* one wins, unlike a crashed server where the first is the cause: a build fails
         * at the bottom, where Maven prints its summary, and everything above it is the work that
         * went fine.
         */
        fun failureLine(line: String): String? {
            if (MARKERS.none { line.contains(it) }) {
                return null
            }

            return line.replace('\n', ' ').trim().takeIf { it.isNotEmpty() }?.take(MAX_MESSAGE)
        }

        /**
         * The first `[ERROR]` line of Maven's that says something: "No compiler is provided in this
         * environment…", "Foo.java:[12,5] cannot find symbol". Maven prints its cause first and
         * a page of boilerplate after it, and BuildTools then adds `Error running command …` with
         * the whole Maven command line -- the line [failureLine] ends up on, which tells an admin
         * only that something failed.
         */
        fun mavenCause(line: String): String? {
            val body = mavenErrorBody(line) ?: return null

            if (body.startsWith(GOAL_FAILURE) || MAVEN_BOILERPLATE.any { body.startsWith(it) }) {
                return null
            }

            return body.take(MAX_MESSAGE)
        }

        /** `Failed to execute goal …`, the cause when Maven gives no line of its own under it. */
        fun mavenGoalFailure(line: String): String? =
            mavenErrorBody(line)?.takeIf { it.startsWith(GOAL_FAILURE) }?.take(MAX_MESSAGE)

        private fun mavenErrorBody(line: String): String? {
            val text = line.replace('\n', ' ').trim()

            if (!text.startsWith(MAVEN_ERROR)) {
                return null
            }

            return text.removePrefix(MAVEN_ERROR).trim().takeIf { it.isNotEmpty() }
        }

        private const val MAVEN_ERROR = "[ERROR]"
        private const val GOAL_FAILURE = "Failed to execute goal"

        /** Maven's `[ERROR]` lines that are about how to read the failure, not what it was. */
        private val MAVEN_BOILERPLATE = listOf(
            "COMPILATION ERROR",
            "BUILD FAILURE",
            "-> [Help",
            "[Help",
            "To see the full stack trace",
            "Re-run Maven",
            "For more information",
            "After correcting the problems",
            "mvn <args>"
        )

        private const val JAR_PREFIX = "spigot-"
        private const val JAR_SUFFIX = ".jar"

        /**
         * What BuildTools, Maven and the JVM call a failure between them.
         *
         * `Error` unqualified is deliberate: the most common BuildTools failure of all is
         * `java.lang.UnsupportedClassVersionError` from a JDK outside the revision's range, and
         * a marker that only matched `Error:` would throw away the one line that explains it.
         */
        private val MARKERS = listOf("ERROR", "Exception", "Error", "error:", "FAILURE")
    }
}
