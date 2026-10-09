package com.panomc.platform.ui

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.panomc.platform.AppConstants
import com.panomc.platform.UIManager
import com.panomc.platform.util.adapter.StrictNotNullTypeAdapterFactory
import com.panomc.platform.util.annotation.StrictValidation
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * `manifest.json` of a custom app (doc 05 §8.1). Not a theme manifest: no `panoVersion`, no
 * `screenshots`, never premium. [apiLevel] is optional: custom apps did not exist before the
 * cutover, so a missing level is not "old" (doc 04 §7).
 */
@StrictValidation
class CustomAppManifest(
    val id: String,
    val type: String,
    val title: String,
    val version: String,
    val author: String,
    val description: String? = null,
    val apiLevel: Int? = null,
    val urls: Map<String, Any>? = null,
    val settingsSchema: JsonObject? = null
)

/** An app that is on disk, as the panel lists it. */
data class InstalledCustomApp(
    val id: String,
    val title: String,
    val version: String,
    val author: String,
    val description: String?,
    val apiLevel: Int?,
    val installedAt: Long
)

/**
 * Installs and removes custom apps: a zip with `manifest.json` and `index.js` at its root, kept
 * under `custom-apps/<id>/` and run by [UIManager.startCustomApp]. Not in the store (decision 23),
 * so there is no hash bookkeeping, no licence and no fingerprint.
 *
 * The theme side of the separation is [UIManager.parseThemeManifest], which refuses `type:
 * "custom-app"`; this class refuses everything that is not one.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class CustomAppInstaller(
    private val uiManager: UIManager,
    private val vertx: Vertx,
    private val logger: Logger
) {
    private val folder: File get() = uiManager.customAppsFolder

    /** Every valid app folder, by id. A folder without a readable manifest is not an app. */
    fun list(): List<InstalledCustomApp> =
        folder.listFiles { file -> file.isDirectory && ID_PATTERN.matches(file.name) }
            .orEmpty()
            .mapNotNull { read(it) }
            .sortedBy { it.id }

    fun get(id: String): InstalledCustomApp? =
        if (ID_PATTERN.matches(id)) read(File(folder, id)) else null

    /** Whether [id] is an installed app. */
    fun exists(id: String) = get(id) != null

    suspend fun install(zip: File): InstalledCustomApp = vertx.executeBlocking<InstalledCustomApp> {
        installBlocking(zip)
    }.coAwait()

    suspend fun delete(id: String) = vertx.executeBlocking<Unit> {
        if (ID_PATTERN.matches(id)) {
            File(folder, id).deleteRecursively()
        }
    }.coAwait()

    /** The whole install on the calling (worker) thread. Throws the front-end errors of doc 05 §8. */
    internal fun installBlocking(zip: File): InstalledCustomApp {
        val staging = File(AppConstants.TEMP_FOLDER, "custom-app-" + System.nanoTime())

        try {
            staging.mkdirs()

            extract(zip, staging)

            val manifest = readManifest(staging)

            if (!File(staging, ENTRY_FILE).isFile) {
                throw CustomAppNoEntry()
            }

            manifest.apiLevel?.let { level ->
                val min = SupportedApiLevel.min
                val current = SupportedApiLevel.current

                if (level < min || level > current) {
                    throw CustomAppApiLevel(level, min, current)
                }
            }

            if (manifest.id in reservedIds()) {
                throw CustomAppIdTaken(manifest.id)
            }

            val target = File(folder, manifest.id)

            if (target.exists()) {
                if (uiManager.isCustomAppActive(manifest.id)) {
                    throw CustomAppActive(manifest.id)
                }

                // Same id again is an update: the new files replace the old ones whole.
                target.deleteRecursively()
            }

            folder.mkdirs()

            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
                staging.copyRecursively(target, overwrite = true)
            }

            logger.info("Installed custom app '{}' v{}.", manifest.id, manifest.version)

            return read(target)!!
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun reservedIds(): Set<String> =
        uiManager.installedThemeList.map { it.id.lowercase() }.toSet() + RESERVED_UI_IDS

    private fun read(appFolder: File): InstalledCustomApp? {
        val manifestFile = File(appFolder, uiManager.manifestFileName)

        if (!manifestFile.isFile || !File(appFolder, ENTRY_FILE).isFile) return null

        return try {
            val manifest = gson.fromJson(manifestFile.readText(), CustomAppManifest::class.java)

            InstalledCustomApp(
                id = appFolder.name,
                title = manifest.title,
                version = manifest.version,
                author = manifest.author,
                description = manifest.description,
                apiLevel = manifest.apiLevel,
                installedAt = manifestFile.lastModified()
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validates by hand rather than leaning on the strict adapter alone: the error has to name the
     * field, and a theme zip has to be told apart from a broken app.
     */
    private fun readManifest(root: File): CustomAppManifest {
        val file = File(root, uiManager.manifestFileName)

        if (!file.isFile) {
            throw CustomAppInvalidManifest("manifest.json", "The zip has no manifest.json at its root.")
        }

        val json = try {
            JsonParser.parseString(file.readText()).asJsonObject
        } catch (_: Exception) {
            throw CustomAppInvalidManifest("manifest.json", "manifest.json is not a JSON object.")
        }

        val type = json.stringOrNull("type")

        if (type != TYPE) {
            throw CustomAppInvalidManifest(
                "type",
                "type must be \"$TYPE\"" + if (json.has("panoVersion")) " (this looks like a theme)." else "."
            )
        }

        for (field in listOf("id", "title", "version", "author")) {
            if (json.stringOrNull(field).isNullOrBlank()) {
                throw CustomAppInvalidManifest(field, "$field is required and must be text.")
            }
        }

        val id = json.stringOrNull("id")!!

        if (!ID_PATTERN.matches(id)) {
            throw CustomAppInvalidManifest("id", "id may hold lowercase letters, digits and dashes only (up to 63).")
        }

        if (json.has("premium") && json.get("premium").let { !it.isJsonPrimitive || it.asString != "false" }) {
            throw CustomAppInvalidManifest("premium", "A custom app cannot be premium.")
        }

        if (json.has("description") && json.stringOrNull("description") == null) {
            throw CustomAppInvalidManifest("description", "description must be text.")
        }

        if (json.has("apiLevel") && !json.get("apiLevel").let { it.isJsonPrimitive && it.asString.toIntOrNull() != null }) {
            throw CustomAppInvalidManifest("apiLevel", "apiLevel must be a whole number.")
        }

        if (json.has("urls") && !json.get("urls").isJsonObject) {
            throw CustomAppInvalidManifest("urls", "urls must be an object.")
        }

        if (json.has("settingsSchema") && !json.get("settingsSchema").isJsonObject) {
            throw CustomAppInvalidManifest("settingsSchema", "settingsSchema must be an object.")
        }

        return try {
            gson.fromJson(json, CustomAppManifest::class.java)
        } catch (e: Exception) {
            throw CustomAppInvalidManifest("manifest.json", e.message ?: "manifest.json is invalid.")
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null

        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }

    /** Unzips [zip] into [target]; an entry that would land outside it, or a zip bomb, fails the install. */
    private fun extract(zip: File, target: File) {
        val root = target.canonicalFile
        var total = 0L
        var count = 0

        try {
            ZipFile(zip).use { archive ->
                for (entry in archive.entries()) {
                    if (++count > MAX_ENTRIES) {
                        throw CustomAppInvalidManifest("manifest.json", "The zip holds too many files.")
                    }

                    val out = File(root, entry.name).canonicalFile

                    if (out != root && !out.path.startsWith(root.path + File.separator)) {
                        throw CustomAppInvalidManifest("manifest.json", "The zip holds a path outside the app folder.")
                    }

                    if (entry.isDirectory) {
                        out.mkdirs()

                        continue
                    }

                    out.parentFile.mkdirs()

                    total += copyEntry(archive, entry, out, MAX_UNPACKED_BYTES - total)
                }
            }
        } catch (_: java.util.zip.ZipException) {
            throw CustomAppInvalidManifest("manifest.json", "The file is not a zip.")
        }
    }

    private fun copyEntry(archive: ZipFile, entry: ZipEntry, out: File, budget: Long): Long {
        var written = 0L

        archive.getInputStream(entry).use { input ->
            out.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)

                while (true) {
                    val read = input.read(buffer)

                    if (read < 0) break

                    written += read

                    if (written > budget) {
                        throw CustomAppInvalidManifest("manifest.json", "The unpacked app is too large.")
                    }

                    output.write(buffer, 0, read)
                }
            }
        }

        return written
    }

    companion object {
        const val TYPE = "custom-app"
        const val ENTRY_FILE = "index.js"

        private const val MAX_ENTRIES = 20_000
        private const val MAX_UNPACKED_BYTES = 1L shl 30

        /** Ids that name the built-in UIs; theme ids are added from the installed list. */
        private val RESERVED_UI_IDS = setOf("panel-ui", "setup-ui", AppConstants.DEFAULT_THEME_ID)

        /** Folder names, so also what `custom-app` in config.conf can hold. */
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

        private val gson: Gson by lazy {
            GsonBuilder().registerTypeAdapterFactory(StrictNotNullTypeAdapterFactory()).create()
        }
    }
}
