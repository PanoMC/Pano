package com.panomc.node.task

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * `modrinth.index.json`, read the way a server has to read it.
 *
 * An `.mrpack` is a zip holding this manifest, an `overrides/` tree and nothing else: the mods are
 * URLs the manifest points at, which is what lets a pack be redistributed without redistributing
 * anybody's jar. Reading it therefore means deciding three things — which files belong on a
 * server, where each one goes, and which loader has to exist for any of it to run.
 *
 * **Server-side selection.** Every entry carries `env.server`, one of `required`, `optional` or
 * `unsupported`. Only `unsupported` is dropped; an optional mod is installed because on a server
 * the person who chose the pack is the one who decides, and a missing optional mod usually means a
 * client that cannot join.
 *
 * **Paths are attacker-controlled.** `path` comes out of a file downloaded from the internet, so
 * it is carried as text and resolved by the caller through the same guard a zip entry goes
 * through. Nothing here touches the filesystem.
 */
object ModpackIndex {
    /** One file the pack wants on disk, and where it comes from. */
    data class Entry(
        /** Relative to the server directory, exactly as the manifest spells it. */
        val path: String,
        val downloads: List<String>,
        val sha512: String? = null,
        val sha1: String? = null,
        val size: Long = 0
    )

    /** What the manifest says, reduced to what an install needs. */
    data class Pack(
        val name: String?,
        val versionId: String?,
        /** The Minecraft version, from `dependencies.minecraft`. */
        val minecraftVersion: String?,
        /** `fabric-loader`, `quilt-loader`, `forge` or `neoforge`, when the pack names one. */
        val loader: String?,
        /** That loader's version, as the manifest spells it. */
        val loaderVersion: String?,
        val files: List<Entry>
    )

    /** Loader keys a `dependencies` block can carry, besides `minecraft`. */
    val LOADER_KEYS = listOf("fabric-loader", "quilt-loader", "forge", "neoforge")

    /** Most files one pack may declare, so a hostile manifest cannot become an infinite install. */
    const val MAX_FILES = 5000

    /**
     * Reads [text] as a `modrinth.index.json`, or returns null when it is not one.
     *
     * Null rather than an exception because the caller's answer to both is the same — fail the
     * import with "this is not a Modrinth modpack" — and because the text came off the internet,
     * so being unreadable is an expected outcome rather than a bug.
     */
    fun parse(text: String?): Pack? {
        val body = try {
            JsonObject(text ?: return null)
        } catch (_: Exception) {
            return null
        }

        // `game` is always "minecraft" and `formatVersion` is 1 today; a pack that says otherwise
        // is one this cannot honestly claim to understand.
        if (body.getString("game", "minecraft") != "minecraft") {
            return null
        }

        val dependencies = body.getJsonObject("dependencies") ?: JsonObject()

        val loader = LOADER_KEYS.firstOrNull { dependencies.getString(it) != null }

        return Pack(
            name = body.getString("name"),
            versionId = body.getString("versionId"),
            minecraftVersion = dependencies.getString("minecraft"),
            loader = loader,
            loaderVersion = loader?.let { dependencies.getString(it) },
            files = serverFiles(body.getJsonArray("files"))
        )
    }

    private fun serverFiles(files: JsonArray?): List<Entry> = (files ?: JsonArray())
        .mapNotNull { it as? JsonObject }
        .filter { isForServer(it) }
        .mapNotNull { entry(it) }
        .take(MAX_FILES)

    /**
     * Whether this entry belongs on a server.
     *
     * A missing `env` block means the pack did not say, and a pack that did not say is taken at
     * its word that the file belongs in it: the only thing that keeps a file off a server is an
     * explicit `unsupported`.
     */
    private fun isForServer(entry: JsonObject): Boolean {
        val env = entry.getJsonObject("env") ?: return true

        return env.getString("server", "required") != "unsupported"
    }

    private fun entry(file: JsonObject): Entry? {
        val path = file.getString("path")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val downloads = (file.getJsonArray("downloads") ?: JsonArray())
            .mapNotNull { it as? String }
            .filter { it.startsWith("http://") || it.startsWith("https://") }

        if (downloads.isEmpty()) {
            return null
        }

        val hashes = file.getJsonObject("hashes") ?: JsonObject()

        return Entry(
            path = path,
            downloads = downloads,
            sha512 = hashes.getString("sha512"),
            sha1 = hashes.getString("sha1"),
            size = file.getLong("fileSize", 0L) ?: 0L
        )
    }
}
