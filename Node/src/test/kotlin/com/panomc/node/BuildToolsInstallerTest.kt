package com.panomc.node

import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.task.BuildToolsInstaller
import com.panomc.node.tools.GitToolInstaller
import com.panomc.node.tools.GitToolResolver
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Drives the BuildTools install with the host stubbed out.
 *
 * Nothing here runs BuildTools: a real run clones three git repositories and compiles Minecraft,
 * which is not a unit test. What is worth pinning is everything around it -- that a second server
 * on the same revision is a file copy rather than a second ten-minute build, that a host without
 * git is told so in a sentence naming the fix, that a percentage moves with the phases the log
 * announces, and that the jar the build wrote is the one that ends up in the server directory.
 */
class BuildToolsInstallerTest {
    @TempDir
    lateinit var root: File

    private lateinit var dataDir: File
    private lateinit var serverDir: File
    private lateinit var logger: NodeLogger

    private val progress = mutableListOf<Pair<Int, String>>()

    @BeforeEach
    fun setUp() {
        dataDir = File(root, "node-data").apply { mkdirs() }
        serverDir = File(root, "servers/abc").apply { mkdirs() }

        // The daemon's logger writes to stdout; a test has no reason to.
        logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

        progress.clear()
    }

    @Test
    fun `a cached revision is copied in without touching the host`() {
        val cached = File(dataDir, "cache/spigot/spigot-1.21.8.jar")

        cached.parentFile.mkdirs()
        cached.writeBytes(jarBytes("cached"))

        // Every method on this runner fails the test: a cache hit must not probe git, download
        // BuildTools or start a process.
        val installer = installer(RefusingRunner())

        installer.install(serverDir, "1.21.8", null, 21, ::record)

        val installed = File(serverDir, "server.jar")

        assertTrue(installed.isFile)
        assertEquals(cached.readBytes().toList(), installed.readBytes().toList())
        assertTrue(messages().any { it == "Using the Spigot 1.21.8 build from cache" }, messages().toString())

        // And the cache keeps its copy, which is the whole point of having one.
        assertTrue(cached.isFile)
    }

    @Test
    fun `a host without git is told exactly what to install`() {
        val runner = StubRunner(hasGit = false)

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner).install(serverDir, "1.21.8", TOOL_URL, 21, ::record)
        }

        assertEquals(
            "Git is not installed on this node; BuildTools needs it " +
                "(apt install git / pacman -S git / brew install git).",
            failure.message
        )

        // Nothing was downloaded and no process was started before the requirement was checked.
        assertFalse(runner.downloaded)
        assertFalse(runner.ran)
        assertFalse(File(serverDir, "server.jar").exists())
    }

    @Test
    fun `a host without git gets the node's own, on the build's PATH only`() {
        val runner = StubRunner(hasGit = false, lines = listOf("Success!"))
        val git = FakeGit()

        installer(runner, git.installer(), hostPath = "/usr/local/bin:/usr/bin:/bin")
            .install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        assertEquals(1, git.fetches)
        assertTrue(File(serverDir, "server.jar").isFile)

        val bin = File(dataDir, "tools/git/linux-x64-2.55.0/bin").absolutePath

        // In front of the host's PATH, which is kept: BuildTools' patch script needs sh and sed.
        assertEquals("$bin:/usr/local/bin:/usr/bin:/bin", runner.environment?.get("PATH"))

        // The download is reported inside the install task, before the build starts.
        assertTrue(messages().any { it.startsWith("Downloading git (pano-git-linux-x64.tar.gz, v1.2.0)") }, messages().toString())
        assertTrue(messages().contains("Using the node's own git 2.55.0"), messages().toString())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first }, "the percentage must never go backwards")
    }

    @Test
    fun `a system git wins and the build's PATH is left alone`() {
        val runner = StubRunner(hasGit = true, lines = listOf("Success!"))
        val git = FakeGit()

        installer(runner, git.installer(), hostPath = "/usr/bin").install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        assertEquals(0, git.fetches)
        assertFalse(runner.environment.orEmpty().containsKey("PATH"))
        assertFalse(File(dataDir, "tools").exists())
    }

    @Test
    fun `a git downloaded earlier is used even with downloads switched off`() {
        val git = FakeGit()

        installer(StubRunner(hasGit = false, lines = listOf("Success!")), git.installer())
            .install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        val runner = StubRunner(hasGit = false, lines = listOf("Success!"))

        installer(runner, git.installer(), autoDownload = false, hostPath = "/usr/bin")
            .install(serverDir, "1.21.9", TOOL_URL, null, ::record)

        assertEquals(1, git.fetches)
        assertTrue(runner.environment?.get("PATH")!!.startsWith(File(dataDir, "tools/git").absolutePath))
    }

    @Test
    fun `with downloads switched off a host without git is told so`() {
        val runner = StubRunner(hasGit = false)
        val git = FakeGit()

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner, git.installer(), autoDownload = false).install(serverDir, "1.21.8", TOOL_URL, 21, ::record)
        }

        assertEquals(BuildToolsInstaller.GIT_DOWNLOAD_DISABLED, failure.message)
        assertTrue(failure.message!!.contains("apt install git"))
        assertEquals(0, git.fetches)
        assertFalse(runner.downloaded)
        assertFalse(runner.ran)
    }

    @Test
    fun `a failed git download fails the install with the reason and the fix`() {
        val runner = StubRunner(hasGit = false)
        val git = FakeGit(failWith = IllegalStateException("Download failed with HTTP 503."))

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner, git.installer()).install(serverDir, "1.21.8", TOOL_URL, 21, ::record)
        }

        assertEquals(
            "Git is not installed on this node and downloading a portable git failed (Download failed with HTTP 503.); " +
                "install it (apt install git / pacman -S git / brew install git) or try again.",
            failure.message
        )

        // Nothing half-installed, and BuildTools was never fetched or started.
        assertTrue(File(dataDir, "tools/git").listFiles().orEmpty().isEmpty())
        assertFalse(runner.downloaded)
        assertFalse(runner.ran)
    }

    @Test
    fun `a host no release builds git for gets the install command`() {
        val runner = StubRunner(hasGit = false)
        val resolver = GitToolResolver(JavaTarget("linux", "arm32"), "1.2.0", { throw AssertionError("nothing to ask") })
        val installer = GitToolInstaller(dataDir, resolver, logger, JavaTarget("linux", "arm32"))

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner, installer).install(serverDir, "1.21.8", TOOL_URL, 21, ::record)
        }

        assertTrue(failure.message!!.startsWith("Git is not installed on this node and downloading a portable git failed"))
        assertTrue(failure.message!!.contains("No portable git is published for linux/arm32"), failure.message)
        assertFalse(runner.ran)
    }

    @Test
    fun `on Windows BuildTools is left to fetch its own git`() {
        val runner = StubRunner(hasGit = false, lines = listOf("Success!"))
        val git = FakeGit()

        installer(runner, git.installer(), windows = true).install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        assertEquals(0, git.fetches)
        assertTrue(runner.ran)
        assertFalse(runner.environment.orEmpty().containsKey("PATH"))
        assertTrue(messages().contains(BuildToolsInstaller.GIT_WINDOWS))
    }

    @Test
    fun `a build installs the jar it produced and keeps it cached`() {
        val runner = StubRunner(
            lines = listOf(
                "Pulling updates for BuildData...",
                "Applying patches to CraftBukkit...",
                "[INFO] Compiling 1420 source files",
                "Success! Everything completed successfully. Copying final .jar files now."
            )
        )

        // The Java is left to the host: the running JVM is the one runtime a test can rely on.
        installer(runner).install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        val installed = File(serverDir, "server.jar")
        val cached = File(dataDir, "cache/spigot/spigot-1.21.8.jar")

        assertTrue(installed.isFile)
        assertTrue(cached.isFile)
        assertEquals(cached.readBytes().toList(), installed.readBytes().toList())

        // Run in its own per-revision directory, with the log left there for somebody to read.
        assertTrue(runner.directory!!.absolutePath.endsWith("cache/spigot/buildtools/work-1.21.8"))
        assertTrue(File(runner.directory, "buildtools.log").readText().contains("Applying patches"))

        // The command line is the contract's, in its order.
        val command = runner.command.orEmpty()

        assertTrue(command.contains("-jar"))
        assertEquals(
            listOf("--rev", "1.21.8", "--output-dir", File(dataDir, "cache/spigot").absolutePath, "--compile", "SPIGOT"),
            command.subList(command.size - 6, command.size)
        )
        assertTrue(runner.environment.orEmpty().containsKey("JAVA_HOME"))
        assertEquals(File(dataDir, "cache/spigot/buildtools").absolutePath, runner.environment?.get("HOME"))
    }

    @Test
    fun `the log lines are reported as progress and the percent follows the phases`() {
        val runner = StubRunner(
            lines = listOf(
                "Pulling updates for BuildData...",
                "Applying patches to CraftBukkit...",
                "[INFO] Compiling 1420 source files",
                "Success! Everything completed successfully."
            )
        )

        installer(runner).install(serverDir, "1.21.8", TOOL_URL, null, ::record)

        val percents = progress.map { it.first }

        // A phase is always reported, whatever the rate limit would say about it.
        assertTrue(progress.any { it.second.contains("Pulling updates") })
        assertTrue(progress.any { it.second.contains("Applying patches") })
        assertTrue(progress.any { it.second.contains("Compiling") })

        assertEquals(percents.sorted(), percents, "the percentage must never go backwards")
        assertEquals(90, percents.last())
    }

    @Test
    fun `a failed build fails with the last error line and keeps the log`() {
        val runner = StubRunner(
            exitCode = 1,
            lines = listOf(
                "Pulling updates for BuildData...",
                "[ERROR] Failed to execute goal on project spigot: Could not resolve dependencies",
                "[INFO] BUILD FAILURE"
            )
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner).install(serverDir, "1.21.8", TOOL_URL, null, ::record)
        }

        val log = File(dataDir, "cache/spigot/buildtools/work-1.21.8/buildtools.log")

        // The last failure line, not the first and not the exit code on its own.
        assertTrue(failure.message!!.startsWith("[INFO] BUILD FAILURE"), failure.message)
        assertTrue(failure.message!!.contains(log.absolutePath))
        assertTrue(log.readText().contains("Could not resolve dependencies"))
        assertFalse(File(serverDir, "server.jar").exists())
    }

    @Test
    fun `a build that produced nothing is a failure rather than a broken server`() {
        val runner = StubRunner(lines = listOf("Success!"), writesJar = false)

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(runner).install(serverDir, "1.21.8", TOOL_URL, null, ::record)
        }

        assertEquals("BuildTools finished without producing a Spigot 1.21.8 jar.", failure.message)
    }

    @Test
    fun `a revision that is not a version is refused before anything runs`() {
        val runner = RefusingRunner()

        listOf("latest", "../../etc", "1.21.8 --rev", "").forEach { rev ->
            assertThrows(IllegalStateException::class.java) {
                installer(runner).install(serverDir, rev, TOOL_URL, 21, ::record)
            }
        }
    }

    @Test
    fun `the phase mapping is coarse, monotonic and ignores everything else`() {
        assertEquals(30, BuildToolsInstaller.percentFor("Pulling updates for BuildData...", 10))
        assertEquals(50, BuildToolsInstaller.percentFor("Applying patches to CraftBukkit...", 30))
        assertEquals(70, BuildToolsInstaller.percentFor("[INFO] Compiling 1420 source files", 50))
        assertEquals(90, BuildToolsInstaller.percentFor("Success! Everything completed successfully.", 70))

        // Maven prints "Compiling" per module and the patch phase mentions itself on the way out;
        // neither is a reason for a progress bar to jump backwards.
        assertEquals(70, BuildToolsInstaller.percentFor("Applying patches to Spigot", 70))
        assertEquals(90, BuildToolsInstaller.percentFor("[INFO] Compiling 3 source files", 90))

        // Anything the build says that is not a phase leaves the percentage where it was.
        assertEquals(50, BuildToolsInstaller.percentFor("[INFO] Downloading from central", 50))
    }

    @Test
    fun `finds the built jar by name, and otherwise by what this build wrote`() {
        val output = File(root, "out").apply { mkdirs() }

        val exact = File(output, "spigot-1.21.8.jar").apply { writeBytes(jarBytes("exact")) }

        assertEquals(exact, BuildToolsInstaller.findBuiltJar(output, "1.21.8"))

        exact.setLastModified(1_000L)

        // A revision BuildTools resolved into a version of its own: only a jar written since the
        // build started counts, so the cached revision next to it is not installed by mistake.
        val old = File(output, "spigot-1.20.6.jar").apply { writeBytes(jarBytes("old")) }

        old.setLastModified(1_000L)

        val fresh = File(output, "spigot-1.21.9.jar").apply { writeBytes(jarBytes("fresh")) }

        fresh.setLastModified(9_000L)

        assertEquals(fresh, BuildToolsInstaller.findBuiltJar(output, "snapshot", 5_000L))
        assertEquals(null, BuildToolsInstaller.findBuiltJar(output, "snapshot", 20_000L))
    }

    @Test
    fun `an error line is recognised, an ordinary one is not`() {
        assertEquals(
            "[ERROR] Failed to execute goal",
            BuildToolsInstaller.failureLine("[ERROR] Failed to execute goal")
        )
        assertEquals(
            "java.lang.UnsupportedClassVersionError",
            BuildToolsInstaller.failureLine("  java.lang.UnsupportedClassVersionError  ")
        )
        assertEquals(null, BuildToolsInstaller.failureLine("[INFO] Compiling 1420 source files"))
    }

    private fun installer(runner: BuildToolsInstaller.Runner) =
        BuildToolsInstaller(dataDir, JavaRuntimeLocator(dataDir), logger, runner, windows = false)

    private fun installer(
        runner: BuildToolsInstaller.Runner,
        git: GitToolInstaller,
        autoDownload: Boolean = true,
        windows: Boolean = false,
        hostPath: String? = "/usr/bin:/bin"
    ) = BuildToolsInstaller(
        dataDir,
        JavaRuntimeLocator(dataDir),
        logger,
        runner,
        gitInstaller = git,
        toolAutoDownload = { autoDownload },
        windows = windows,
        hostPath = { hostPath }
    )

    /**
     * The node's git download, offline: a release fixture pointing at a tarball in the CI layout,
     * a copy for the download and a canned `git --version`.
     */
    private inner class FakeGit(private val failWith: Exception? = null) {
        var fetches = 0

        private val archive: File by lazy {
            TarBuilder()
                .directory("pano-git-linux-x64/")
                .file("pano-git-linux-x64/bin/git", "#!/bin/sh\n", mode = 0b111_101_101)
                .symlink("pano-git-linux-x64/libexec/git-core/git-am", "../../bin/git")
                .writeTarGz(File(root, "pano-git-linux-x64.tar.gz"))
        }

        fun installer(): GitToolInstaller {
            val target = JavaTarget("linux", "x64")

            val resolver = GitToolResolver(target, "1.2.0", { url ->
                if (url.endsWith("/releases/tags/v1.2.0")) {
                    JavaPackageResolver.Http.Response(
                        200,
                        """{"tag_name":"v1.2.0","assets":[{"name":"pano-git-linux-x64.tar.gz",""" +
                            """"digest":"sha256:${Sha256.of(archive)}","size":${archive.length()},""" +
                            """"browser_download_url":"https://example.invalid/pano-git-linux-x64.tar.gz"}]}"""
                    )
                } else {
                    JavaPackageResolver.Http.Response(404, "")
                }
            })

            return GitToolInstaller(
                dataDir,
                resolver,
                logger,
                target,
                fetch = { _, file, onFraction ->
                    fetches++

                    failWith?.let { throw it }

                    archive.copyTo(file, overwrite = true)
                    onFraction(1.0)
                },
                probe = { "2.55.0" }
            )
        }
    }

    private fun record(percent: Int, message: String) {
        progress.add(percent to message)
    }

    private fun messages() = progress.map { it.second }

    /** A jar as far as the zip check is concerned, with distinguishable contents. */
    private fun jarBytes(marker: String) = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + marker.toByteArray()

    /** Fails the test if it is touched at all. */
    private inner class RefusingRunner : BuildToolsInstaller.Runner {
        override fun hasGit(): Boolean = throw AssertionError("git must not be probed here")

        override fun download(url: String, target: File) = throw AssertionError("nothing may be downloaded here")

        override fun run(
            command: List<String>,
            directory: File,
            environment: Map<String, String>,
            timeoutMinutes: Long,
            onLine: (String) -> Unit,
            onHeartbeat: () -> Unit
        ): Int = throw AssertionError("BuildTools must not be run here")
    }

    /** The host, replayed: a canned log, a canned exit code and the jar the build would write. */
    private inner class StubRunner(
        private val hasGit: Boolean = true,
        private val lines: List<String> = emptyList(),
        private val exitCode: Int = 0,
        private val writesJar: Boolean = true
    ) : BuildToolsInstaller.Runner {
        var downloaded = false
        var ran = false
        var command: List<String>? = null
        var directory: File? = null
        var environment: Map<String, String>? = null

        override fun hasGit(): Boolean = hasGit

        override fun download(url: String, target: File) {
            downloaded = true

            target.parentFile?.mkdirs()
            target.writeBytes(jarBytes("buildtools"))
        }

        override fun run(
            command: List<String>,
            directory: File,
            environment: Map<String, String>,
            timeoutMinutes: Long,
            onLine: (String) -> Unit,
            onHeartbeat: () -> Unit
        ): Int {
            ran = true

            this.command = command
            this.directory = directory
            this.environment = environment

            lines.forEach(onLine)

            if (writesJar && exitCode == 0) {
                val outputDir = File(command[command.indexOf("--output-dir") + 1])
                val rev = command[command.indexOf("--rev") + 1]

                File(outputDir, "spigot-$rev.jar").writeBytes(jarBytes("built $rev"))
            }

            return exitCode
        }
    }

    private companion object {
        const val TOOL_URL = "https://hub.spigotmc.org/jenkins/job/BuildTools/x/artifact/target/BuildTools.jar"
    }
}
