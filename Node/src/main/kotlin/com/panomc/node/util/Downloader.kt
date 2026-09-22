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
     * Downloads [url] to [target], calling [onProgress] with a 0..1 fraction as it goes.
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
        onProgress: (Double) -> Unit
    ) {
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

        verify(target, md5, sha1, sha256)
    }

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
