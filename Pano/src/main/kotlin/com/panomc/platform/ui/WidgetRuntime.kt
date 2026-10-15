package com.panomc.platform.ui

import com.panomc.platform.AppConstants.THEMES_FOLDER_PATH
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The runtime a Pano serves to widget pages: the Svelte build `runtime.json` names and its hash. */
data class WidgetRuntimeInfo(val svelte: String, val hash: String)

/** One file of the widget runtime. [immutable] is true for content-hashed names. */
class WidgetRuntimeFile(val bytes: ByteArray, val etag: String, val contentType: String, val immutable: Boolean)

/**
 * The widget runtime of this Pano (doc 06 section 3.3): Svelte, the host modules, `loader.js`, the token
 * and icon sheets, built by `theme-core/packages/widget-host`.
 *
 * Where it comes from, first hit wins: the folder `widget-runtime/` beside the themes folder (dev and
 * local installs, `install-local.js` unpacks the zip there), else the classpath `UIFiles/widget-runtime.zip`.
 * With neither (or without a readable `runtime.json`) there is no runtime and every lookup answers null.
 *
 * Only the files `runtime.json` lists (and `runtime.json` itself) are served. The folder is read on every
 * call, so a reinstalled runtime applies without a restart; the zip is read once.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class WidgetRuntime internal constructor(
    private val directory: () -> File?,
    private val zipStream: () -> InputStream?
) {
    @Autowired
    constructor() : this(
        { File(THEMES_FOLDER_PATH).absoluteFile.parentFile?.let { File(it, FOLDER) } },
        { ClassLoader.getSystemClassLoader().getResourceAsStream(ZIP_RESOURCE) }
    )

    private val logger = LoggerFactory.getLogger(WidgetRuntime::class.java)

    // The zip entries, read once; null when there is no zip on the classpath.
    private val zipEntries: Map<String, ByteArray>? by lazy {
        try {
            zipStream()?.use { stream ->
                val entries = linkedMapOf<String, ByteArray>()

                ZipInputStream(stream).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break

                        if (!entry.isDirectory) {
                            normalize(entry.name)?.let { entries[it] = zip.readBytes() }
                        }
                    }
                }

                entries
            }
        } catch (e: Exception) {
            logger.warn("The classpath {} could not be read: {}", ZIP_RESOURCE, e.message)

            null
        }
    }

    /** Svelte version and hash of the runtime, or null when this Pano has none. Blocking. */
    fun info(): WidgetRuntimeInfo? = manifest()?.second

    /** The runtime file at [path] (relative to the runtime root), or null. Blocking. */
    fun file(path: String): WidgetRuntimeFile? {
        val entry = normalize(path) ?: return null
        val (listed, info) = manifest() ?: return null

        if (entry != MANIFEST && entry !in listed) return null

        val bytes = read(entry) ?: return null

        return WidgetRuntimeFile(bytes, info.hash, contentType(entry), isHashed(entry))
    }

    /** `loader.js`, the module a page includes. Blocking. */
    fun loader(): WidgetRuntimeFile? = file(LOADER)

    // The listed files and the info of runtime.json, or null without a usable runtime.json.
    private fun manifest(): Pair<Set<String>, WidgetRuntimeInfo>? {
        val bytes = read(MANIFEST) ?: return null

        val json = try {
            JsonObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            logger.warn("The widget runtime has an unreadable {}: {}", MANIFEST, e.message)

            return null
        }

        val svelte = json.getValue("svelte") as? String
        val hash = json.getValue("hash") as? String

        if (svelte.isNullOrBlank() || hash.isNullOrBlank()) {
            logger.warn("The widget runtime's {} names no svelte version or hash", MANIFEST)

            return null
        }

        val files = (json.getValue("files") as? io.vertx.core.json.JsonArray)
            ?.list?.filterIsInstance<String>()?.mapNotNull { normalize(it) }?.toSet() ?: emptySet()

        return files to WidgetRuntimeInfo(svelte, hash)
    }

    private fun read(entry: String): ByteArray? {
        val dir = directory()

        if (dir != null && dir.isDirectory) {
            val file = File(dir, entry)

            // A symbolic link out of the folder must not become a way to read other files.
            if (!file.isFile || !file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())) return null

            return file.readBytes()
        }

        return zipEntries?.get(entry)
    }

    private fun normalize(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.contains('\\') || raw.contains('\u0000')) return null

        val segments = raw.split('/')

        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

        return segments.joinToString("/")
    }

    companion object {
        const val FOLDER = "widget-runtime"
        const val ZIP_RESOURCE = "UIFiles/widget-runtime.zip"
        const val MANIFEST = "runtime.json"
        const val LOADER = "loader.js"

        // chunks/chunk-<hash>.js: the name changes with the content.
        private val HASHED = Regex("^chunks/[^/]+-[A-Za-z0-9_]{6,}\\.m?js$")

        internal fun isHashed(entry: String) = HASHED.matches(entry)

        internal fun contentType(entry: String): String = when (entry.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

        internal fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
