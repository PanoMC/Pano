package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.dto.PluginHashMatch
import com.panomc.platform.server.plugins.dto.PluginProjectInfo
import com.panomc.platform.server.plugins.dto.PluginSearchResultData
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * CurseForge's v1 payloads, turned into Pano's shapes.
 *
 * The one rule that matters here is `allowModDistribution`: a project whose author opted out of
 * third-party downloads must not have its files offered, and CurseForge signals that on the
 * project rather than on each file. Pano drops those projects from the results entirely rather
 * than showing them and failing at install time.
 */
object CurseForgeResponses {
    fun searchResults(body: JsonObject?): List<PluginSearchResultData> {
        val data = body?.getJsonArray("data") ?: return emptyList()

        return data.mapNotNull { it as? JsonObject }
            .filter { it.getBoolean("allowModDistribution", true) != false }
            .mapNotNull { searchResult(it) }
    }

    /** Whether CurseForge's pagination says more mods exist past this page. */
    fun hasMore(body: JsonObject?, offset: Int, pageSize: Int): Boolean {
        val total = body?.getJsonObject("pagination")?.getLong("totalCount") ?: return false

        return offset + pageSize < total
    }

    private fun searchResult(mod: JsonObject): PluginSearchResultData? {
        val id = mod.getInteger("id")?.toString() ?: return null

        val authors = (mod.getJsonArray("authors") ?: JsonArray()).mapNotNull { it as? JsonObject }
        val categories = (mod.getJsonArray("categories") ?: JsonArray())
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.getString("name") }

        return PluginSearchResultData(
            source = PluginSourceId.CURSEFORGE.id,
            projectId = id,
            slug = mod.getString("slug"),
            name = mod.getString("name") ?: id,
            author = authors.firstOrNull()?.getString("name"),
            summary = mod.getString("summary"),
            iconUrl = mod.getJsonObject("logo")?.getString("url"),
            downloads = mod.getLong("downloadCount", 0L) ?: 0L,
            // CurseForge has no follower count; thumbs up is the closest thing it publishes.
            follows = mod.getLong("thumbsUpCount", 0L) ?: 0L,
            categories = categories,
            pageUrl = mod.getJsonObject("links")?.getString("websiteUrl"),
            compatible = true
        )
    }

    /** Maps `GET /mods/{id}/files`. */
    fun versions(body: JsonObject?, loaders: List<String>, gameVersions: List<String>): List<PluginVersionData> {
        val data = body?.getJsonArray("data") ?: return emptyList()

        return data.mapNotNull { it as? JsonObject }.mapNotNull { version(it, loaders, gameVersions) }
    }

    /**
     * Maps `POST /v1/fingerprints/432`.
     *
     * Keyed back by `fileFingerprint` rather than by position, because CurseForge answers only
     * with what it recognised: the request may carry twenty fingerprints and the answer three, in
     * no particular order, and pairing them up by index would attach the wrong plugin to the
     * wrong file.
     */
    fun fingerprintMatches(body: JsonObject?): List<PluginHashMatch> {
        val matches = body?.getJsonObject("data")?.getJsonArray("exactMatches") ?: return emptyList()

        return matches.mapNotNull { it as? JsonObject }.mapNotNull { match ->
            val file = match.getJsonObject("file") ?: return@mapNotNull null

            val fingerprint = file.getLong("fileFingerprint") ?: return@mapNotNull null
            val projectId = (match.getInteger("id") ?: file.getInteger("modId"))?.toString()
                ?: return@mapNotNull null
            val fileId = file.getInteger("id")?.toString() ?: return@mapNotNull null

            PluginHashMatch(
                key = fingerprint.toString(),
                projectId = projectId,
                versionId = fileId,
                versionNumber = file.getString("displayName") ?: file.getString("fileName"),
                publishedAt = file.getString("fileDate")
            )
        }
    }

    /** Maps `POST /v1/mods`, which is how a fingerprint match gets a name a person recognises. */
    fun mods(body: JsonObject?): List<PluginProjectInfo> {
        val data = body?.getJsonArray("data") ?: return emptyList()

        return data.mapNotNull { it as? JsonObject }.mapNotNull { mod ->
            val id = mod.getInteger("id")?.toString() ?: return@mapNotNull null

            PluginProjectInfo(
                projectId = id,
                name = mod.getString("name") ?: mod.getString("slug"),
                pageUrl = mod.getJsonObject("links")?.getString("websiteUrl")
            )
        }
    }

    private fun version(entry: JsonObject, loaders: List<String>, gameVersions: List<String>): PluginVersionData? {
        val id = entry.getInteger("id")?.toString() ?: return null

        // CurseForge mixes Minecraft versions and loader names into one list, so both filters read
        // the same field and each ignores what it does not recognise.
        val declared = (entry.getJsonArray("gameVersions") ?: JsonArray()).mapNotNull { it as? String }

        val hashes = (entry.getJsonArray("hashes") ?: JsonArray()).mapNotNull { it as? JsonObject }
        val sha1 = hashes.firstOrNull { it.getInteger("algo") == HASH_ALGO_SHA1 }?.getString("value")

        val downloadUrl = entry.getString("downloadUrl")

        val file = PluginVersionFileData(
            url = downloadUrl,
            filename = entry.getString("fileName"),
            size = entry.getLong("fileLength", 0L) ?: 0L,
            sha1 = sha1,
            primary = true,
            external = downloadUrl == null
        )

        return PluginVersionData(
            id = id,
            name = entry.getString("displayName") ?: entry.getString("fileName") ?: id,
            versionNumber = entry.getString("displayName"),
            gameVersions = declared,
            loaders = declared.filter { it.lowercase() in KNOWN_LOADERS }.map { it.lowercase() },
            publishedAt = entry.getString("fileDate"),
            channel = channelOf(entry.getInteger("releaseType")),
            compatible = PluginCompatibility.matches(declared, loaders) &&
                PluginCompatibility.matches(declared, gameVersions),
            files = listOf(file)
        )
    }

    private fun channelOf(releaseType: Int?): String? = when (releaseType) {
        1 -> "release"
        2 -> "beta"
        3 -> "alpha"
        else -> null
    }

    /** CurseForge's hash algorithm ids: 1 is SHA-1, 2 is MD5. */
    private const val HASH_ALGO_SHA1 = 1

    private val KNOWN_LOADERS = setOf("forge", "fabric", "quilt", "neoforge", "bukkit", "spigot", "paper", "purpur")
}
