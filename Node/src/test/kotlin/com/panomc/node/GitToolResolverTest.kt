package com.panomc.node

import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import com.panomc.node.tools.GitToolResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The portable-git lookup (SM-67, §2.4.32) against GitHub release JSON shaped like the real API's:
 * the release of the node's own version first, the newest release carrying git when that has
 * none, a checksum from the asset `digest` or the `.sha256` asset, and nothing at all for a host
 * no release builds git for. No network: every URL is answered from the fixtures here.
 */
class GitToolResolverTest {
    private val linux = JavaTarget("linux", "x64")

    private val digest = "a8b9308a22f907dbb5e9b5c68a31fab281204bf6eaf4e8b55820a224abf194d2"
    private val otherDigest = "1111111111111111111111111111111111111111111111111111111111111111"

    /** One release as `GET /repos/{repo}/releases/tags/{tag}` returns it (trimmed to what is read). */
    private fun release(
        tag: String,
        withGit: Boolean = true,
        withDigest: Boolean = true,
        withChecksumFile: Boolean = true,
        draft: Boolean = false,
        asset: String = "pano-git-linux-x64.tar.gz",
        sha256: String = digest
    ): String {
        val assets = mutableListOf(
            """{"name":"pano-node.jar","size":12345,"digest":"sha256:$otherDigest",
               "browser_download_url":"https://github.com/PanoMC/pano/releases/download/$tag/pano-node.jar"}"""
        )

        if (withGit) {
            val digestField = if (withDigest) ""","digest":"sha256:$sha256"""" else ""","digest":null"""

            assets.add(
                """{"name":"$asset","size":8066499$digestField,
                   "browser_download_url":"https://github.com/PanoMC/pano/releases/download/$tag/$asset"}"""
            )

            if (withChecksumFile) {
                assets.add(
                    """{"name":"$asset.sha256","size":92,
                       "browser_download_url":"https://github.com/PanoMC/pano/releases/download/$tag/$asset.sha256"}"""
                )
            }
        }

        return """{"tag_name":"$tag","draft":$draft,"prerelease":true,"assets":[${assets.joinToString(",")}]}"""
    }

    /** Answers [routes] by URL, 404 for anything else, and records every URL asked. */
    private class Routes(private val routes: Map<String, JavaPackageResolver.Http.Response>) : JavaPackageResolver.Http {
        val asked = mutableListOf<String>()

        override fun get(url: String): JavaPackageResolver.Http.Response {
            asked.add(url)

            return routes[url] ?: JavaPackageResolver.Http.Response(404, """{"message":"Not Found"}""")
        }
    }

    private fun ok(body: String) = JavaPackageResolver.Http.Response(200, body)

    private val api = "https://api.github.com/repos/PanoMC/pano"

    @Test
    fun `the release of the node's own version is used, with GitHub's digest`() {
        val http = Routes(mapOf("$api/releases/tags/v1.2.0-beta.40" to ok(release("v1.2.0-beta.40"))))

        val pkg = GitToolResolver(linux, "1.2.0-beta.40", http).resolve()

        assertNotNull(pkg)
        assertEquals("pano-git-linux-x64.tar.gz", pkg!!.fileName)
        assertEquals("https://github.com/PanoMC/pano/releases/download/v1.2.0-beta.40/pano-git-linux-x64.tar.gz", pkg.url)
        assertEquals(digest, pkg.sha256)
        assertEquals(8066499L, pkg.size)
        assertEquals("v1.2.0-beta.40", pkg.release)

        // The digest was enough: no second request for the .sha256 file, no release listing.
        assertEquals(listOf("$api/releases/tags/v1.2.0-beta.40"), http.asked)
    }

    @Test
    fun `a tag without the v prefix is tried second`() {
        val http = Routes(mapOf("$api/releases/tags/1.2.0" to ok(release("1.2.0"))))

        val pkg = GitToolResolver(linux, "1.2.0", http).resolve()

        assertEquals("1.2.0", pkg!!.release)
        assertEquals(listOf("$api/releases/tags/v1.2.0", "$api/releases/tags/1.2.0"), http.asked)
    }

    @Test
    fun `without a digest the published sha256 file is read`() {
        val tag = "v1.2.0"
        val checksumUrl = "https://github.com/PanoMC/pano/releases/download/$tag/pano-git-linux-x64.tar.gz.sha256"

        val http = Routes(
            mapOf(
                "$api/releases/tags/$tag" to ok(release(tag, withDigest = false)),
                checksumUrl to ok("${digest.uppercase()}  pano-git-linux-x64.tar.gz\n")
            )
        )

        val pkg = GitToolResolver(linux, "1.2.0", http).resolve()

        assertEquals(digest, pkg!!.sha256)
        assertTrue(http.asked.contains(checksumUrl))
    }

    @Test
    fun `an asset with no checksum at all is not offered`() {
        val http = Routes(
            mapOf(
                "$api/releases/tags/v1.2.0" to ok(release("v1.2.0", withDigest = false, withChecksumFile = false)),
                "$api/releases?per_page=30" to ok("[${release("v1.2.0", withDigest = false, withChecksumFile = false)}]")
            )
        )

        assertNull(GitToolResolver(linux, "1.2.0", http).resolve())
    }

    @Test
    fun `a development build falls back to the newest release that carries git`() {
        val listing = "[" + listOf(
            release("v1.3.0-alpha.2", draft = true, sha256 = otherDigest),
            release("v1.3.0-alpha.1", withGit = false),
            release("v1.2.9"),
            release("v1.2.8", sha256 = otherDigest)
        ).joinToString(",") + "]"

        val http = Routes(mapOf("$api/releases?per_page=30" to ok(listing)))

        val pkg = GitToolResolver(linux, "local-build", http).resolve()

        // The draft is skipped, the release without git is skipped, the newest with git wins.
        assertEquals("v1.2.9", pkg!!.release)
        assertEquals(digest, pkg.sha256)

        // `local-build` has no release of its own, so no tag was even asked for.
        assertEquals(listOf("$api/releases?per_page=30"), http.asked)
    }

    @Test
    fun `a release from before git was published falls back to the listing`() {
        val http = Routes(
            mapOf(
                "$api/releases/tags/v1.1.0" to ok(release("v1.1.0", withGit = false)),
                "$api/releases?per_page=30" to ok("[${release("v1.2.0")}]")
            )
        )

        assertEquals("v1.2.0", GitToolResolver(linux, "1.1.0", http).resolve()!!.release)
    }

    @Test
    fun `no release with git for this host is null, not an error`() {
        val http = Routes(mapOf("$api/releases?per_page=30" to ok("[${release("v1.2.0", withGit = false)}]")))

        assertNull(GitToolResolver(linux, "local-build", http).resolve())
    }

    @Test
    fun `each host gets its own asset, and hosts without one ask nothing`() {
        assertEquals("pano-git-linux-x64.tar.gz", GitToolResolver.assetNameFor(JavaTarget("linux", "x64")))
        assertEquals("pano-git-linux-arm64.tar.gz", GitToolResolver.assetNameFor(JavaTarget("linux", "arm64", musl = true)))
        assertEquals("pano-git-macos-x64.tar.gz", GitToolResolver.assetNameFor(JavaTarget("macos", "x64")))
        assertEquals("pano-git-macos-arm64.tar.gz", GitToolResolver.assetNameFor(JavaTarget("macos", "arm64")))
        assertNull(GitToolResolver.assetNameFor(JavaTarget("windows", "x64")))
        assertNull(GitToolResolver.assetNameFor(JavaTarget("linux", "arm32")))

        val http = Routes(emptyMap())

        assertNull(GitToolResolver(JavaTarget("windows", "x64"), "1.2.0", http).resolve())
        assertTrue(http.asked.isEmpty())
    }

    @Test
    fun `the macOS arm64 asset is picked out of a release carrying all four`() {
        val tag = "v1.2.0"
        val body = release(tag, asset = "pano-git-macos-arm64.tar.gz", sha256 = otherDigest)
            .replace(
                "\"assets\":[",
                "\"assets\":[{\"name\":\"pano-git-linux-x64.tar.gz\",\"digest\":\"sha256:$digest\"," +
                    "\"browser_download_url\":\"https://example.invalid/linux\"},"
            )

        val http = Routes(mapOf("$api/releases/tags/$tag" to ok(body)))

        val pkg = GitToolResolver(JavaTarget("macos", "arm64"), "1.2.0", http).resolve()

        assertEquals("pano-git-macos-arm64.tar.gz", pkg!!.fileName)
        assertEquals(otherDigest, pkg.sha256)
    }

    @Test
    fun `GitHub failing is an error rather than no git`() {
        val http = Routes(mapOf("$api/releases/tags/v1.2.0" to JavaPackageResolver.Http.Response(403, "rate limited")))

        assertThrows(GitToolResolver.LookupFailedException::class.java) {
            GitToolResolver(linux, "1.2.0", http).resolve()
        }

        val throwing = JavaPackageResolver.Http { throw java.io.IOException("api.github.com: unreachable") }

        val failure = assertThrows(GitToolResolver.LookupFailedException::class.java) {
            GitToolResolver(linux, "local-build", throwing).resolve()
        }

        assertTrue(failure.message!!.contains("unreachable"))
    }

    @Test
    fun `release tags and checksum files are read strictly`() {
        assertEquals(listOf("v1.2.0", "1.2.0"), GitToolResolver.releaseTags("1.2.0"))
        assertEquals(emptyList<String>(), GitToolResolver.releaseTags("local-build"))
        assertEquals(emptyList<String>(), GitToolResolver.releaseTags(""))
        assertEquals(emptyList<String>(), GitToolResolver.releaseTags("../x"))

        assertEquals(digest, GitToolResolver.parseChecksumFile("$digest  pano-git-linux-x64.tar.gz"))
        assertEquals(digest, GitToolResolver.parseChecksumFile(digest))
        assertNull(GitToolResolver.parseChecksumFile("<html>Not Found</html>"))
        assertNull(GitToolResolver.parseChecksumFile(""))
    }
}
