package com.panomc.platform.server.plugins.dto

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * One searchable plugin directory as the panel sees it.
 *
 * [reason] is filled only when [enabled] is false, so the panel can say *why* a source is missing
 * instead of silently offering two where an operator expected three.
 */
data class PluginSourceData(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val reason: String? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("reason", reason)
}

/** One project in a search result, normalised across the three sources. */
data class PluginSearchResultData(
    val source: String,
    val projectId: String,
    val slug: String?,
    val name: String,
    val author: String?,
    val summary: String?,
    val iconUrl: String?,
    val downloads: Long,
    val follows: Long,
    val categories: List<String>,
    val pageUrl: String?,
    /** Whether this project publishes something for this server's loader and game version. */
    val compatible: Boolean
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("source", source)
        .put("projectId", projectId)
        .put("slug", slug)
        .put("name", name)
        .put("author", author)
        .put("summary", summary)
        .put("iconUrl", iconUrl)
        .put("downloads", downloads)
        .put("follows", follows)
        .put("categories", JsonArray(categories))
        .put("pageUrl", pageUrl)
        .put("compatible", compatible)
}

/** A page of search results, with just enough to drive a "load more" button. */
data class PluginSearchPage(
    val results: List<PluginSearchResultData>,
    val page: Int,
    val hasMore: Boolean
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("results", JsonArray(results.map { it.toJsonObject() }))
        .put("page", page)
        .put("hasMore", hasMore)
}

/**
 * One downloadable file of a version.
 *
 * [external] marks a file the source does not host itself — Hangar lets an author point at a
 * GitHub release page instead of uploading a jar. Those are shown so the panel can link out, and
 * refused for installation, because what is behind the link is a web page rather than a jar.
 */
data class PluginVersionFileData(
    val url: String?,
    val filename: String?,
    val size: Long,
    val sha512: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val primary: Boolean = false,
    val external: Boolean = false
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("url", url)
        .put("filename", filename)
        .put("size", size)
        .put("sha512", sha512)
        .put("sha1", sha1)
        .put("sha256", sha256)
        .put("primary", primary)
        .put("external", external)
}

/** One published version of a project, normalised across the three sources. */
data class PluginVersionData(
    val id: String,
    val name: String,
    val versionNumber: String?,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val publishedAt: String?,
    /** `release`, `beta` or `alpha`, lowercased; sources spell their channels differently. */
    val channel: String?,
    val compatible: Boolean,
    val files: List<PluginVersionFileData>
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("id", id)
        .put("name", name)
        .put("versionNumber", versionNumber)
        .put("gameVersions", JsonArray(gameVersions))
        .put("loaders", JsonArray(loaders))
        .put("publishedAt", publishedAt)
        .put("channel", channel)
        .put("compatible", compatible)
        .put("files", JsonArray(files.map { it.toJsonObject() }))
}

/** One jar sitting in a managed server's plugin directory. */
data class ServerPluginFileData(
    val filename: String,
    val size: Long,
    val modified: Long,
    /** The name of the loaded plugin this file most likely is, when one matches. */
    val matchedPlugin: String? = null,
    /** False for a jar switched off by renaming it to `<name>.jar.disabled`. */
    val enabled: Boolean = true
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("filename", filename)
        .put("size", size)
        .put("modified", modified)
        .put("matchedPlugin", matchedPlugin)
        .put("enabled", enabled)
}

/**
 * One jar a source recognised by its hash.
 *
 * [key] is the hash that matched — a SHA-1 for Modrinth, a murmur2 fingerprint for CurseForge —
 * and is how the answer is paired back with the file it was asked about, because a batch lookup
 * comes back keyed by hash and says nothing about filenames.
 */
data class PluginHashMatch(
    val key: String,
    val projectId: String,
    val versionId: String,
    val versionNumber: String?,
    val publishedAt: String?
)

/**
 * What a project is called and where its page is.
 *
 * A hash lookup answers with a version and a project id and nothing a person would recognise, so
 * this is the second half of identifying a jar: without it the panel would show "modrinth
 * AANobbMI" where it should show "FastAsyncWorldEdit".
 */
data class PluginProjectInfo(
    val projectId: String,
    val name: String?,
    val pageUrl: String?
)

/**
 * One jar identified by hash, as the panel is told about it.
 *
 * [source] is the source's id rather than the enum, because this crosses the API boundary.
 */
data class IdentifiedPluginData(
    val filename: String,
    val source: String,
    val projectId: String,
    val projectName: String?,
    val pageUrl: String?,
    val versionId: String,
    val versionNumber: String?,
    val publishedAt: String?
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("filename", filename)
        .put("source", source)
        .put("projectName", projectName)
        .put("versionNumber", versionNumber)
}

/**
 * One tracked jar and whether there is something newer for it.
 *
 * The installed half comes from the `server_plugin_install` row and is always there; the `latest`
 * half comes from the source and may be missing, because a source that is down means "no answer
 * this time" rather than "no update". [updateAvailable] false therefore never means "up to date"
 * on its own — it means nothing newer is known right now.
 */
data class TrackedPluginData(
    val filename: String,
    val source: String,
    val projectId: String,
    val projectName: String?,
    val pageUrl: String?,
    val identified: Boolean,
    val versionId: String?,
    val versionNumber: String?,
    val updateAvailable: Boolean,
    val latestVersionId: String?,
    val latestVersionNumber: String?,
    val latestPublishedAt: String?
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("filename", filename)
        .put("source", source)
        .put("projectId", projectId)
        .put("projectName", projectName)
        .put("pageUrl", pageUrl)
        .put("identified", identified)
        .put("versionId", versionId)
        .put("versionNumber", versionNumber)
        .put("updateAvailable", updateAvailable)
        .put("latestVersionId", latestVersionId)
        .put("latestVersionNumber", latestVersionNumber)
        .put("latestPublishedAt", latestPublishedAt)
}
