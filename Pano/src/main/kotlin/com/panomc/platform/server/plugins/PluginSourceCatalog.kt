package com.panomc.platform.server.plugins

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.plugins.dto.PluginHashMatch
import com.panomc.platform.server.plugins.dto.PluginProjectInfo
import com.panomc.platform.server.plugins.dto.PluginSearchPage
import com.panomc.platform.server.plugins.dto.PluginSourceData
import com.panomc.platform.server.plugins.dto.PluginVersionData
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a managed server's plugins and mods can be searched for, and what each source has.
 *
 * Three third-party directories, each with its own idea of what a loader is and its own rate
 * limits, behind one shape. Like [com.panomc.platform.server.software.ServerSoftwareCatalog],
 * **nothing here throws**: a source that is down, rate limited or reshaped comes back empty and is
 * reported as a source with no results, because an admin browsing for a plugin should never see a
 * 500 because Modrinth was having a bad minute.
 *
 * Results are cached for ten minutes. Search is the one screen in the panel where a person types,
 * pauses and types again, and every keystroke reaching three upstreams is the fastest way to be
 * banned by all of them.
 *
 * CurseForge is only offered when an operator has pasted their own API key into config: CurseForge
 * requires one per application and forbids sharing, so Pano cannot ship a key and does not pretend
 * the source exists without one.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PluginSourceCatalog(
    private val webClient: WebClient,
    private val configManager: ConfigManager,
    private val logger: Logger
) {
    private data class CacheEntry(val value: Any, val storedAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** The CurseForge key, or null when the operator never set one. */
    private val curseForgeApiKey: String?
        get() = configManager.config.effectivePluginSources.curseForgeApiKey?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Which sources this server can be searched in, and why one is missing.
     *
     * A source that cannot serve this software at all (Hangar has nothing for Fabric) is reported
     * as disabled with a reason rather than hidden, so the panel can explain the gap.
     */
    fun sources(type: ServerType): List<PluginSourceData> {
        val supportsPlugins = PluginLoaderMapping.supportsPlugins(type)

        return listOf(
            PluginSourceData(
                id = PluginSourceId.MODRINTH.id,
                name = PluginSourceId.MODRINTH.displayName,
                enabled = supportsPlugins,
                reason = if (supportsPlugins) null else REASON_NO_PLUGIN_PLATFORM
            ),
            PluginSourceData(
                id = PluginSourceId.HANGAR.id,
                name = PluginSourceId.HANGAR.displayName,
                enabled = supportsPlugins && PluginLoaderMapping.hangarPlatform(type) != null,
                reason = when {
                    !supportsPlugins -> REASON_NO_PLUGIN_PLATFORM
                    PluginLoaderMapping.hangarPlatform(type) == null -> REASON_HANGAR_PLATFORM
                    else -> null
                }
            ),
            PluginSourceData(
                id = PluginSourceId.CURSEFORGE.id,
                name = PluginSourceId.CURSEFORGE.displayName,
                enabled = supportsPlugins && curseForgeApiKey != null,
                reason = when {
                    !supportsPlugins -> REASON_NO_PLUGIN_PLATFORM
                    curseForgeApiKey == null -> REASON_CURSEFORGE_KEY
                    else -> null
                }
            )
        )
    }

    /** Whether [source] can be searched for a server of this type right now. */
    fun isEnabled(source: PluginSourceId, type: ServerType): Boolean =
        sources(type).firstOrNull { it.id == source.id }?.enabled == true

    /**
     * One page of search results from one source.
     *
     * Always answers: an unreachable upstream is an empty page, which renders as "nothing found"
     * rather than as an error the person has no way to act on.
     */
    suspend fun search(
        source: PluginSourceId,
        type: ServerType,
        softwareVersion: String?,
        query: String,
        page: Int,
        limit: Int,
        projectType: PluginProjectType? = null
    ): PluginSearchPage {
        if (!isEnabled(source, type)) {
            return PluginSearchPage(emptyList(), page, false)
        }

        val pageSize = limit.coerceIn(1, MAX_LIMIT)
        val pageIndex = page.coerceIn(0, MAX_PAGE)
        val offset = pageIndex * pageSize
        val trimmed = query.trim().take(MAX_QUERY_LENGTH)

        val key =
            "search:${source.id}:${type.name}:${projectType?.id}:$softwareVersion:$trimmed:$pageIndex:$pageSize"

        cached<PluginSearchPage>(key)?.let { return it }

        val result = try {
            when (source) {
                PluginSourceId.MODRINTH ->
                    searchModrinth(type, softwareVersion, trimmed, offset, pageSize, projectType)

                PluginSourceId.HANGAR -> searchHangar(type, softwareVersion, trimmed, offset, pageSize)
                PluginSourceId.CURSEFORGE ->
                    searchCurseForge(type, softwareVersion, trimmed, offset, pageSize, projectType)
            }
        } catch (e: Exception) {
            logger.warn("Plugin search on ${source.id} failed: ${e.message}")

            PluginSearchPage(emptyList(), pageIndex, false)
        }

        if (result.results.isNotEmpty()) {
            store(key, result)
        }

        return result
    }

    /** Every published version of one project, newest first as the source returns them. */
    suspend fun versions(
        source: PluginSourceId,
        type: ServerType,
        softwareVersion: String?,
        projectId: String
    ): List<PluginVersionData> {
        if (!isEnabled(source, type)) {
            return emptyList()
        }

        val key = "versions:${source.id}:${type.name}:$softwareVersion:$projectId"

        cached<List<PluginVersionData>>(key)?.let { return it }

        val result = try {
            when (source) {
                PluginSourceId.MODRINTH -> modrinthVersions(type, softwareVersion, projectId)
                PluginSourceId.HANGAR -> hangarVersions(type, softwareVersion, projectId)
                PluginSourceId.CURSEFORGE -> curseForgeVersions(type, softwareVersion, projectId)
            }
        } catch (e: Exception) {
            logger.warn("Listing ${source.id} versions of $projectId failed: ${e.message}")

            emptyList()
        }

        if (result.isNotEmpty()) {
            store(key, result)
        }

        return result
    }

    /** One version by id, or null when the source no longer has it. */
    suspend fun version(
        source: PluginSourceId,
        type: ServerType,
        softwareVersion: String?,
        projectId: String,
        versionId: String
    ): PluginVersionData? = versions(source, type, softwareVersion, projectId).firstOrNull { it.id == versionId }

    /**
     * One version of a Modrinth **modpack**, for the server-creation wizard's import step.
     *
     * Modpacks live in the same project space as plugins and mods — same API, same version shape,
     * only `project_type: modpack` — so the same reader handles them and the `.mrpack` comes back
     * as an ordinary file on the version. What it cannot share is the loader/game-version
     * filtering the rest of this class does: a pack *is* the loader and the game version, so there
     * is nothing to be compatible with yet, and the compatibility flag is left meaningless here
     * rather than computed against a server that does not exist.
     *
     * Pano resolves the URL rather than the node, for the same reason every other download is
     * resolved here: which hosts a node fetches from stays something Pano decides.
     */
    suspend fun modpackVersion(projectId: String, versionId: String): PluginVersionData? {
        val key = "modpack:$projectId:$versionId"

        cached<PluginVersionData>(key)?.let { return it }

        val versions = try {
            ModrinthResponses.versions(
                getJsonArray("$MODRINTH_API/project/${encode(projectId)}/version"),
                emptyList(),
                emptyList()
            )
        } catch (e: Exception) {
            logger.warn("Listing Modrinth modpack versions of ${'$'}projectId failed: ${'$'}{e.message}")

            emptyList()
        }

        val version = versions.firstOrNull { it.id == versionId } ?: return null

        store(key, version)

        return version
    }

    /** The `.mrpack` of a modpack version, or null when that version publishes none. */
    fun modpackFile(version: PluginVersionData) = version.files
        .filterNot { it.external }
        .firstOrNull { it.filename?.endsWith(MODPACK_EXTENSION, ignoreCase = true) == true }
        ?: version.files.filterNot { it.external }.firstOrNull { it.primary }

    // ---------------------------------------------------------------------------------------
    // Identification by file hash
    // ---------------------------------------------------------------------------------------

    /** Whether CurseForge can be asked about fingerprints at all, which needs the operator's key. */
    fun isCurseForgeConfigured(): Boolean = curseForgeApiKey != null

    /**
     * Which of [sha1Hashes] Modrinth recognises.
     *
     * Not cached: a hash is asked about once per jar per identification run, and those runs are
     * themselves rate limited by the caller, so a cache would only remember answers nobody asks
     * for twice.
     */
    suspend fun identifyByModrinthSha1(sha1Hashes: List<String>): List<PluginHashMatch> {
        val hashes = sha1Hashes.filter { it.isNotBlank() }.distinct().take(MAX_HASH_BATCH)

        if (hashes.isEmpty()) {
            return emptyList()
        }

        val body = JsonObject()
            .put("hashes", JsonArray(hashes))
            .put("algorithm", "sha1")

        return ModrinthResponses.versionFiles(postJsonObject("$MODRINTH_API/version_files", body))
    }

    /**
     * What one project is called and where its page is.
     *
     * Looked up when a plugin is installed, so the tracked row carries a name a person recognises
     * rather than the opaque id the panel sent. Cached like everything else here: installing three
     * versions of the same plugin in a row is one lookup.
     */
    suspend fun projectInfo(source: PluginSourceId, projectId: String): PluginProjectInfo? {
        if (projectId.isBlank()) {
            return null
        }

        val key = "project:${source.id}:$projectId"

        cached<PluginProjectInfo>(key)?.let { return it }

        val info = try {
            when (source) {
                PluginSourceId.MODRINTH ->
                    ModrinthResponses.project(getJsonObject("$MODRINTH_API/project/${encode(projectId)}"))

                PluginSourceId.HANGAR ->
                    HangarResponses.project(getJsonObject("$HANGAR_API/projects/${encode(projectId)}"))

                PluginSourceId.CURSEFORGE -> curseForgeApiKey?.let { apiKey ->
                    CurseForgeResponses.mods(
                        JsonObject().put(
                            "data",
                            JsonArray().apply {
                                getJsonObject("$CURSEFORGE_API/mods/${encode(projectId)}", apiKey)
                                    ?.getJsonObject("data")
                                    ?.let { add(it) }
                            }
                        )
                    ).firstOrNull()
                }
            }
        } catch (e: Exception) {
            logger.warn("Looking up the ${source.id} project $projectId failed: ${e.message}")

            null
        }

        if (info != null) {
            store(key, info)
        }

        return info
    }

    /** Names and page links for projects a Modrinth hash lookup pointed at. */
    suspend fun modrinthProjects(projectIds: List<String>): List<PluginProjectInfo> {
        val ids = projectIds.filter { it.isNotBlank() }.distinct().take(MAX_HASH_BATCH)

        if (ids.isEmpty()) {
            return emptyList()
        }

        val url = "$MODRINTH_API/projects?ids=${encode(JsonArray(ids).encode())}"

        return ModrinthResponses.projects(getJsonArray(url))
    }

    /**
     * Which of [fingerprints] CurseForge recognises.
     *
     * The game-scoped endpoint is used rather than the global one, so a jar that happens to
     * fingerprint-collide with something from another game cannot come back as a Minecraft plugin.
     */
    suspend fun identifyByCurseForgeFingerprint(fingerprints: List<String>): List<PluginHashMatch> {
        val key = curseForgeApiKey ?: return emptyList()

        val values = fingerprints.mapNotNull { it.toLongOrNull() }.distinct().take(MAX_HASH_BATCH)

        if (values.isEmpty()) {
            return emptyList()
        }

        val body = JsonObject().put("fingerprints", JsonArray(values))

        val url = "$CURSEFORGE_API/fingerprints/${PluginLoaderMapping.CURSEFORGE_GAME_ID}"

        return CurseForgeResponses.fingerprintMatches(postJsonObject(url, body, key))
    }

    /** Names and page links for the mods a CurseForge fingerprint lookup pointed at. */
    suspend fun curseForgeMods(projectIds: List<String>): List<PluginProjectInfo> {
        val key = curseForgeApiKey ?: return emptyList()

        val ids = projectIds.mapNotNull { it.toIntOrNull() }.distinct().take(MAX_HASH_BATCH)

        if (ids.isEmpty()) {
            return emptyList()
        }

        val body = JsonObject().put("modIds", JsonArray(ids))

        return CurseForgeResponses.mods(postJsonObject("$CURSEFORGE_API/mods", body, key))
    }

    // ---------------------------------------------------------------------------------------
    // Catalogue (no server)
    // ---------------------------------------------------------------------------------------

    /**
     * Which sources can answer a catalogue search for [projectType] at all.
     *
     * Hangar is a plugin site and files no mods or modpacks; CurseForge still needs the operator's
     * own key. A source that cannot answer returns an empty page rather than an error, exactly as
     * the server-scoped search does for a source that is down.
     */
    fun isCatalogueEnabled(source: PluginSourceId, projectType: PluginProjectType): Boolean = when (source) {
        PluginSourceId.MODRINTH -> true
        PluginSourceId.HANGAR -> projectType.hangarSupported
        PluginSourceId.CURSEFORGE -> curseForgeApiKey != null && projectType.curseForgeClassId != null
    }

    /**
     * One page of a source's catalogue with no server behind it.
     *
     * The create-server wizard browses modpacks *before* the server exists, so there is no
     * software to derive a loader or a game version from and none is sent: the only filter is the
     * kind of project. For the same reason every result comes back `compatible`, which here means
     * "nothing constrains this yet" rather than a claim about a server that does not exist.
     */
    suspend fun searchCatalogue(
        source: PluginSourceId,
        projectType: PluginProjectType,
        query: String,
        page: Int,
        limit: Int
    ): PluginSearchPage {
        val pageSize = limit.coerceIn(1, MAX_LIMIT)
        val pageIndex = page.coerceIn(0, MAX_PAGE)
        val offset = pageIndex * pageSize
        val trimmed = query.trim().take(MAX_QUERY_LENGTH)

        if (!isCatalogueEnabled(source, projectType)) {
            return PluginSearchPage(emptyList(), pageIndex, false)
        }

        val key = "catalogue:${source.id}:${projectType.id}:$trimmed:$pageIndex:$pageSize"

        cached<PluginSearchPage>(key)?.let { return it }

        val answer = try {
            when (source) {
                PluginSourceId.MODRINTH -> catalogueModrinth(projectType, trimmed, offset, pageSize)
                PluginSourceId.HANGAR -> catalogueHangar(trimmed, offset, pageSize)
                PluginSourceId.CURSEFORGE -> catalogueCurseForge(projectType, trimmed, offset, pageSize)
            }
        } catch (e: Exception) {
            logger.warn("Catalogue search on ${source.id} failed: ${e.message}")

            PluginSearchPage(emptyList(), pageIndex, false)
        }

        val result = answer.copy(results = answer.results.map { it.copy(compatible = true) })

        if (result.results.isNotEmpty()) {
            store(key, result)
        }

        return result
    }

    /**
     * Every published version of one catalogue project, unfiltered.
     *
     * Hangar is addressed per platform and a version's files hang off that platform, so a
     * server-less listing of a Hangar project has versions but no downloadable files. That is
     * fine for the one thing this serves — choosing a modpack in the wizard, which is Modrinth —
     * and honest about the rest.
     */
    suspend fun catalogueVersions(
        source: PluginSourceId,
        projectType: PluginProjectType,
        projectId: String
    ): List<PluginVersionData> {
        if (!isCatalogueEnabled(source, projectType)) {
            return emptyList()
        }

        val key = "catalogueVersions:${source.id}:${projectType.id}:$projectId"

        cached<List<PluginVersionData>>(key)?.let { return it }

        val versions = try {
            when (source) {
                PluginSourceId.MODRINTH -> ModrinthResponses.versions(
                    getJsonArray("$MODRINTH_API/project/${encode(projectId)}/version"),
                    emptyList(),
                    emptyList()
                )

                PluginSourceId.HANGAR -> HangarResponses.versions(
                    getJsonObject("$HANGAR_API/projects/${encode(projectId)}/versions?limit=$HANGAR_VERSION_LIMIT&offset=0"),
                    null,
                    emptyList()
                )

                PluginSourceId.CURSEFORGE -> curseForgeCatalogueVersions(projectId)
            }
        } catch (e: Exception) {
            logger.warn("Listing ${source.id} catalogue versions of $projectId failed: ${e.message}")

            emptyList()
        }

        val result = versions.map { it.copy(compatible = true) }

        if (result.isNotEmpty()) {
            store(key, result)
        }

        return result
    }

    private suspend fun catalogueModrinth(
        projectType: PluginProjectType,
        query: String,
        offset: Int,
        pageSize: Int
    ): PluginSearchPage {
        val facets = JsonArray().add(JsonArray().add("project_type:${projectType.modrinthProjectType}"))

        val url = "$MODRINTH_API/search?query=${encode(query)}&offset=$offset&limit=$pageSize" +
            "&index=relevance&facets=${encode(facets.encode())}"

        val body = getJsonObject(url)

        return PluginSearchPage(
            results = ModrinthResponses.searchResults(body),
            page = offset / pageSize,
            hasMore = ModrinthResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun catalogueHangar(query: String, offset: Int, pageSize: Int): PluginSearchPage {
        val url = buildString {
            append("$HANGAR_API/projects?limit=$pageSize&offset=$offset")

            if (query.isNotEmpty()) {
                append("&q=${encode(query)}")
            }
        }

        val body = getJsonObject(url)

        return PluginSearchPage(
            results = HangarResponses.searchResults(body, null, emptyList()),
            page = offset / pageSize,
            hasMore = HangarResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun catalogueCurseForge(
        projectType: PluginProjectType,
        query: String,
        offset: Int,
        pageSize: Int
    ): PluginSearchPage {
        val key = curseForgeApiKey ?: return PluginSearchPage(emptyList(), offset / pageSize, false)
        val classId = projectType.curseForgeClassId ?: return PluginSearchPage(emptyList(), offset / pageSize, false)

        val url = buildString {
            append("$CURSEFORGE_API/mods/search")
            append("?gameId=${PluginLoaderMapping.CURSEFORGE_GAME_ID}")
            append("&classId=$classId")
            append("&index=$offset&pageSize=$pageSize&sortField=2&sortOrder=desc")

            if (query.isNotEmpty()) {
                append("&searchFilter=${encode(query)}")
            }
        }

        val body = getJsonObject(url, key)

        return PluginSearchPage(
            results = CurseForgeResponses.searchResults(body),
            page = offset / pageSize,
            hasMore = CurseForgeResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun curseForgeCatalogueVersions(projectId: String): List<PluginVersionData> {
        val key = curseForgeApiKey ?: return emptyList()

        val mod = getJsonObject("$CURSEFORGE_API/mods/${encode(projectId)}", key)?.getJsonObject("data")

        if (mod != null && mod.getBoolean("allowModDistribution", true) == false) {
            return emptyList()
        }

        val body = getJsonObject(
            "$CURSEFORGE_API/mods/${encode(projectId)}/files?index=0&pageSize=$CURSEFORGE_FILE_LIMIT",
            key
        )

        return CurseForgeResponses.versions(body, emptyList(), emptyList())
    }

    // ---------------------------------------------------------------------------------------
    // Modrinth
    // ---------------------------------------------------------------------------------------

    private suspend fun searchModrinth(
        type: ServerType,
        softwareVersion: String?,
        query: String,
        offset: Int,
        pageSize: Int,
        projectType: PluginProjectType? = null
    ): PluginSearchPage {
        val facets = JsonArray()

        val modrinthType = projectType?.modrinthProjectType ?: PluginLoaderMapping.modrinthProjectType(type)

        facets.add(JsonArray().add("project_type:$modrinthType"))

        val loaders = PluginLoaderMapping.modrinthLoaders(type)

        if (loaders.isNotEmpty()) {
            facets.add(JsonArray(loaders.map { "categories:$it" }))
        }

        val gameVersions = PluginLoaderMapping.gameVersions(type, softwareVersion)

        if (gameVersions.isNotEmpty()) {
            facets.add(JsonArray(gameVersions.map { "versions:$it" }))
        }

        val url = "$MODRINTH_API/search?query=${encode(query)}&offset=$offset&limit=$pageSize" +
            "&index=relevance&facets=${encode(facets.encode())}"

        val body = getJsonObject(url)

        return PluginSearchPage(
            results = ModrinthResponses.searchResults(body),
            page = offset / pageSize,
            hasMore = ModrinthResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun modrinthVersions(
        type: ServerType,
        softwareVersion: String?,
        projectId: String
    ): List<PluginVersionData> {
        val loaders = PluginLoaderMapping.modrinthLoaders(type)
        val gameVersions = PluginLoaderMapping.gameVersions(type, softwareVersion)

        // Unfiltered on purpose: the panel shows every version with a compatibility flag, so
        // somebody can deliberately install an older build for a server the author has not
        // updated for yet.
        val body = getJsonArray("$MODRINTH_API/project/${encode(projectId)}/version")

        return ModrinthResponses.versions(body, loaders, gameVersions)
    }

    // ---------------------------------------------------------------------------------------
    // Hangar
    // ---------------------------------------------------------------------------------------

    private suspend fun searchHangar(
        type: ServerType,
        softwareVersion: String?,
        query: String,
        offset: Int,
        pageSize: Int
    ): PluginSearchPage {
        val platform = PluginLoaderMapping.hangarPlatform(type)
            ?: return PluginSearchPage(emptyList(), offset / pageSize, false)

        val url = buildString {
            append("$HANGAR_API/projects?limit=$pageSize&offset=$offset&platform=$platform")

            if (query.isNotEmpty()) {
                append("&q=${encode(query)}")
            }
        }

        val body = getJsonObject(url)

        return PluginSearchPage(
            results = HangarResponses.searchResults(
                body,
                platform,
                PluginLoaderMapping.gameVersions(type, softwareVersion)
            ),
            page = offset / pageSize,
            hasMore = HangarResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun hangarVersions(
        type: ServerType,
        softwareVersion: String?,
        projectId: String
    ): List<PluginVersionData> {
        val platform = PluginLoaderMapping.hangarPlatform(type) ?: return emptyList()

        val body = getJsonObject(
            "$HANGAR_API/projects/${encode(projectId)}/versions?limit=$HANGAR_VERSION_LIMIT&offset=0"
        )

        return HangarResponses.versions(body, platform, PluginLoaderMapping.gameVersions(type, softwareVersion))
    }

    // ---------------------------------------------------------------------------------------
    // CurseForge
    // ---------------------------------------------------------------------------------------

    private suspend fun searchCurseForge(
        type: ServerType,
        softwareVersion: String?,
        query: String,
        offset: Int,
        pageSize: Int,
        projectType: PluginProjectType? = null
    ): PluginSearchPage {
        val key = curseForgeApiKey ?: return PluginSearchPage(emptyList(), offset / pageSize, false)

        val classId = projectType?.curseForgeClassId ?: PluginLoaderMapping.curseForgeClassId(type)

        val url = buildString {
            append("$CURSEFORGE_API/mods/search")
            append("?gameId=${PluginLoaderMapping.CURSEFORGE_GAME_ID}")
            append("&classId=$classId")
            append("&index=$offset&pageSize=$pageSize&sortField=2&sortOrder=desc")

            if (query.isNotEmpty()) {
                append("&searchFilter=${encode(query)}")
            }

            PluginLoaderMapping.curseForgeLoaderType(type)?.let { append("&modLoaderType=$it") }

            PluginLoaderMapping.gameVersions(type, softwareVersion).firstOrNull()?.let {
                append("&gameVersion=${encode(it)}")
            }
        }

        val body = getJsonObject(url, key)

        return PluginSearchPage(
            results = CurseForgeResponses.searchResults(body),
            page = offset / pageSize,
            hasMore = CurseForgeResponses.hasMore(body, offset, pageSize)
        )
    }

    private suspend fun curseForgeVersions(
        type: ServerType,
        softwareVersion: String?,
        projectId: String
    ): List<PluginVersionData> {
        val key = curseForgeApiKey ?: return emptyList()

        // The project is fetched first only to honour allowModDistribution: a "no" there means the
        // author forbade third-party downloads, and Pano must not hand out a file URL anyway.
        val mod = getJsonObject("$CURSEFORGE_API/mods/${encode(projectId)}", key)?.getJsonObject("data")

        if (mod != null && mod.getBoolean("allowModDistribution", true) == false) {
            return emptyList()
        }

        val body = getJsonObject(
            "$CURSEFORGE_API/mods/${encode(projectId)}/files?index=0&pageSize=$CURSEFORGE_FILE_LIMIT",
            key
        )

        return CurseForgeResponses.versions(
            body,
            PluginLoaderMapping.curseForgeLoaderNames(type),
            PluginLoaderMapping.gameVersions(type, softwareVersion)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String): T? {
        val entry = cache[key] ?: return null

        if (System.currentTimeMillis() - entry.storedAt > CACHE_TTL_MS) {
            cache.remove(key)

            return null
        }

        return entry.value as? T
    }

    private fun store(key: String, value: Any) {
        // A search cache keyed by free text grows with every distinct query somebody types; the
        // cheapest bound that never leaks is to drop the whole thing once it gets large.
        if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.clear()
        }

        cache[key] = CacheEntry(value, System.currentTimeMillis())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun <T> prepare(request: HttpRequest<T>, apiKey: String?): HttpRequest<T> {
        request.timeout(REQUEST_TIMEOUT_MS)
        request.putHeader("User-Agent", USER_AGENT)
        request.putHeader("Accept", "application/json")

        if (apiKey != null) {
            request.putHeader("x-api-key", apiKey)
        }

        return request
    }

    private suspend fun getJsonObject(url: String, apiKey: String? = null): JsonObject? = try {
        val response = prepare(webClient.getAbs(url), apiKey).send().coAwait()

        if (response.statusCode() != 200) {
            logger.warn("Plugin source $url answered ${response.statusCode()}")

            null
        } else {
            response.bodyAsJsonObject()
        }
    } catch (e: Exception) {
        logger.warn("Plugin source $url is unreachable: ${e.message}")

        null
    }

    private suspend fun postJsonObject(url: String, body: JsonObject, apiKey: String? = null): JsonObject? = try {
        val response = prepare(webClient.postAbs(url), apiKey).sendJsonObject(body).coAwait()

        if (response.statusCode() != 200) {
            logger.warn("Plugin source $url answered ${response.statusCode()}")

            null
        } else {
            response.bodyAsJsonObject()
        }
    } catch (e: Exception) {
        logger.warn("Plugin source $url is unreachable: ${e.message}")

        null
    }

    private suspend fun getJsonArray(url: String, apiKey: String? = null): JsonArray? = try {
        val response = prepare(webClient.getAbs(url), apiKey).send().coAwait()

        if (response.statusCode() != 200) {
            logger.warn("Plugin source $url answered ${response.statusCode()}")

            null
        } else {
            response.bodyAsJsonArray()
        }
    } catch (e: Exception) {
        logger.warn("Plugin source $url is unreachable: ${e.message}")

        null
    }

    companion object {
        const val MODRINTH_API = "https://api.modrinth.com/v2"
        const val HANGAR_API = "https://hangar.papermc.io/api/v1"
        const val CURSEFORGE_API = "https://api.curseforge.com/v1"

        /** Every upstream here is optional to the request at hand, so none of them may hold it up. */
        const val REQUEST_TIMEOUT_MS = 10_000L

        const val CACHE_TTL_MS = 10 * 60 * 1000L

        const val MAX_LIMIT = 50
        const val MAX_PAGE = 200
        const val MAX_QUERY_LENGTH = 100

        /** Most hashes or ids one lookup may carry; both sources cap their batch endpoints. */
        const val MAX_HASH_BATCH = 200

        private const val MAX_CACHE_ENTRIES = 500
        private const val HANGAR_VERSION_LIMIT = 25
        private const val CURSEFORGE_FILE_LIMIT = 50

        /** Modrinth and Hangar both ask applications to identify themselves. */
        private const val USER_AGENT = "PanoMC/pano-web-platform (panomc.com)"

        /** What a Modrinth modpack's downloadable archive is called. */
        const val MODPACK_EXTENSION = ".mrpack"

        const val REASON_NO_PLUGIN_PLATFORM = "NO_PLUGIN_PLATFORM"
        const val REASON_HANGAR_PLATFORM = "UNSUPPORTED_PLATFORM"
        const val REASON_CURSEFORGE_KEY = "API_KEY_MISSING"
    }
}
