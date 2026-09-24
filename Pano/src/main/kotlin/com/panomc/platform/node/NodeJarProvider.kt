package com.panomc.platform.node

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * The `pano-node.jar` this Pano ships with, as the daemon it hands out.
 *
 * Everything Pano gives to a node or a Pano Agent -- the bytes behind `GET /api/node/pano-node.jar`,
 * the checksum next to them, the "update available" a hello is answered with -- comes straight from
 * the `pano-node.zip` bundled in the Pano jar ([NodeJarBundle]), never from a file on disk. That is
 * the entire point of bundling it: a node installed from this Pano speaks the protocol this Pano
 * speaks, and no copy next to Pano, however it got there, can change what is served. The copy
 * [NodeJarSync] keeps on disk exists for one reason only, that `java -jar` needs a file to start
 * the local node from.
 *
 * The checksum and size are computed once, the first time they are asked for, and kept for the life
 * of the process: the classpath cannot change underneath a running Pano.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class NodeJarProvider {
    /** What the bundled jar is: its length in bytes and its SHA-256, lower-case hex. */
    data class Daemon(val size: Long, val sha256: String)

    private val cached = AtomicReference<Daemon?>()
    private val mutex = Mutex()

    /** Whether there is a daemon to hand out at all; only a hand-assembled Pano jar has none. */
    fun isAvailable(): Boolean = NodeJarBundle.isBundled

    /**
     * The bundled jar's size and checksum, read once off the event loop; null when there is none.
     *
     * Hashing is ten megabytes of I/O, so it happens on [Dispatchers.IO] and under a lock, so that
     * a burst of hellos at boot does not hash it once each.
     */
    suspend fun describe(): Daemon? {
        cached.get()?.let { return it }

        if (!NodeJarBundle.isBundled) {
            return null
        }

        return mutex.withLock {
            cached.get() ?: withContext(Dispatchers.IO) { digest() }?.also { cached.set(it) }
        }
    }

    /** The bundled jar's SHA-256, or null when there is none. */
    suspend fun sha256(): String? = describe()?.sha256

    /**
     * The checksum [describe] last computed, without computing it. For callers that cannot suspend
     * (the server JSON a Pano Agent's "update available" is shown in); boot warms it, and until then
     * the answer is "not known", which [NodeUpdateAvailability] never turns into "yes".
     */
    fun cachedSha256(): String? = cached.get()?.sha256

    /**
     * The checksum file's contents, naming the jar [fileName] -- `pano-node.jar`, or `pano-agent.jar`
     * for the copy a Pano Agent is downloaded as, so `sha256sum -c` works next to either.
     *
     * Two spaces between the digest and the name is the `sha256sum` format, which is what the
     * installers cut the first field out of and what a human can feed to `sha256sum -c`.
     */
    suspend fun checksumBody(fileName: String = LocalNodeJarLocator.JAR_NAME): String? =
        sha256()?.let { "$it  $fileName\n" }

    private fun digest(): Daemon? {
        val jar = NodeJarBundle.openJar() ?: return null

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L

        jar.use { stream ->
            while (true) {
                val read = stream.read(buffer)

                if (read < 0) {
                    break
                }

                digest.update(buffer, 0, read)
                size += read
            }
        }

        return Daemon(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    companion object {
        const val BUFFER_SIZE = 1 shl 16
    }
}
