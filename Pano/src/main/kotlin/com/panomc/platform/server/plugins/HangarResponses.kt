package com.panomc.platform.server.plugins

import com.panomc.platform.server.plugins.dto.PluginProjectInfo
import com.panomc.platform.server.plugins.dto.PluginSearchResultData
import com.panomc.platform.server.plugins.dto.PluginVersionData
import com.panomc.platform.server.plugins.dto.PluginVersionFileData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Hangar's v1 payloads, turned into Pano's shapes.
 *
 * Hangar is organised by platform (`PAPER`, `VELOCITY`, `WATERFALL`) rather than by loader, and a
 * version carries one download entry per platform it publishes for. That entry is either a file
 * Hangar hosts — with a name, a size and a SHA-256 — or an `externalUrl` pointing at the author's
 * own release page. Both are surfaced; only the first can be installed, which is what the
 * `external` flag on a file says.
 *
 * A project is addressed by its slug everywhere in this API, so the slug is the project id Pano
 * hands back to the panel.
 */
object HangarResponses {
    fun searchResults(body: JsonObject?, platform: String?, gameVersions: List<String>): List<PluginSearchResultData> {
        val results = body?.getJsonArray("result") ?: return emptyList()

        return results.mapNotNull { it as? JsonObject }.mapNotNull { searchResult(it, platform, gameVersions) }
    }

    /** Whether Hangar's pagination says more projects exist past this page. */
    fun hasMore(body: JsonObject?, offset: Int, pageSize: Int): Boolean {
        val total = body?.getJsonObject("pagination")?.getLong("count") ?: return false

        return offset + pageSize < total
    }

    private fun searchResult(
        project: JsonObject,
        platform: String?,
        gameVersions: List<String>
    ): PluginSearchResultData? {
        val namespace = project.getJsonObject("namespace") ?: JsonObject()
        val slug = namespace.getString("slug") ?: project.getString("name") ?: return null
        val owner = namespace.getString("owner")
        val stats = project.getJsonObject("stats") ?: JsonObject()

        val supported = project.getJsonObject("supportedPlatforms") ?: JsonObject()

        val platformVersions = platform
            ?.let { supported.getJsonArray(it) }
            ?.mapNotNull { it as? String }
            .orEmpty()

        val compatible = if (platform == null) {
            false
        } else {
            supported.containsKey(platform) && PluginCompatibility.matches(platformVersions, gameVersions)
        }

        return PluginSearchResultData(
            source = PluginSourceId.HANGAR.id,
            projectId = slug,
            slug = slug,
            name = project.getString("name") ?: slug,
            author = owner,
            summary = project.getString("description"),
            iconUrl = project.getString("avatarUrl"),
            downloads = stats.getLong("downloads", 0L) ?: 0L,
            follows = stats.getLong("stars", 0L) ?: 0L,
            categories = listOfNotNull(project.getString("category")),
            pageUrl = owner?.let { "https://hangar.papermc.io/$it/$slug" },
            compatible = compatible
        )
    }

    /** Maps `GET /projects/{slug}` into the name and link a tracked install is displayed with. */
    fun project(body: JsonObject?): PluginProjectInfo? {
        val project = body ?: return null

        val namespace = project.getJsonObject("namespace") ?: JsonObject()
        val slug = namespace.getString("slug") ?: project.getString("name") ?: return null
        val owner = namespace.getString("owner")

        return PluginProjectInfo(
            projectId = slug,
            name = project.getString("name") ?: slug,
            pageUrl = owner?.let { "https://hangar.papermc.io/$it/$slug" }
        )
    }

    /** Maps `GET /projects/{slug}/versions` for one platform. */
    fun versions(body: JsonObject?, platform: String?, gameVersions: List<String>): List<PluginVersionData> {
        val results = body?.getJsonArray("result") ?: return emptyList()

        return results.mapNotNull { it as? JsonObject }.mapNotNull { version(it, platform, gameVersions) }
    }

    private fun version(entry: JsonObject, platform: String?, gameVersions: List<String>): PluginVersionData? {
        val name = entry.getString("name") ?: return null

        val downloads = entry.getJsonObject("downloads") ?: JsonObject()
        val platformDependencies = entry.getJsonObject("platformDependencies") ?: JsonObject()

        val loaders = downloads.fieldNames().map { it.lowercase() }.sorted()

        val versionGameVersions = platform
            ?.let { platformDependencies.getJsonArray(it) }
            ?.mapNotNull { it as? String }
            ?: (platformDependencies.fieldNames().flatMap { key ->
                (platformDependencies.getJsonArray(key) ?: JsonArray()).mapNotNull { it as? String }
            })

        val download = platform?.let { downloads.getJsonObject(it) }

        val files = listOfNotNull(download?.let { file(it) })

        val compatible = platform != null &&
            downloads.containsKey(platform) &&
            PluginCompatibility.matches(versionGameVersions, gameVersions)

        return PluginVersionData(
            // Hangar's download URL is built from the version *name*, not from its numeric id, so
            // the name is the only usable handle on a version.
            id = name,
            name = name,
            versionNumber = name,
            gameVersions = versionGameVersions.sorted(),
            loaders = loaders,
            publishedAt = entry.getString("createdAt"),
            channel = entry.getJsonObject("channel")?.getString("name")?.lowercase(),
            compatible = compatible,
            files = files
        )
    }

    private fun file(download: JsonObject): PluginVersionFileData {
        val info = download.getJsonObject("fileInfo")
        val hosted = download.getString("downloadUrl")
        val external = download.getString("externalUrl")

        return PluginVersionFileData(
            url = hosted ?: external,
            filename = info?.getString("name"),
            size = info?.getLong("sizeBytes", 0L) ?: 0L,
            sha256 = info?.getString("sha256Hash"),
            primary = true,
            // No hosted URL means the author published the jar somewhere else, and what is behind
            // that link is a web page rather than a file the node can verify.
            external = hosted == null && external != null
        )
    }
}
