package com.panomc.node.util

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pulls a build artifact onto disk and reports how far it got.
 *
 * The JDK's own client rather than Vert.x's: every upstream a server jar comes from (Mojang,
 * PaperMC, Purpur, Fabric, GitHub) answers with a chain of redirects, some of them across hosts,
 * and this one follows them while handing back a plain [java.io.InputStream] that a worker thread
 * can pump with a byte counter. It is always called off the event loop.
 */
object Downloader {
    /** How long the daemon waits for the first byte before giving up on an upstream. */
    private val CONNECT_TIMEOUT = Duration.ofSeconds(30)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * One moment of a download, for a progress line that says more than a percentage.
     *
     * [total] is -1 when the upstream sent no length; [bytesPerSecond] is null until two readings
     * are far enough apart to divide, and smoothed after that so it does not jump on every read.
     */
    data class Progress(val done: Long, val total: Long, val bytesPerSecond: Long?) {
        /** 0..1, or null when the size is unknown. */
        val fraction: Double? get() = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else null
    }

    /** How often [download] hands [Progress] to its caller; a task frame is throttled further. */
    const val BYTES_INTERVAL_MS = 250L

    /** Who hears about the downloads this thread makes without asking for [download]'s `onBytes`. */
    private val observer = ThreadLocal<((Progress) -> Unit)?>()

    /**
     * Runs [block] with every download it makes on this thread reported to [listener].
     *
     * A task is many steps deep by the time something downloads -- a JDK three calls into a
     * BuildTools install, a modpack's archive inside an import -- and threading a byte callback
     * through each of them is how the Java download ended up saying nothing but "Downloading".
     * A download that passes its own `onBytes` is its caller's to report and does not come here.
     */
    fun <T> observing(listener: (Progress) -> Unit, block: () -> T): T {
        val previous = observer.get()

        observer.set(listener)

        try {
            return block()
        } finally {
            observer.set(previous)
        }
    }

    /**
     * Downloads [url] to [target], calling [onProgress] with a 0..1 fraction as it goes, and
     * [onBytes] -- when given -- with the bytes so far, the size and the rate, every
     * [BYTES_INTERVAL_MS] and once more at the end.
     *
     * The fraction is only meaningful when the upstream sent a content length; without one the
     * callback is never invoked and the caller keeps whatever percent it had.
     *
     * Whichever of [md5], [sha1] and [sha256] the caller was given is checked once the file is
     * complete, and a mismatch throws with the file already deleted -- half a jar under the right
     * name is the failure mode this exists to prevent. Absent checksums are not an error: most of
     * the upstreams a server jar comes from publish none, and refusing those would mean refusing
     * to install anything.
     */
    fun download(
        url: String,
        target: File,
        md5: String? = null,
        sha1: String? = null,
        sha256: String? = null,
        onBytes: ((Progress) -> Unit)? = null,
        onProgress: (Double) -> Unit
    ) {
        val bytesListener = onBytes ?: observer.get()

        val uri = URI.create(url)

        require(uri.scheme == "http" || uri.scheme == "https") { "Refused to download from \"${uri.scheme}\"." }

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "pano-node")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() !in 200..299) {
            response.body().close()

            throw IllegalStateException("Download failed with HTTP ${response.statusCode()}.")
        }

        val total = response.headers().firstValueAsLong("content-length").orElse(-1L)

        target.parentFile?.mkdirs()

        var copied = 0L
        var lastReported = -1

        val startedAt = System.currentTimeMillis()
        var bytesAt = startedAt
        var bytesDone = 0L
        var rate: Double? = null

        fun reportBytes(now: Long) {
            val callback = bytesListener ?: return
            val elapsed = now - bytesAt

            if (elapsed > 0) {
                val instant = (copied - bytesDone) * 1000.0 / elapsed

                // Smoothed, so a burst after a stall does not read as a sudden 90 MB/s.
                rate = rate?.let { it * RATE_SMOOTHING + instant * (1 - RATE_SMOOTHING) } ?: instant
            }

            bytesAt = now
            bytesDone = copied

            callback(Progress(copied, total, rate?.toLong()))
        }

        response.body().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)

                while (true) {
                    val read = input.read(buffer)

                    if (read <= 0) {
                        break
                    }

                    output.write(buffer, 0, read)

                    copied += read

                    val now = System.currentTimeMillis()

                    if (bytesListener != null && now - bytesAt >= BYTES_INTERVAL_MS) {
                        reportBytes(now)
                    }

                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt()

                        if (percent != lastReported) {
                            lastReported = percent

                            onProgress(percent / 100.0)
                        }
                    }
                }
            }
        }

        // The last reading, so the line ends on the whole size rather than the one a quarter
        // second before it.
        if (bytesListener != null && copied != bytesDone) {
            reportBytes(System.currentTimeMillis())
        }

        verify(target, md5, sha1, sha256)
    }

    /** How much of the previous rate a new reading keeps. */
    private const val RATE_SMOOTHING = 0.6

    /**
     * Checks [file] against whichever checksums were published for it.
     *
     * MD5 is here because md-5's Jenkins is where BungeeCord lives and an MD5 is all a Jenkins
     * has: a weak hash somebody published still separates a finished jar from a truncated one or
     * from an error page saved under a .jar name, and that is what a download check is for.
     *
     * The file is deleted on a mismatch so no later step can find it and carry on.
     */
    fun verify(file: File, md5: String? = null, sha1: String? = null, sha256: String? = null) {
        val checks = listOf(
            "MD5" to md5,
            "SHA-1" to sha1,
            "SHA-256" to sha256
        )

        checks.forEach { (algorithm, expected) ->
            if (expected.isNullOrBlank()) {
                return@forEach
            }

            if (!FileHash.matches(file, algorithm, expected)) {
                file.delete()

                throw IllegalStateException("The download did not match its $algorithm checksum.")
            }
        }
    }

    /**
     * Whether [file] starts with the local-file-header magic every zip (and therefore every jar)
     * begins with. An HTML error page saved under a .jar name is the failure this catches.
     */
    fun isZip(file: File): Boolean {
        if (!file.isFile || file.length() < 4) {
            return false
        }

        return file.inputStream().use { input ->
            val header = ByteArray(4)

            if (input.read(header) != 4) {
                return@use false
            }

            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        }
    }
}
