package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.dto.PluginHashMatch
import com.panomc.platform.server.plugins.dto.PluginProjectInfo
import com.panomc.platform.server.plugins.dto.PluginSearchResultData
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Modrinth's v2 payloads, turned into Pano's shapes.
 *
 * Pure and separate from the HTTP calls so the mapping can be tested against real captured JSON
 * instead of against a live API that changes on its own schedule. Everything is read defensively:
 * a field Modrinth stops sending must make one result thinner, never make a panel request fail.
 */
object ModrinthResponses {
    fun searchResults(body: JsonObject?): List<PluginSearchResultData> {
        val hits = body?.getJsonArray("hits") ?: return emptyList()

        return hits.mapNotNull { it as? JsonObject }.mapNotNull { searchResult(it) }
    }

    /** Whether Modrinth says more hits exist past this page. */
    fun hasMore(body: JsonObject?, offset: Int, pageSize: Int): Boolean {
        val total = body?.getInteger("total_hits") ?: return false

        return offset + pageSize < total
    }

    private fun searchResult(hit: JsonObject): PluginSearchResultData? {
        val projectId = hit.getString("project_id") ?: hit.getString("slug") ?: return null
        val slug = hit.getString("slug")

        val categories = (hit.getJsonArray("display_categories") ?: hit.getJsonArray("categories") ?: JsonArray())
            .mapNotNull { it as? String }

        return PluginSearchResultData(
            source = PluginSourceId.MODRINTH.id,
            projectId = projectId,
            slug = slug,
            name = hit.getString("title") ?: slug ?: projectId,
            author = hit.getString("author"),
            summary = hit.getString("description"),
            iconUrl = hit.getString("icon_url"),
            downloads = hit.getLong("downloads", 0L) ?: 0L,
            follows = hit.getLong("follows", 0L) ?: 0L,
            categories = categories,
            pageUrl = slug?.let { "https://modrinth.com/project/$it" },
            // The search is already faceted by loader and game version, so anything that comes
            // back is something this server can run.
            compatible = true
        )
    }

    /**
     * Maps `GET /project/{id}/version`.
     *
     * [loaders] and [gameVersions] are what this server can use; they decide the `compatible`
     * flag rather than filtering, because a panel that silently drops versions looks broken to
     * someone who can see them on the website.
     */
    fun versions(body: JsonArray?, loaders: List<String>, gameVersions: List<String>): List<PluginVersionData> {
        val entries = body ?: return emptyList()

        return entries.mapNotNull { it as? JsonObject }.mapNotNull { version(it, loaders, gameVersions) }
    }

    /**
     * Maps `POST /v2/version_files`, which answers keyed by the hash that was asked about.
     *
     * Modrinth leaves out hashes it does not know rather than answering null for them, so the
     * keys of the answer are exactly the files it recognised and there is nothing to filter.
     */
    fun versionFiles(body: JsonObject?): List<PluginHashMatch> {
        val document = body ?: return emptyList()

        return document.fieldNames().mapNotNull { hash ->
            val entry = document.getJsonObject(hash) ?: return@mapNotNull null

            val versionId = entry.getString("id") ?: return@mapNotNull null
            val projectId = entry.getString("project_id") ?: return@mapNotNull null

            PluginHashMatch(
                key = hash,
                projectId = projectId,
                versionId = versionId,
                versionNumber = entry.getString("version_number") ?: entry.getString("name"),
                publishedAt = entry.getString("date_published")
            )
        }
    }

    /**
     * Maps `GET /v2/projects?ids=[…]`.
     *
     * The page url is built from `project_type` rather than hard-coded to `/plugin/`: Modrinth
     * serves a mod at `/mod/<slug>` and a plugin at `/plugin/<slug>`, and a link to the wrong one
     * redirects today and may not tomorrow.
     */
    fun projects(body: JsonArray?): List<PluginProjectInfo> {
        val entries = body ?: return emptyList()

        return entries.mapNotNull { it as? JsonObject }.mapNotNull { project ->
            val id = project.getString("id") ?: return@mapNotNull null
            val slug = project.getString("slug")
            val type = project.getString("project_type") ?: "project"

            PluginProjectInfo(
                projectId = id,
                name = project.getString("title") ?: slug,
                pageUrl = slug?.let { "https://modrinth.com/$type/$it" }
            )
        }
    }

    /** Maps `GET /v2/project/{id}`, which is [projects] for a single project. */
    fun project(body: JsonObject?): PluginProjectInfo? =
        projects(JsonArray().apply { body?.let { add(it) } }).firstOrNull()

    private fun version(
        entry: JsonObject,
        loaders: List<String>,
        gameVersions: List<String>
    ): PluginVersionData? {
        val id = entry.getString("id") ?: return null

        val versionLoaders = (entry.getJsonArray("loaders") ?: JsonArray()).mapNotNull { it as? String }
        val versionGameVersions = (entry.getJsonArray("game_versions") ?: JsonArray()).mapNotNull { it as? String }

        // Primary first, because the panel installs `fileIndex` 0 unless somebody picks another
        // one, and Modrinth does not promise the primary jar is the first in the array (a version
        // often ships sources or a javadoc jar alongside it).
        val files = (entry.getJsonArray("files") ?: JsonArray())
            .mapNotNull { it as? JsonObject }
            .sortedByDescending { it.getBoolean("primary", false) }
            .map { file ->
                val hashes = file.getJsonObject("hashes") ?: JsonObject()

                PluginVersionFileData(
                    url = file.getString("url"),
                    filename = file.getString("filename"),
                    size = file.getLong("size", 0L) ?: 0L,
                    sha512 = hashes.getString("sha512"),
                    sha1 = hashes.getString("sha1"),
                    primary = file.getBoolean("primary", false) ?: false
                )
            }

        return PluginVersionData(
            id = id,
            name = entry.getString("name") ?: entry.getString("version_number") ?: id,
            versionNumber = entry.getString("version_number"),
            gameVersions = versionGameVersions,
            loaders = versionLoaders,
            publishedAt = entry.getString("date_published"),
            channel = entry.getString("version_type")?.lowercase(),
            compatible = PluginCompatibility.matches(versionLoaders, loaders) &&
                PluginCompatibility.matches(versionGameVersions, gameVersions),
            files = files
        )
    }
}
