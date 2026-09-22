package com.panomc.platform.node

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.util.HashUtil.hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The `pano-node.jar` this install has on disk, and its checksum.
 *
 * [LocalNodeJarLocator] decides *where* the jar is; this is the one place that asks it in a
 * running Pano, so the daemon Pano runs locally and the daemon it hands to a remote host over
 * `GET /api/node/pano-node.jar` can never be two different builds. That is the entire point of
 * serving it from here: a node installed from this Pano speaks the protocol this Pano speaks,
 * with no release having to exist anywhere on the internet first.
 *
 * The checksum is cached against the file's identity rather than for a duration, because the only
 * thing that can invalidate it is the file changing — rebuilding the jar during development is
 * exactly that, and a size or timestamp change re-hashes it on the next request without anyone
 * having to restart Pano.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeJarProvider(
    private val configManager: ConfigManager
) {
    /** A hash together with the file identity it was computed from. */
    private data class Checksum(
        val path: String,
        val lastModified: Long,
        val size: Long,
        val hex: String
    ) {
        fun describes(file: File) =
            path == file.absolutePath && lastModified == file.lastModified() && size == file.length()
    }

    private val cached = AtomicReference<Checksum?>()

    /** The jar this Pano would run as a node, or null when there is none to serve. */
    fun locate(): File? = LocalNodeJarLocator.locate(
        configuredPath = configManager.config.effectiveLocalNode.jarPath,
        systemProperty = System.getProperty(LocalNodeJarLocator.JAR_PROPERTY),
        workingDir = File("").absoluteFile,
        runningJarDir = runningJarDirectory()
    )

    /** Whether an installer can be pointed at this Pano instead of at a GitHub release. */
    fun isAvailable(): Boolean = locate() != null

    /**
     * The jar's SHA-256, hashed once per version of the file.
     *
     * Hashing is ten megabytes of I/O, so it happens off the event loop; the cache is what keeps
     * a node that retries a download from paying for it again.
     */
    suspend fun sha256(jar: File): String {
        cached.get()?.takeIf { it.describes(jar) }?.let { return it.hex }

        val snapshot = Checksum(jar.absolutePath, jar.lastModified(), jar.length(), "")
        val hex = withContext(Dispatchers.IO) { jar.inputStream().use { it.hash() } }

        cached.set(snapshot.copy(hex = hex))

        return hex
    }

    /**
     * The checksum [sha256] last computed, when it still describes the jar [locate] finds now, and
     * null otherwise -- without hashing anything. For callers that cannot suspend (the server JSON
     * a Pano Agent's "update available" is shown in); a hello or the nodes page hashes it soon
     * enough, and until then the answer is "not known", which [NodeUpdateAvailability] never
     * turns into "yes".
     */
    fun cachedSha256(): String? {
        val jar = locate() ?: return null

        return cached.get()?.takeIf { it.describes(jar) && it.hex.isNotEmpty() }?.hex
    }

    /**
     * The checksum file's contents, naming the jar [fileName] -- `pano-node.jar`, or `pano-agent.jar`
     * for the copy a Pano Agent is downloaded as, so `sha256sum -c` works next to either.
     *
     * Two spaces between the digest and the name is the `sha256sum` format, which is what the
     * installers cut the first field out of and what a human can feed to `sha256sum -c`.
     */
    suspend fun checksumBody(jar: File, fileName: String = LocalNodeJarLocator.JAR_NAME): String =
        "${sha256(jar)}  $fileName\n"

    /** Where the running Pano jar lives, which is where a release install keeps the daemon. */
    fun runningJarDirectory(): File? = try {
        val location = Main::class.java.protectionDomain?.codeSource?.location

        val file = location?.let { File(it.toURI()) }

        if (file != null && file.isFile) file.parentFile else null
    } catch (_: Exception) {
        null
    }
}
