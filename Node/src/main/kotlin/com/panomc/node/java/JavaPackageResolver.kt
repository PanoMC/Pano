package com.panomc.node.java

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * One downloadable Java runtime, in the shape the installer and `JAVA_CATALOG` both need.
 *
 * [version] is the vendor's own full version (`21.0.12.1+1` for Temurin, `16.0.2+7` for Zulu) and
 * becomes part of the directory the runtime is installed into, which is what lets an update land
 * side by side with the version a running server is still using.
 */
data class JavaPackage(
    /** `temurin` or `zulu`. */
    val vendor: String,
    val major: Int,
    val version: String,
    val url: String,
    /** Hex SHA-256 the vendor published for the archive; the download is refused without it. */
    val sha256: String,
    /** Archive size in bytes, when the vendor said. */
    val size: Long?,
    /** `tar.gz` or `zip`. */
    val archive: String,
    /** The archive's file name upstream, used for the cached download. */
    val fileName: String
)

/**
 * Finds the JRE to download for one Java major on this host (SM-63, §2.4.28).
 *
 * Eclipse Temurin first, because it is the build most Minecraft hosting guides and Paper's own
 * docs point at; Azul Zulu second, because it fills exactly the holes Temurin leaves (verified
 * 2026-09-23): there is no Temurin 16 for any platform at all, no Temurin 8 for Apple silicon and
 * no Temurin 8/11/17/25 for Windows on ARM. Where neither has a build -- Java 8 on Windows ARM --
 * the answer is null, which the catalog shows as "not available" rather than an error.
 *
 * The distinction between "no build exists" and "could not ask" is kept all the way through:
 * [resolve] returns null for the first and throws for the second, because a catalog that turned
 * a network outage into "Java 21 is not available for your host" would be telling a lie somebody
 * then acts on.
 *
 * Answers are cached for an hour -- the catalog is opened far more often than a JRE is released --
 * and failures for a minute, so an offline host that is asked to start five servers does not wait
 * out five sets of timeouts in a row.
 */
class JavaPackageResolver(
    private val target: JavaTarget = JavaTarget.current,
    private val http: Http = Http.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The one thing this class does over the network, as a seam so tests can serve fixtures. */
    fun interface Http {
        /** GETs [url] and returns its status and body, throwing only when nothing came back. */
        fun get(url: String): Response

        data class Response(val status: Int, val body: String)

        companion object {
            val DEFAULT: Http by lazy { JdkHttp() }
        }
    }

    /** Thrown when neither source could be asked, as opposed to a source that has no build. */
    class LookupFailedException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private data class CacheEntry(val found: JavaPackage?, val error: String?, val expiresAt: Long)

    /** Keyed by major and whether a JDK was asked for: the two are different archives. */
    private val cache = ConcurrentHashMap<Pair<Int, Boolean>, CacheEntry>()

    /** The host this resolver answers for, which the catalog reports back to Pano. */
    val host: JavaTarget get() = target

    /**
     * The package to install for Java [major], or null when neither source builds one for this
     * host. Throws [LookupFailedException] when that cannot be known right now.
     *
     * A JRE unless [jdk]: every server runs on one, and it is a quarter of the download. Only a
     * build (BuildTools) needs the compiler a JDK adds.
     */
    fun resolve(major: Int, jdk: Boolean = false): JavaPackage? {
        val now = clock()
        val key = major to jdk

        cache[key]?.takeIf { it.expiresAt > now }?.let { entry ->
            entry.error?.let { throw LookupFailedException(it) }

            return entry.found
        }

        return try {
            val found = lookup(major, jdk)

            cache[key] = CacheEntry(found, null, now + CACHE_MILLIS)

            found
        } catch (exception: LookupFailedException) {
            cache[key] = CacheEntry(null, exception.message, now + FAILURE_CACHE_MILLIS)

            throw exception
        }
    }

    /** Forgets every cached answer, so the next question goes to the network. */
    fun invalidate() {
        cache.clear()
    }

    private fun lookup(major: Int, jdk: Boolean): JavaPackage? {
        if (major !in 1..MAX_MAJOR) {
            return null
        }

        // Temurin's silence is only trusted when it actually answered: a failed request falls
        // through to Zulu as well, and the error is only raised if Zulu cannot answer either.
        val temurinFailure: Exception? = try {
            temurin(major, jdk)?.let { return it }

            null
        } catch (exception: Exception) {
            exception
        }

        val zulu = try {
            zulu(major, jdk)
        } catch (exception: Exception) {
            val reason = temurinFailure?.message?.let { "Temurin: $it; Zulu: ${exception.message}" }
                ?: exception.message
                ?: exception.javaClass.simpleName

            throw LookupFailedException("Could not look up Java $major downloads ($reason)", exception)
        }

        if (zulu == null && temurinFailure != null) {
            // Zulu has nothing, but Temurin never got to say: the honest answer is "unknown".
            throw LookupFailedException(
                "Could not look up Java $major downloads (Temurin: ${temurinFailure.message})",
                temurinFailure
            )
        }

        return zulu
    }

    private fun temurin(major: Int, jdk: Boolean): JavaPackage? {
        val os = target.temurinOs ?: return null
        val arch = target.temurinArch ?: return null

        val url = "$TEMURIN_API/v3/assets/latest/$major/hotspot?os=${encode(os)}" +
            "&architecture=${encode(arch)}&image_type=${if (jdk) "jdk" else "jre"}&vendor=eclipse"

        val response = http.get(url)

        // Adoptium answers an unknown major with a 404 and a known one with no build here as an
        // empty list; both mean the same thing to this node.
        if (response.status == 404) {
            return null
        }

        requireOk(response, "Temurin")

        return parseTemurin(response.body, major)
    }

    private fun zulu(major: Int, jdk: Boolean): JavaPackage? {
        val os = target.zuluOs ?: return null
        val arch = target.zuluArch ?: return null

        val url = "$ZULU_API/metadata/v1/zulu/packages/?java_version=$major&os=${encode(os)}" +
            "&arch=${encode(arch)}&java_package_type=${if (jdk) "jdk" else "jre"}&archive_type=${encode(target.archive)}" +
            "&javafx_bundled=false&latest=true&release_status=ga&availability_types=CA&page_size=1"

        val response = http.get(url)

        if (response.status == 404) {
            return null
        }

        requireOk(response, "Zulu")

        val listed = parseZuluList(response.body, major) ?: return null

        // The list does not carry the checksum, and a runtime with no checksum is not installed.
        val details = http.get("$ZULU_API/metadata/v1/zulu/packages/${encode(listed.uuid)}")

        requireOk(details, "Zulu")

        return parseZuluDetails(details.body, listed)
    }

    private fun requireOk(response: Http.Response, source: String) {
        if (response.status !in 200..299) {
            throw LookupFailedException("$source answered HTTP ${response.status}")
        }
    }

    /** What the Zulu list call says about a package, before its details are fetched. */
    data class ZuluListing(
        val uuid: String,
        val major: Int,
        val version: String,
        val url: String,
        val fileName: String,
        val archive: String
    )

    private class JdkHttp : Http {
        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        override fun get(url: String): Http.Response {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "pano-node")
                .header("Accept", "application/json")
                .GET()
                .build()

            return try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())

                Http.Response(response.statusCode(), response.body() ?: "")
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()

                throw LookupFailedException("Interrupted", exception)
            } catch (exception: Exception) {
                throw LookupFailedException(
                    "${URI.create(url).host}: ${exception.message ?: exception.javaClass.simpleName}",
                    exception
                )
            }
        }
    }

    companion object {
        const val TEMURIN_API = "https://api.adoptium.net"
        const val ZULU_API = "https://api.azul.com"

        const val VENDOR_TEMURIN = "temurin"
        const val VENDOR_ZULU = "zulu"

        /** The majors the catalog offers: every one [com.panomc.node.server.MinecraftJavaVersions] can ask for. */
        val OFFERED_MAJORS = listOf(8, 11, 16, 17, 21, 25)

        const val CACHE_MILLIS = 60 * 60 * 1000L
        const val FAILURE_CACHE_MILLIS = 60 * 1000L

        private const val MAX_MAJOR = 99

        /** Per request, connect and read alike (§2.4.28: 10 s). */
        val TIMEOUT: Duration = Duration.ofSeconds(10)

        private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

        /**
         * Reads Adoptium's `assets/latest` answer. Null for an empty list or a release with no
         * package link or checksum -- either way there is nothing this node can safely install.
         */
        fun parseTemurin(body: String, major: Int): JavaPackage? {
            val first = JsonArray(body.ifBlank { "[]" }).firstOrNull() as? JsonObject ?: return null

            val pkg = first.getJsonObject("binary")?.getJsonObject("package") ?: return null

            val link = pkg.getString("link")?.takeIf { it.isNotBlank() } ?: return null
            val checksum = pkg.getString("checksum")?.takeIf { it.isNotBlank() } ?: return null
            val name = pkg.getString("name")?.takeIf { it.isNotBlank() } ?: link.substringAfterLast('/')

            // `21.0.12.1+1-LTS` is the release; the `-LTS` tag says nothing about which build it
            // is and only makes the directory name longer.
            val version = first.getJsonObject("version")?.getString("openjdk_version")
                ?.removeSuffix("-LTS")
                ?.takeIf { it.isNotBlank() }
                ?: first.getString("release_name")?.removePrefix("jdk-")?.removePrefix("jdk")
                ?: return null

            return JavaPackage(
                vendor = VENDOR_TEMURIN,
                major = major,
                version = version,
                url = link,
                sha256 = checksum.lowercase(),
                size = pkg.getValue("size")?.let { (it as? Number)?.toLong() },
                archive = archiveOf(name),
                fileName = name
            )
        }

        /** Reads the first entry of Azul's package list, or null when the list is empty. */
        fun parseZuluList(body: String, major: Int): ZuluListing? {
            val first = JsonArray(body.ifBlank { "[]" }).firstOrNull() as? JsonObject ?: return null

            val uuid = first.getString("package_uuid")?.takeIf { it.isNotBlank() } ?: return null
            val url = first.getString("download_url")?.takeIf { it.isNotBlank() } ?: return null
            val name = first.getString("name")?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')

            val numbers = first.getJsonArray("java_version")
                ?.mapNotNull { (it as? Number)?.toInt() }
                .orEmpty()

            if (numbers.isEmpty()) {
                return null
            }

            val build = (first.getValue("openjdk_build_number") as? Number)?.toInt()

            val version = numbers.joinToString(".") + (build?.let { "+$it" } ?: "")

            return ZuluListing(uuid, numbers.first().takeIf { it > 1 } ?: major, version, url, name, archiveOf(name))
        }

        /** Completes a [ZuluListing] with the checksum and size only the details call carries. */
        fun parseZuluDetails(body: String, listing: ZuluListing): JavaPackage? {
            val details = JsonObject(body)

            val sha256 = details.getString("sha256_hash")?.takeIf { it.isNotBlank() } ?: return null

            return JavaPackage(
                vendor = VENDOR_ZULU,
                major = listing.major,
                version = listing.version,
                url = listing.url,
                sha256 = sha256.lowercase(),
                size = (details.getValue("size") as? Number)?.toLong(),
                archive = listing.archive,
                fileName = listing.fileName
            )
        }

        private fun archiveOf(name: String) =
            if (name.endsWith(".zip", ignoreCase = true)) JavaTarget.ZIP else JavaTarget.TAR_GZ
    }
}
