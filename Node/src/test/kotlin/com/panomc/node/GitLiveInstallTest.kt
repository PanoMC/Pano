package com.panomc.node

import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.tools.GitToolInstaller
import com.panomc.node.tools.GitToolResolver
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.Sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real `pano-git-<os>-<arch>.tar.gz` (as `scripts/build-pano-git.sh` makes it) through the real
 * installer: the pure-JVM tar reader, the relative symlinks, the execute bits and `git --version`,
 * then a clone, fetch and `am --3way` between two local repositories run by that git alone -- the
 * operations BuildTools' `applyPatches.sh` performs (SM-67).
 *
 * Opt-in, because it runs git: point `PANO_NODE_LIVE_GIT_TARBALL` at a tarball built for this
 * host, e.g. `PANO_NODE_LIVE_GIT_TARBALL=/tmp/out/pano-git-linux-x64.tar.gz ./gradlew :Node:test
 * --tests '*GitLiveInstallTest*'`. Only the release lookup is a fixture; everything after the
 * download is the code a node runs.
 */
@EnabledIfEnvironmentVariable(named = "PANO_NODE_LIVE_GIT_TARBALL", matches = ".+")
class GitLiveInstallTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var workDir: File

    private val logger = NodeLogger("live-git")

    @Test
    fun `a CI-built git installs, relocates and applies patches`() {
        val tarball = File(System.getenv("PANO_NODE_LIVE_GIT_TARBALL"))
        val target = JavaTarget.current
        val asset = GitToolResolver.assetNameFor(target)!!

        val release = """
            {"tag_name":"v-live","assets":[{"name":"$asset","size":${tarball.length()},
             "digest":"sha256:${Sha256.of(tarball)}","browser_download_url":"https://example.invalid/$asset"}]}
        """.trimIndent()

        val resolver = GitToolResolver(target, "live", { url ->
            if (url.endsWith("/releases/tags/vlive")) {
                JavaPackageResolver.Http.Response(200, release)
            } else {
                JavaPackageResolver.Http.Response(404, "")
            }
        })

        val installer = GitToolInstaller(
            dataDir,
            resolver,
            logger,
            target,
            fetch = { _, file, onFraction ->
                tarball.copyTo(file, overwrite = true)
                onFraction(1.0)
            }
        )

        val git = installer.ensure { percent, message -> logger.info("git: $percent% $message") }

        logger.info("Installed git ${git.version} at ${git.home}")

        assertTrue(git.home.absolutePath.startsWith(File(dataDir, "tools/git").absolutePath))

        // Relocatable: the exec path follows the tree to wherever the node put it.
        assertEquals(
            File(git.home, "libexec/git-core").canonicalPath,
            File(run(git, workDir, "--exec-path").trim()).canonicalPath
        )

        // applyPatches.sh in miniature, with nothing but this git on the PATH.
        val upstream = File(workDir, "upstream").apply { mkdirs() }

        run(git, upstream, "init", "-q")
        File(upstream, "a.txt").writeText("one\n")
        run(git, upstream, "add", "a.txt")
        run(git, upstream, "commit", "-qm", "one")
        File(upstream, "a.txt").appendText("two\n")
        run(git, upstream, "commit", "-qam", "two")
        run(git, upstream, "format-patch", "-q", "-1", "-o", "../patches")
        run(git, upstream, "reset", "-q", "--hard", "HEAD~1")

        run(git, workDir, "clone", "-q", "upstream", "downstream")

        val downstream = File(workDir, "downstream")
        val patch = File(workDir, "patches").listFiles()!!.single()

        run(git, downstream, "fetch", "-q", "origin")
        run(git, downstream, "am", "-q", "--3way", patch.absolutePath)

        assertEquals("one\ntwo\n", File(downstream, "a.txt").readText())
    }

    private fun run(git: com.panomc.node.tools.ManagedGit, directory: File, vararg args: String): String {
        // Through sh, as BuildTools runs applyPatches.sh: `git` is then looked up on the PATH set
        // below. A bare "git" handed to ProcessBuilder would be looked up on this JVM's own PATH.
        val builder = ProcessBuilder(listOf("/bin/sh", "-c", "exec git \"$@\"", "git") + args)
            .directory(directory)
            .redirectErrorStream(true)

        val environment = builder.environment()

        // The managed git first, then only what sh needs: no system git can answer instead.
        environment["PATH"] = GitToolInstaller.pathWith(listOf(git.binDir), "/bin:/usr/bin")
            .split(File.pathSeparator)
            .filter { it == git.binDir.absolutePath || !File(it, "git").exists() }
            .joinToString(File.pathSeparator)
        environment["HOME"] = workDir.absolutePath
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_AUTHOR_NAME"] = "t"
        environment["GIT_AUTHOR_EMAIL"] = "t@t"
        environment["GIT_COMMITTER_NAME"] = "t"
        environment["GIT_COMMITTER_EMAIL"] = "t@t"

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), "git ${args.joinToString(" ")}: $output")

        return output
    }
}
