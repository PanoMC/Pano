package com.panomc.node

import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.tools.GitPackage
import com.panomc.node.tools.GitToolInstaller
import com.panomc.node.tools.GitToolResolver
import com.panomc.node.tools.GitUnavailableException
import com.panomc.node.tools.ManagedToolMarker
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The portable-git installer (SM-67, §2.4.32), offline: the release lookup is answered with a
 * fixture pointing at a tarball laid out exactly like the one `scripts/build-pano-git.sh` makes
 * (one `pano-git-<os>-<arch>/` directory, `bin/git`, relative symlinks in `libexec/git-core`), the
 * download is a file copy and `git --version` is replaced, because `:Node:test` starts no
 * processes.
 */
class GitToolInstallerTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var workDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val target = JavaTarget("linux", "x64")

    /** The CI tarball's layout, in miniature. */
    private fun gitTarGz(top: String = "pano-git-linux-x64"): File =
        TarBuilder()
            .directory("$top/")
            .file("$top/bin/git", "#!/bin/sh\necho fake git\n", mode = 0b111_101_101)
            .file("$top/libexec/git-core/git-sh-setup", "# sourced by scripts\n", mode = 0b110_100_100)
            .symlink("$top/libexec/git-core/git", "../../bin/git")
            .symlink("$top/libexec/git-core/git-am", "../../bin/git")
            .symlink("$top/bin/git-upload-pack", "git")
            .file("$top/share/git-core/templates/description", "template\n")
            .file("$top/PANO-GIT-VERSION", "2.55.0\n")
            .writeTarGz(File(workDir, "pano-git-linux-x64.tar.gz"))

    private fun resolverFor(archive: File, sha256: String = Sha256.of(archive)): GitToolResolver {
        val release = """
            {"tag_name":"v1.2.0","draft":false,"assets":[
              {"name":"pano-git-linux-x64.tar.gz","size":${archive.length()},"digest":"sha256:$sha256",
               "browser_download_url":"https://example.invalid/pano-git-linux-x64.tar.gz"}]}
        """.trimIndent()

        return GitToolResolver(target, "1.2.0", { url ->
            if (url.endsWith("/releases/tags/v1.2.0")) {
                JavaPackageResolver.Http.Response(200, release)
            } else {
                JavaPackageResolver.Http.Response(404, "")
            }
        })
    }

    private fun installer(
        resolver: GitToolResolver,
        source: (GitPackage) -> File,
        fetches: AtomicInteger = AtomicInteger(),
        probe: (File) -> String = { "2.55.0" }
    ) = GitToolInstaller(
        dataDir,
        resolver,
        logger,
        target,
        fetch = { pkg, file, onFraction ->
            fetches.incrementAndGet()
            source(pkg).copyTo(file, overwrite = true)
            onFraction(0.5)
            onFraction(1.0)
        },
        probe = probe
    )

    @Test
    fun `installs git into its own directory under the data dir`() {
        val archive = gitTarGz()
        val progress = mutableListOf<Pair<Int, String>>()

        val git = installer(resolverFor(archive), { archive }).ensure { percent, message -> progress.add(percent to message) }

        val home = File(dataDir, "tools/git/linux-x64-2.55.0")

        assertEquals(home.absolutePath, git.home.absolutePath)
        assertEquals("2.55.0", git.version)
        assertEquals(File(home, "bin").absolutePath, git.binDir.absolutePath)

        // The top-level directory is stripped and the tree is intact, links and all.
        assertTrue(File(home, "bin/git").canExecute())
        assertTrue(Files.isSymbolicLink(File(home, "libexec/git-core/git-am").toPath()))
        assertEquals(File(home, "bin/git").canonicalPath, File(home, "libexec/git-core/git-am").canonicalPath)
        assertTrue(File(home, "share/git-core/templates/description").isFile)

        val marker = File(home, ManagedToolMarker.FILE).readText()

        assertTrue(marker.contains("\"tool\": \"git\""))
        assertTrue(marker.contains("\"release\": \"v1.2.0\""))
        assertTrue(marker.contains("\"sha256\": \"${Sha256.of(archive)}\""))

        // Nothing left behind, and nothing outside the data directory was written.
        assertTrue(File(dataDir, "cache/tools").listFiles().orEmpty().isEmpty())
        assertTrue(File(dataDir, "tools/git").listFiles().orEmpty().none { it.name.startsWith(".") })

        assertTrue(progress.any { it.second.startsWith("Downloading git (pano-git-linux-x64.tar.gz, v1.2.0)") })
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
    }

    @Test
    fun `an installed git is found again without asking GitHub`() {
        val archive = gitTarGz()
        val fetches = AtomicInteger()

        installer(resolverFor(archive), { archive }, fetches).ensure()

        // A resolver that fails the test if asked: the second call must be served from disk.
        val offline = GitToolResolver(target, "1.2.0", { throw AssertionError("GitHub must not be asked") })
        val again = installer(offline, { throw AssertionError("nothing may be downloaded") })

        assertEquals("2.55.0", again.installed()!!.version)
        assertEquals("2.55.0", again.ensure().version)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a checksum mismatch deletes the download and installs nothing`() {
        val archive = gitTarGz()
        val installer = installer(resolverFor(archive, sha256 = "0".repeat(64)), { archive })

        val failure = assertThrows(IllegalStateException::class.java) { installer.ensure() }

        assertTrue(failure.message!!.contains("SHA-256"), failure.message)
        assertTrue(File(dataDir, "cache/tools").listFiles().orEmpty().isEmpty())
        assertNull(installer.installed())
        assertTrue(File(dataDir, "tools/git").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a git that does not run on this host installs nothing`() {
        val archive = gitTarGz()
        val installer = installer(resolverFor(archive), { archive }, probe = {
            throw IllegalStateException("The downloaded git does not run on this host (exit 126).")
        })

        val failure = assertThrows(IllegalStateException::class.java) { installer.ensure() }

        assertTrue(failure.message!!.contains("does not run"))
        assertNull(installer.installed())
        assertTrue(File(dataDir, "tools/git").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an archive without bin git is refused`() {
        val archive = TarBuilder()
            .directory("pano-git-linux-x64/")
            .file("pano-git-linux-x64/README", "no binary here")
            .writeTarGz(File(workDir, "broken.tar.gz"))

        val failure = assertThrows(IllegalStateException::class.java) {
            installer(resolverFor(archive), { archive }).ensure()
        }

        assertTrue(failure.message!!.contains("bin/git"))
    }

    @Test
    fun `a symlink escaping the tree is refused`() {
        val archive = TarBuilder()
            .directory("pano-git-linux-x64/")
            .file("pano-git-linux-x64/bin/git", "#!/bin/sh\n", mode = 0b111_101_101)
            .symlink("pano-git-linux-x64/bin/evil", "../../../../etc/passwd")
            .writeTarGz(File(workDir, "evil.tar.gz"))

        assertThrows(IllegalStateException::class.java) {
            installer(resolverFor(archive), { archive }).ensure()
        }

        assertTrue(File(dataDir, "tools/git").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `no git for this host is its own failure`() {
        val none = GitToolResolver(target, "1.2.0", { url ->
            if (url.contains("/tags/")) JavaPackageResolver.Http.Response(404, "") else JavaPackageResolver.Http.Response(200, "[]")
        })

        assertThrows(GitUnavailableException::class.java) {
            installer(none, { throw AssertionError("nothing to download") }).ensure()
        }
    }

    @Test
    fun `only a complete git for this os and arch counts as installed`() {
        val git = File(dataDir, "tools/git")

        // Another architecture's tree, copied along with the data directory.
        File(git, "linux-arm64-2.55.0/bin").mkdirs()
        File(git, "linux-arm64-2.55.0/bin/git").apply { writeText("x"); setExecutable(true) }
        File(git, "linux-arm64-2.55.0/${ManagedToolMarker.FILE}").writeText("""{"tool":"git","version":"2.55.0"}""")

        // One without a marker (not ours), and an interrupted extraction.
        File(git, "linux-x64-2.54.0/bin").mkdirs()
        File(git, "linux-x64-2.54.0/bin/git").apply { writeText("x"); setExecutable(true) }
        File(git, ".tmp-1234/bin").mkdirs()

        val installer = installer(GitToolResolver(target, "1.2.0", { throw AssertionError() }), { throw AssertionError() })

        assertNull(installer.installed())

        // Two of ours: the newer one wins.
        listOf("2.53.1", "2.55.0").forEach { version ->
            val home = File(git, "linux-x64-$version")

            File(home, "bin").mkdirs()
            File(home, "bin/git").apply { writeText("x"); setExecutable(true) }
            File(home, ManagedToolMarker.FILE).writeText("""{"tool":"git","version":"$version"}""")
        }

        assertEquals("2.55.0", installer.installed()!!.version)

        installer.cleanup()

        assertFalse(File(git, ".tmp-1234").exists())
        assertNotNull(installer.installed())
    }

    @Test
    fun `the build's PATH gets git in front and keeps the rest`() {
        val bin = File("/data/tools/git/linux-x64-2.55.0/bin")

        assertEquals(
            "${bin.absolutePath}:/usr/local/bin:/usr/bin:/bin",
            GitToolInstaller.pathWith(listOf(bin), "/usr/local/bin:/usr/bin:/bin", ":")
        )

        // No PATH at all, or an empty one, is just git -- never a trailing separator, which a
        // shell would read as "the current directory".
        assertEquals(bin.absolutePath, GitToolInstaller.pathWith(listOf(bin), null, ":"))
        assertEquals(bin.absolutePath, GitToolInstaller.pathWith(listOf(bin), "", ":"))

        // Empty entries are dropped and an existing copy of the directory is not repeated.
        assertEquals(
            "${bin.absolutePath}:/usr/bin",
            GitToolInstaller.pathWith(listOf(bin), "::${bin.absolutePath}:/usr/bin:", ":")
        )

        // Windows' separator, for completeness of the helper.
        assertEquals(
            "${bin.absolutePath};C:\\Windows\\system32",
            GitToolInstaller.pathWith(listOf(bin), "C:\\Windows\\system32", ";")
        )
    }

    @Test
    fun `config carries tool-auto-download, defaulting to on`() {
        assertTrue(NodeConfigStore.parse("node.name = x\n").toolAutoDownload)

        val rendered = NodeConfigStore.render(NodeConfig(name = "x", toolAutoDownload = false))

        assertTrue(rendered.contains("tool-auto-download"), rendered)
        assertFalse(NodeConfigStore.parse(rendered).toolAutoDownload)

        // Independent of the Java switch.
        assertTrue(NodeConfigStore.parse(rendered).javaAutoDownload)
    }

    @Test
    fun `the environment overrides tool-auto-download`() {
        fun parse(value: String?) = NodeCli.parse(emptyArray()) { name ->
            if (name == NodeCli.ENV_TOOL_AUTO_DOWNLOAD) value else null
        }.toolAutoDownload

        assertNull(parse(null))
        assertEquals(false, parse("false"))
        assertEquals(true, parse("on"))
        assertNull(parse(""))
        assertThrows(IllegalArgumentException::class.java) { parse("sometimes") }
        assertEquals("PANO_NODE_TOOL_AUTO_DOWNLOAD", NodeCli.ENV_TOOL_AUTO_DOWNLOAD)
    }

    @Test
    fun `the version is read from what git printed`() {
        assertEquals("2.55.0", GitToolInstaller.parseVersion("git version 2.55.0\n"))
        assertEquals("2.39.5", GitToolInstaller.parseVersion("git version 2.39.5 (Apple Git-154)"))
        assertNull(GitToolInstaller.parseVersion("bash: git: command not found"))
    }
}
