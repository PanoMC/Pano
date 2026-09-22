package com.panomc.node.tools

import com.panomc.node.NodeVersion
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaTarget
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.IOException

/**
 * One downloadable portable git, as a `pano-web-platform` release publishes it.
 *
 * [release] is the tag the asset was found on, which ends up in the install marker so an operator
 * can tell where the binary came from; the git version itself is only known once the binary has
 * run (`git --version`), and that is what the install directory is named after.
 */
data class GitPackage(
    val url: String,
    /** Hex SHA-256 of the tarball. The download is refused without one. */
    val sha256: String,
    /** Size in bytes, when GitHub said. */
    val size: Long?,
    /** `pano-git-<os>-<arch>.tar.gz`. */
    val fileName: String,
    /** The release tag the asset is attached to, e.g. `v1.2.0-beta.40`. */
    val release: String
)

/**
 * Finds the portable git this node downloads when BuildTools needs one and the host has none
 * (SM-67, §2.4.32).
 *
 * There is no official portable git for Linux or macOS, so Pano's own release CI builds one --
 * static musl on Linux, system-libraries-only on macOS, no curl/OpenSSL/Perl/Python because
 * BuildTools only ever runs local git commands -- and attaches it to every `pano-web-platform`
 * release as `pano-git-<os>-<arch>.tar.gz` next to `pano-node.jar`. This resolver finds that asset
 * the same way Pano finds the node jar for its local node: the release whose tag is this node's
 * own version (`v<version>`, then `<version>`).
 *
 * Unlike the node jar, git does not have to match the node's version at all, so when that release
 * does not exist (a development build calls itself `local-build`) or predates SM-67 and carries
 * no git, the newest release that does carry one is used instead.
 *
 * The checksum comes from GitHub's own asset `digest` (`sha256:<hex>`, computed by GitHub on
 * upload) or, for an asset without one, from the `.sha256` file published next to it. An asset
 * with neither is treated as absent: an unverifiable executable is never downloaded.
 *
 * Windows has no asset here on purpose. BuildTools brings its own Git for Windows there: when it
 * cannot run `sh` it downloads a checksum-pinned PortableGit into its working directory and runs
 * its patch scripts through that git-bash, which is exactly the "portable, never touches the
 * system" behaviour SM-67 wants -- and the only one BuildTools itself tests on Windows.
 */
class GitToolResolver(
    private val target: JavaTarget = JavaTarget.current,
    private val nodeVersion: String = NodeVersion.VERSION,
    private val http: JavaPackageResolver.Http = JavaPackageResolver.Http.DEFAULT,
    private val repository: String = REPOSITORY
) {
    /** Thrown when GitHub could not be asked, as opposed to a release with no git for this host. */
    class LookupFailedException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** `pano-git-linux-x64.tar.gz` for this host, or null when no release builds one for it. */
    val assetName: String? get() = assetNameFor(target)

    /**
     * The git to download for this host, or null when no release publishes one for it. Throws
     * [LookupFailedException] when GitHub could not answer.
     */
    fun resolve(): GitPackage? {
        val asset = assetName ?: return null

        var own: JsonObject? = null

        for (tag in releaseTags(nodeVersion)) {
            val response = get("$API/repos/$repository/releases/tags/$tag")

            if (response.status == 200) {
                // One tag answers; the second spelling is only for a repository that tags
                // without the `v`.
                own = json(response.body) { JsonObject(it) }

                break
            }

            if (response.status != 404) {
                throw LookupFailedException("GitHub answered HTTP ${response.status} for release $tag")
            }
        }

        own?.let { findAsset(it, asset) }?.let { return complete(it) }

        // No release of this exact version, or one from before git was published: any recent
        // release's git does the same job.
        val listing = get("$API/repos/$repository/releases?per_page=$RELEASE_PAGE_SIZE")

        if (listing.status !in 200..299) {
            throw LookupFailedException("GitHub answered HTTP ${listing.status} for the release list")
        }

        val found = json(listing.body.ifBlank { "[]" }) { JsonArray(it) }
            .asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.getBoolean("draft", false) != true }
            .firstNotNullOfOrNull { findAsset(it, asset) }
            ?: return null

        return complete(found)
    }

    private fun complete(found: FoundAsset): GitPackage? {
        found.sha256?.let { return found.toPackage(it) }

        val checksumUrl = found.checksumUrl ?: return null

        val response = get(checksumUrl)

        if (response.status !in 200..299) {
            throw LookupFailedException("The checksum of ${found.name} answered HTTP ${response.status}")
        }

        val sha256 = parseChecksumFile(response.body) ?: return null

        return found.toPackage(sha256)
    }

    /** A body GitHub sent that is not the JSON it documents is a failed lookup, not "no git". */
    private fun <T> json(body: String, parse: (String) -> T): T = try {
        parse(body)
    } catch (exception: Exception) {
        throw LookupFailedException("GitHub answered with something that is not a release: ${exception.message}", exception)
    }

    private fun get(url: String): JavaPackageResolver.Http.Response = try {
        http.get(url)
    } catch (exception: LookupFailedException) {
        throw exception
    } catch (exception: Exception) {
        throw LookupFailedException(exception.message ?: exception.javaClass.simpleName, exception)
    }

    /** An asset as a release lists it, before its checksum is known for certain. */
    data class FoundAsset(
        val name: String,
        val url: String,
        val size: Long?,
        val release: String,
        /** From the asset's `digest`, when GitHub computed one. */
        val sha256: String?,
        /** The `<name>.sha256` asset's download URL, when the release has one. */
        val checksumUrl: String?
    ) {
        fun toPackage(sha256: String) = GitPackage(url, sha256.lowercase(), size, name, release)
    }

    companion object {
        /** Where `pano-node.jar` and the git tarballs are released (Pano's `AppConstants.REPO`). */
        const val REPOSITORY = "PanoMC/pano"

        const val API = "https://api.github.com"

        /** How far back the fallback looks for a release that carries git. */
        const val RELEASE_PAGE_SIZE = 30

        private val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * `pano-git-<os>-<arch>.tar.gz`, for exactly the four builds the release CI makes: Linux
         * and macOS, x64 and arm64. Linux has no libc variant because the binary is static.
         */
        fun assetNameFor(target: JavaTarget): String? {
            val os = when (target.os) {
                "linux" -> "linux"
                "macos" -> "macos"
                else -> return null
            }

            val arch = when (target.arch) {
                "x64" -> "x64"
                "arm64" -> "arm64"
                else -> return null
            }

            return "pano-git-$os-$arch.tar.gz"
        }

        /**
         * The tags the release of [version] may carry, in the order Pano's local-node download
         * tries them. Nothing for a development build, which has no release of its own.
         */
        fun releaseTags(version: String): List<String> {
            val trimmed = version.trim()

            if (trimmed.isEmpty() || trimmed == "local-build" || trimmed.any { it.isWhitespace() || it == '/' }) {
                return emptyList()
            }

            return listOf("v$trimmed", trimmed)
        }

        /** The asset called [name] on [release], with whatever checksum source it has. */
        fun findAsset(release: JsonObject, name: String): FoundAsset? {
            val assets = release.getJsonArray("assets") ?: return null

            val objects = assets.mapNotNull { it as? JsonObject }

            val asset = objects.firstOrNull { it.getString("name") == name } ?: return null

            val url = asset.getString("browser_download_url")?.takeIf { it.isNotBlank() } ?: return null

            val digest = asset.getString("digest")
                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.takeIf { HEX_SHA256.matches(it) }

            val checksumUrl = objects
                .firstOrNull { it.getString("name") == "$name.sha256" }
                ?.getString("browser_download_url")
                ?.takeIf { it.isNotBlank() }

            if (digest == null && checksumUrl == null) {
                return null
            }

            return FoundAsset(
                name = name,
                url = url,
                size = (asset.getValue("size") as? Number)?.toLong(),
                release = release.getString("tag_name") ?: "",
                sha256 = digest,
                checksumUrl = checksumUrl
            )
        }

        /** The hash out of a `sha256sum`-format line (`<hex>  <name>`), or null when it has none. */
        fun parseChecksumFile(body: String): String? =
            body.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX_SHA256.matches(it) }?.lowercase()
    }
}
