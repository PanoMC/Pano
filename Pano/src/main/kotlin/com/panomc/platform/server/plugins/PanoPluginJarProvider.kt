package com.panomc.platform.server.plugins

import com.panomc.platform.AppConstants
import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.server.ServerType
import io.vertx.core.Vertx
import io.vertx.core.file.OpenOptions
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The Pano plugin build an update would put in a server, as bytes Pano holds and can vouch for.
 *
 * [ManagedPluginJarResolver] answers "where does the jar come from" with a URL, which is all a
 * fresh install needs: the node downloads whatever is there and writes the credentials beside it.
 * An update needs more than a URL. It replaces something that works, so whoever receives it must be
 * able to check that what arrived is what was meant — a SHA-256 and a size — and a linked server's
 * plugin cannot be sent to GitHub for it at all, because it downloads through Pano with its own
 * token (`GET /api/server/pano-plugin/jar`). Both needs have the same answer: Pano has the jar.
 *
 * A development jar (`managed-servers.plugin-jar-dir`) is already on this disk and is used where it
 * is. A release asset is fetched once into `.temp/pano-plugin-jars/<platform>/`, which Pano empties
 * on every start, and reused for as long as the release is the newest one; the previous release's
 * file is removed when a newer one lands, so the directory never holds more than one jar per
 * platform.
 *
 * Nothing here throws for the ordinary failures. No module for this software, GitHub unreachable,
 * a download that turned out to be an HTML error page: each of them is null, and the caller reports
 * the update as unavailable rather than starting a task that would only fail on the other side.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoPluginJarProvider(
    private val managedPluginJarResolver: ManagedPluginJarResolver,
    private val webClient: WebClient,
    private val vertx: Vertx,
    private val logger: Logger
) {
    /**
     * One jar, ready to be handed out.
     *
     * [nodeUrl] is what a node fetches it from — the published development jar or the release
     * asset, exactly as a fresh install would — and is deliberately not [file]: a node on another
     * machine cannot read Pano's disk, and a plugin never gets this URL at all.
     */
    data class PreparedJar(
        val platform: String,
        val file: File,
        val fileName: String,
        /** The release version, or [ManagedPluginJarResolver.LOCAL_BUILD] for a development jar. */
        val version: String?,
        val sha256: String,
        val size: Long,
        val nodeUrl: String
    )

    /** A hash together with the file identity it was computed from, like [com.panomc.platform.node.NodeJarProvider]'s. */
    private data class Checksum(val lastModified: Long, val size: Long, val hex: String)

    private val checksums = ConcurrentHashMap<String, Checksum>()

    /** One download at a time per platform: "update all" asks for the same jar once per server. */
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * The newest version an update for [type] would install, looking it up if nobody has yet.
     *
     * The waiting twin of [ManagedPluginJarResolver.latestVersionOrWarm], for requests where an
     * admin just asked for an update and a few seconds of GitHub are better than an answer of
     * "unknown". Null when this software has no plugin or the release could not be reached.
     */
    suspend fun latestVersion(type: ServerType): String? {
        ManagedPluginJarResolver.platformOf(type) ?: return null

        managedPluginJarResolver.latestVersionOrWarm(type)?.let { return it }

        // Fills the release cache the version is read from; the URL itself is not needed here.
        managedPluginJarResolver.resolve(type) ?: return null

        return managedPluginJarResolver.latestVersionOrWarm(type)
    }

    /** The jar an update of [type] would install, fetched and hashed, or null when there is none. */
    suspend fun prepare(type: ServerType): PreparedJar? {
        val platform = ManagedPluginJarResolver.platformOf(type) ?: return null

        managedPluginJarResolver.localJarFor(platform, warn = false)?.let { jar ->
            return describe(
                platform = platform,
                file = jar,
                fileName = jar.name,
                version = ManagedPluginJarResolver.LOCAL_BUILD,
                nodeUrl = ManagedPluginJarResolver.pluginJarPath(platform)
            )
        }

        val url = managedPluginJarResolver.resolve(type) ?: return null

        // The resolver answers with a path when a development directory appeared between the two
        // calls; that jar is the one it would install, so it is the one to describe.
        if (url.startsWith("/")) {
            val jar = managedPluginJarResolver.localJarFor(platform, warn = false) ?: return null

            return describe(platform, jar, jar.name, ManagedPluginJarResolver.LOCAL_BUILD, url)
        }

        val version = managedPluginJarResolver.latestVersionOrWarm(type)
        val fileName = assetFileName(url, platform, version)

        val cached = downloadLocks.computeIfAbsent(platform) { Mutex() }.withLock {
            fetch(url, cacheDirectory(platform), fileName)
        } ?: return null

        return describe(platform, cached, fileName, version, url)
    }

    private suspend fun describe(
        platform: String,
        file: File,
        fileName: String,
        version: String?,
        nodeUrl: String
    ): PreparedJar? {
        if (!file.isFile) {
            return null
        }

        val size = file.length()

        return PreparedJar(
            platform = platform,
            file = file,
            fileName = fileName,
            version = version,
            sha256 = sha256(file),
            size = size,
            nodeUrl = nodeUrl
        )
    }

    /**
     * The jar's SHA-256, hashed once per version of the file.
     *
     * Keyed by path and checked against size and modification time, because a development jar is
     * rebuilt in place under the same name several times an hour and every rebuild is a new jar.
     */
    private suspend fun sha256(file: File): String {
        val key = file.absolutePath
        val lastModified = file.lastModified()
        val size = file.length()

        checksums[key]?.takeIf { it.lastModified == lastModified && it.size == size }?.let { return it.hex }

        val hex = withContext(Dispatchers.IO) { sha256Of(file) }

        checksums[key] = Checksum(lastModified, size, hex)

        return hex
    }

    /**
     * The release asset [url] as a file in [directory], downloading it when it is not there yet.
     *
     * Lands on a `.part` first and is moved onto its real name only once it has been checked to be
     * a jar at all, so a request that is served while a download is still running can never hand
     * out half a file, and a CDN error page is never mistaken for a plugin.
     */
    private suspend fun fetch(url: String, directory: File, fileName: String): File? {
        val target = File(directory, fileName)

        if (target.isFile && target.length() > 0) {
            return target
        }

        directory.mkdirs()

        val part = File(directory, "$fileName$PART_SUFFIX")

        part.delete()

        return try {
            val stream = vertx.fileSystem()
                .open(part.absolutePath, OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true))
                .coAwait()

            val response = webClient
                .getAbs(url)
                .timeout(DOWNLOAD_TIMEOUT_MS)
                .`as`(BodyCodec.pipe(stream))
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                logger.warn("Downloading the Pano plugin from $url answered ${response.statusCode()}.")

                part.delete()

                return null
            }

            withContext(Dispatchers.IO) {
                if (!isZip(part)) {
                    throw IllegalStateException("$url is not a jar.")
                }

                moveIntoPlace(part, target)

                // Only the newest release is ever served; an older one would just sit here.
                directory.listFiles()
                    ?.filter { it.isFile && it.name != target.name }
                    ?.forEach { it.delete() }
            }

            target
        } catch (e: Exception) {
            logger.warn("Could not fetch the Pano plugin from $url: ${e.message}")

            part.delete()

            null
        }
    }

    private fun cacheDirectory(platform: String): File =
        File(File(AppConstants.TEMP_FOLDER, CACHE_DIRECTORY), platform)

    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Under [AppConstants.TEMP_FOLDER], which Pano clears on every start. */
        const val CACHE_DIRECTORY = "pano-plugin-jars"

        private const val PART_SUFFIX = ".part"

        /** A plugin jar is a few megabytes; two minutes covers a slow mirror without hanging a request forever. */
        private const val DOWNLOAD_TIMEOUT_MS = 120_000L

        /**
         * The name a release asset is kept and served under.
         *
         * The asset's own name when it is one ([ManagedPluginJarResolver.matchesAsset], a single safe
         * segment), because that is what a node would have called it and what an admin recognises
         * in a directory listing. Anything else — a redirect URL, a query string, a name some future
         * release format invents — becomes `pano-<platform>-<version>.jar`, built only from values
         * Pano chose.
         */
        fun assetFileName(url: String, platform: String, version: String?): String {
            val raw = try {
                URI(url).path.orEmpty().substringAfterLast('/')
            } catch (_: Exception) {
                ""
            }

            if (ManagedPluginJarResolver.matchesAsset(raw, platform) && PluginFileNaming.isJarName(raw)) {
                return raw
            }

            val safeVersion = version
                ?.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '+' }
                // No run of dots survives, so the name can never read as a parent directory.
                ?.replace(DOT_RUN, ".")
                ?.trim('.', '-')
                ?.takeIf { it.isNotEmpty() }
                ?: "latest"

            return "${ManagedPluginJarResolver.assetPrefix(platform)}$safeVersion.jar"
        }

        /** Hex SHA-256 of [file], streamed rather than read whole. */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")

            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    val read = input.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    digest.update(buffer, 0, read)
                }
            }

            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Whether [file] starts with the zip local-header magic every jar starts with. */
        fun isZip(file: File): Boolean {
            if (!file.isFile || file.length() < ZIP_MAGIC.size) {
                return false
            }

            val header = ByteArray(ZIP_MAGIC.size)

            file.inputStream().use { input ->
                if (input.read(header) != ZIP_MAGIC.size) {
                    return false
                }
            }

            return header.contentEquals(ZIP_MAGIC)
        }

        private const val BUFFER_SIZE = 64 * 1024

        private val DOT_RUN = Regex("[.]{2,}")

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    }
}
