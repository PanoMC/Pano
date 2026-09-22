package com.panomc.platform.server.software

import com.panomc.platform.server.MinecraftJavaVersions
import com.panomc.platform.server.ServerType
import com.panomc.platform.server.software.dto.SoftwareBuildSpec
import com.panomc.platform.server.software.dto.SoftwareCatalogEntry
import com.panomc.platform.server.software.dto.SoftwareResolution
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * What server software Pano can install, and where each build actually lives.
 *
 * Every upstream here is a third-party service that can be slow, rate limited, reshaped or simply
 * down, and none of that is worth failing a panel request over: an empty catalog renders as "no
 * versions available", a failed resolution is reported as a version that cannot be installed right
 * now, and neither takes anything else with it. That is why **nothing in this class throws** —
 * failures are logged and turned into empty results.
 *
 * Results are cached for an hour. These lists change a few times a week at most, an admin opening
 * the wizard twice must not mean two round trips to Mojang, and an upstream outage then only shows
 * up once the cache goes cold.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ServerSoftwareCatalog(
    private val webClient: WebClient,
    private val logger: Logger
) {
    private data class CacheEntry<T>(val value: T, val storedAt: Long)

    private val catalogCache = ConcurrentHashMap<String, CacheEntry<List<String>>>()
    private val resolutionCache = ConcurrentHashMap<String, CacheEntry<SoftwareResolution>>()

    /**
     * Every installable software with the versions currently on offer.
     *
     * Providers are queried one after another rather than in parallel: this is a rarely used
     * screen, the results are cached for an hour, and hitting five APIs at once from every panel
     * that opens the wizard is a good way to get rate limited by all five.
     */
    suspend fun getCatalog(): List<SoftwareCatalogEntry> = PROVIDERS.map { provider ->
        val versions = getVersions(provider)

        SoftwareCatalogEntry(
            id = provider.id,
            name = provider.displayName,
            recommended = provider.recommended,
            versions = versions,
            recommendedVersion = SoftwareVersions.recommended(versions),
            note = provider.note
        )
    }

    /** Versions on offer for one software id, or an empty list when it is unknown or unreachable. */
    suspend fun getVersions(id: String): List<String> {
        val provider = PROVIDERS.firstOrNull { it.id == id } ?: return emptyList()

        return getVersions(provider)
    }

    /**
     * The version to install for [id] when the wizard sent none, or null when there is nothing to
     * install.
     *
     * The newest release rather than the newest build, which is the whole point: an omitted
     * version used to mean "whatever sorts first", and what sorts first is a snapshot on every
     * upstream that publishes one.
     */
    suspend fun recommendedVersion(id: String): String? {
        val provider = PROVIDERS.firstOrNull { it.id == id } ?: return null

        return SoftwareVersions.recommended(getVersions(provider))
    }

    /**
     * Resolves the download (and, where one is needed, installer) URL for one version.
     *
     * Returns `null` when the software is unknown, the version is not one this software offers, or
     * the upstream could not be reached — the caller turns that into a clean error instead of
     * pushing an install to a node that has nothing to download.
     */
    suspend fun resolve(id: String, version: String): SoftwareResolution? {
        val provider = PROVIDERS.firstOrNull { it.id == id } ?: return null

        if (!ServerSoftwareUrls.isSafeSegment(version)) {
            return null
        }

        val cacheKey = "$id:$version"

        cached(resolutionCache, cacheKey)?.let { return it }

        val resolution = try {
            when (provider.kind) {
                ProviderKind.VANILLA -> resolveVanilla(version)
                ProviderKind.PAPER -> resolvePaper(provider, version)
                ProviderKind.PURPUR -> resolvePurpur(version)
                ProviderKind.FABRIC -> resolveFabric(version)
                ProviderKind.SPIGOT_BUILDTOOLS -> resolveSpigot(version)
                ProviderKind.BUNGEECORD_JENKINS -> resolveBungeeCord(version)
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve $id $version from its software provider: ${e.message}")

            null
        } ?: return null

        // Spigot has no download at all -- it is compiled on the node -- so "nothing to install"
        // is now the absence of both a URL and a build to run.
        if (resolution.downloadUrl == null && resolution.buildSpec == null) {
            return null
        }

        // Filled centrally so every provider reports it the same way: it is a property of the
        // Minecraft version, not of where the jar is downloaded from. A provider that already
        // answered keeps its answer -- the hub knows which JDK a Spigot revision was made for,
        // and a proxy's version number is a build number the Minecraft ladder cannot read.
        val withJava = if (resolution.javaMajor != null) {
            resolution
        } else {
            resolution.copy(javaMajor = MinecraftJavaVersions.recommendedFor(version))
        }

        resolutionCache[cacheKey] = CacheEntry(withJava, System.currentTimeMillis())

        return withJava
    }

    /** The [ServerType] a catalog id installs, so the created server row is typed correctly. */
    fun serverTypeOf(id: String): ServerType? = PROVIDERS.firstOrNull { it.id == id }?.serverType

    private suspend fun getVersions(provider: Provider): List<String> {
        cached(catalogCache, provider.id)?.let { return it }

        val listed = try {
            when (provider.kind) {
                ProviderKind.VANILLA -> vanillaVersions()
                ProviderKind.PAPER -> paperVersions(provider)
                ProviderKind.PURPUR -> purpurVersions()
                ProviderKind.FABRIC -> fabricVersions()
                ProviderKind.SPIGOT_BUILDTOOLS -> spigotVersions()
                ProviderKind.BUNGEECORD_JENKINS -> bungeeCordVersions()
            }
        } catch (e: Exception) {
            logger.warn("Failed to list versions of ${provider.id}: ${e.message}")

            emptyList()
        }

        // Releases first, snapshots after, so "the first version" is never a development build --
        // and, for a proxy, only what the upstream has actually blessed survives at all.
        val versions = try {
            if (provider.probesBuilds) proxyVersions(provider, listed) else SoftwareVersions.stableFirst(listed)
        } catch (e: Exception) {
            logger.warn("Failed to check the builds of ${provider.id}: ${e.message}")

            SoftwareVersions.stableFirst(listed)
        }

        // An empty result is not cached: it almost always means the upstream was unreachable, and
        // caching that would keep the wizard empty for an hour after the outage ended.
        if (versions.isNotEmpty()) {
            catalogCache[provider.id] = CacheEntry(versions, System.currentTimeMillis())
        }

        return versions
    }

    // ---------------------------------------------------------------------------------------
    // Mojang (vanilla)
    // ---------------------------------------------------------------------------------------

    private suspend fun vanillaVersions(): List<String> {
        val manifest = getJsonObject(ServerSoftwareUrls.MOJANG_VERSION_MANIFEST) ?: return emptyList()

        return (manifest.getJsonArray("versions") ?: JsonArray())
            .mapNotNull { it as? JsonObject }
            // Snapshots outnumber releases by an order of magnitude and nobody runs a server on
            // one by accident; the wizard lists releases only.
            .filter { it.getString("type") == "release" }
            .mapNotNull { it.getString("id") }
            .take(MAX_VERSIONS)
    }

    private suspend fun resolveVanilla(version: String): SoftwareResolution? {
        val manifest = getJsonObject(ServerSoftwareUrls.MOJANG_VERSION_MANIFEST) ?: return null

        // The per-version URL is taken from Mojang's own manifest rather than built by hand: it
        // contains a content hash that cannot be derived from the version number.
        val versionUrl = (manifest.getJsonArray("versions") ?: JsonArray())
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.getString("id") == version }
            ?.getString("url")
            ?: return null

        val versionJson = getJsonObject(versionUrl) ?: return null

        val serverUrl = versionJson.getJsonObject("downloads")
            ?.getJsonObject("server")
            ?.getString("url")

        return SoftwareResolution(ServerType.VANILLA.name.lowercase(), version, serverUrl)
    }

    // ---------------------------------------------------------------------------------------
    // PaperMC (paper, folia, velocity, waterfall)
    // ---------------------------------------------------------------------------------------

    private suspend fun paperVersions(provider: Provider): List<String> {
        val url = ServerSoftwareUrls.paperProject(provider.upstreamProject) ?: return emptyList()

        val project = getJsonObject(url) ?: return emptyList()

        return PaperFillResponses.versions(project).take(MAX_VERSIONS)
    }

    private suspend fun resolvePaper(provider: Provider, version: String): SoftwareResolution? {
        val url = ServerSoftwareUrls.paperBuilds(provider.upstreamProject, version) ?: return null

        // v3 answers the builds endpoint with a bare array, and the download URL is in it rather
        // than assembled from the build number: fill serves jars from an object store keyed by
        // content hash, which is not something a URL builder can guess.
        val build = PaperFillResponses.latestBuild(getJsonArray(url)) ?: return null

        return SoftwareResolution(
            id = provider.id,
            version = version,
            downloadUrl = build.downloadUrl,
            build = build.build.toString(),
            sha256 = build.sha256,
            channel = build.channel
        )
    }

    // ---------------------------------------------------------------------------------------
    // Purpur
    // ---------------------------------------------------------------------------------------

    private suspend fun purpurVersions(): List<String> {
        val project = getJsonObject(ServerSoftwareUrls.purpurVersions()) ?: return emptyList()

        return (project.getJsonArray("versions") ?: JsonArray())
            .mapNotNull { it as? String }
            .reversed()
            .take(MAX_VERSIONS)
    }

    private suspend fun resolvePurpur(version: String): SoftwareResolution? {
        val url = ServerSoftwareUrls.purpurVersion(version) ?: return null

        val build = getJsonObject(url)?.getJsonObject("builds")?.getString("latest") ?: return null

        return SoftwareResolution(
            id = ServerType.PURPUR.name.lowercase(),
            version = version,
            downloadUrl = ServerSoftwareUrls.purpurDownload(version, build),
            build = build
        )
    }

    // ---------------------------------------------------------------------------------------
    // Fabric
    // ---------------------------------------------------------------------------------------

    private suspend fun fabricVersions(): List<String> {
        val versions = getJsonArray(ServerSoftwareUrls.fabricGameVersions()) ?: return emptyList()

        return versions
            .mapNotNull { it as? JsonObject }
            .filter { it.getBoolean("stable", false) }
            .mapNotNull { it.getString("version") }
            .take(MAX_VERSIONS)
    }

    private suspend fun resolveFabric(version: String): SoftwareResolution? {
        val loaderUrl = ServerSoftwareUrls.fabricLoaderVersions(version) ?: return null

        val loaderVersion = getJsonArray(loaderUrl)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull()
            ?.getJsonObject("loader")
            ?.getString("version")
            ?: return null

        val installerVersion = getJsonArray(ServerSoftwareUrls.fabricInstallerVersions())
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.getBoolean("stable", false) }
            ?.getString("version")
            ?: return null

        // Fabric publishes a ready-made launcher jar, so the node downloads one file and runs it;
        // no installer has to be executed on the host.
        return SoftwareResolution(
            id = ServerType.FABRIC.name.lowercase(),
            version = version,
            downloadUrl = ServerSoftwareUrls.fabricServerJar(version, loaderVersion, installerVersion),
            build = loaderVersion
        )
    }

    // ---------------------------------------------------------------------------------------
    // Spigot (compiled on the node by BuildTools)
    // ---------------------------------------------------------------------------------------

    private suspend fun spigotVersions(): List<String> =
        SpigotHubResponses.versions(getText(ServerSoftwareUrls.SPIGOT_VERSIONS)).take(MAX_VERSIONS)

    /**
     * Spigot's "download", which is an instruction to compile rather than a URL.
     *
     * The per-revision file is fetched even though nothing in it is strictly required: it is the
     * only way to know the hub has this revision at all, and finding that out now is the
     * difference between a wizard saying "no" and a node spending ten minutes discovering it.
     */
    private suspend fun resolveSpigot(version: String): SoftwareResolution? {
        val url = ServerSoftwareUrls.spigotVersion(version) ?: return null

        val info = getJsonObject(url) ?: return null

        val javaMajor = SpigotHubResponses.javaMajor(info) ?: MinecraftJavaVersions.recommendedFor(version)

        return SoftwareResolution(
            id = ServerType.SPIGOT.name.lowercase(),
            version = version,
            downloadUrl = null,
            // The hub's own name for the revision (a BuildTools build number), so a reinstall can
            // be told from the build it replaced.
            build = info.getString("name"),
            javaMajor = javaMajor,
            buildSpec = SoftwareBuildSpec(
                tool = SoftwareBuildSpec.BUILDTOOLS,
                rev = version,
                toolUrl = ServerSoftwareUrls.BUILDTOOLS_JAR,
                javaMajor = javaMajor
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // BungeeCord (md-5's Jenkins)
    // ---------------------------------------------------------------------------------------

    private suspend fun bungeeCordVersions(): List<String> {
        val response = getJsonObject(ServerSoftwareUrls.bungeeCordBuilds(MAX_VERSIONS)) ?: return emptyList()

        val builds = BungeeCordJenkins.successfulBuilds(response).take(MAX_VERSIONS)

        if (builds.isEmpty()) {
            return emptyList()
        }

        // `latest` is offered as its own version and put first, because it is what a BungeeCord
        // network is meant to run: md-5 publishes no releases, and pinning build 2096 forever is
        // a choice somebody should have to make deliberately.
        return listOf(ServerSoftwareUrls.BUNGEECORD_LATEST) + builds
    }

    private suspend fun resolveBungeeCord(version: String): SoftwareResolution? {
        val reference = ServerSoftwareUrls.bungeeCordReference(version)

        // Only a build number or `latest` ever reaches Jenkins: everything else is a version
        // somebody typed, and the path it would build is not a build.
        if (reference != ServerSoftwareUrls.BUNGEECORD_LAST_SUCCESSFUL && version.toIntOrNull() == null) {
            return null
        }

        val url = ServerSoftwareUrls.bungeeCordBuild(reference) ?: return null

        val build = getJsonObject(url) ?: return null

        if (!BungeeCordJenkins.hasArtifact(build)) {
            return null
        }

        return SoftwareResolution(
            id = ServerType.BUNGEECORD.name.lowercase(),
            version = version,
            downloadUrl = ServerSoftwareUrls.bungeeCordDownload(reference),
            // Resolved to the real number even for `latest`, so the task and the server row say
            // which build was installed rather than "the newest one, whenever that was".
            build = BungeeCordJenkins.buildNumber(build) ?: version.takeIf { it.toIntOrNull() != null },
            // Jenkins does publish an MD5 per build, but it fingerprints the Maven artifact and
            // not the shaded jar the artifact URL serves -- the two genuinely differ, so sending
            // it would fail every BungeeCord install on a checksum that was never wrong. See
            // [BungeeCordJenkins.mavenFingerprintMd5].
            md5 = null,
            // BungeeCord runs on 8 and up and is built for far older, so the ladder has nothing
            // useful to say about a build number. 17 is the oldest LTS worth putting a proxy on.
            javaMajor = BUNGEECORD_JAVA
        )
    }

    // ---------------------------------------------------------------------------------------
    // Proxies
    // ---------------------------------------------------------------------------------------

    /**
     * The versions of a proxy that are actually installable.
     *
     * A proxy is the one place where the version number is not evidence enough. Velocity carries
     * its development work in the version itself (`4.2.1-SNAPSHOT`, a build compiled for Java 25),
     * and a version that looks like a release can still have nothing behind it but experimental
     * builds -- so each candidate is confirmed against the builds endpoint and only a `STABLE` or
     * `RECOMMENDED` newest build keeps it on the list.
     *
     * Two things bound the cost of that. Only the versions whose *names* already look like
     * releases are probed, newest first, and only [PROXY_PROBE_LIMIT] of them; and the first
     * version that cannot be resolved at all stops the walk, because an upstream that is down
     * would otherwise mean a dozen ten-second timeouts on a panel request. Whatever could not be
     * confirmed is kept rather than dropped -- an empty proxy list is a wizard nobody can use.
     */
    private suspend fun proxyVersions(provider: Provider, versions: List<String>): List<String> {
        val candidates = versions.filter { SoftwareVersions.isStable(it) }

        if (candidates.isEmpty()) {
            return SoftwareVersions.stableFirst(versions)
        }

        val confirmed = mutableListOf<String>()

        for (version in candidates.take(PROXY_PROBE_LIMIT)) {
            val channel = resolve(provider.id, version)?.channel ?: break

            if (channel.uppercase() in STABLE_CHANNELS) {
                confirmed.add(version)
            }
        }

        return confirmed.ifEmpty { candidates }
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    private fun <T> cached(cache: ConcurrentHashMap<String, CacheEntry<T>>, key: String): T? {
        val entry = cache[key] ?: return null

        if (System.currentTimeMillis() - entry.storedAt > CACHE_TTL_MS) {
            cache.remove(key)

            return null
        }

        return entry.value
    }

    private suspend fun getJsonObject(url: String): JsonObject? = try {
        val response = webClient.getAbs(url)
            .timeout(REQUEST_TIMEOUT_MS)
            .send()
            .coAwait()

        if (response.statusCode() != 200) {
            logger.warn("Software provider $url answered ${response.statusCode()}")

            null
        } else {
            response.bodyAsJsonObject()
        }
    } catch (e: Exception) {
        logger.warn("Software provider $url is unreachable: ${e.message}")

        null
    }

    private suspend fun getJsonArray(url: String): JsonArray? = try {
        val response = webClient.getAbs(url)
            .timeout(REQUEST_TIMEOUT_MS)
            .send()
            .coAwait()

        if (response.statusCode() != 200) {
            logger.warn("Software provider $url answered ${response.statusCode()}")

            null
        } else {
            response.bodyAsJsonArray()
        }
    } catch (e: Exception) {
        logger.warn("Software provider $url is unreachable: ${e.message}")

        null
    }

    /**
     * Fetches a body that is not JSON -- SpigotMC's revision index is an HTML directory listing.
     *
     * Size-checked because this is the one upstream whose response is not a shape Pano chose:
     * four thousand `<a>` elements is about 400 KB today, and a listing that has grown into
     * something else entirely is better refused than parsed.
     */
    private suspend fun getText(url: String): String? = try {
        val response = webClient.getAbs(url)
            .timeout(REQUEST_TIMEOUT_MS)
            .send()
            .coAwait()

        val body = response.bodyAsString()

        when {
            response.statusCode() != 200 -> {
                logger.warn("Software provider $url answered ${response.statusCode()}")

                null
            }

            body != null && body.length > MAX_TEXT_BODY -> {
                logger.warn("Software provider $url answered ${body.length} characters; ignoring it.")

                null
            }

            else -> body
        }
    } catch (e: Exception) {
        logger.warn("Software provider $url is unreachable: ${e.message}")

        null
    }

    private enum class ProviderKind {
        VANILLA,
        PAPER,
        PURPUR,
        FABRIC,

        /** SpigotMC: a revision list and a compile on the node, with no jar to download. */
        SPIGOT_BUILDTOOLS,

        /** md-5's Jenkins: build numbers rather than versions, and no release channel at all. */
        BUNGEECORD_JENKINS
    }

    private data class Provider(
        val id: String,
        val displayName: String,
        val kind: ProviderKind,
        val serverType: ServerType,
        val recommended: Boolean = false,
        /** Project id at the upstream API, which is not always the catalog id. */
        val upstreamProject: String = id,
        val note: String? = null
    ) {
        /** Whether this software is a proxy: it runs no world, and it can front a network. */
        val isProxy: Boolean get() = serverType.isProxy

        /**
         * Whether each offered version has to be confirmed against a builds endpoint.
         *
         * Only PaperMC's proxies, and only because their version numbers lie -- see
         * [proxyVersions]. A BungeeCord "version" is a Jenkins build that either succeeded or is
         * not on the list at all, so probing it would be one request per version for an answer
         * already in hand.
         */
        val probesBuilds: Boolean get() = kind == ProviderKind.PAPER && isProxy
    }

    companion object {
        /** How long a listing or a resolved URL is reused before asking upstream again. */
        const val CACHE_TTL_MS = 60 * 60 * 1000L

        private const val REQUEST_TIMEOUT_MS = 10_000L

        /** Versions offered per software; older ones are behind "show all" in the wizard, later. */
        private const val MAX_VERSIONS = 60

        /** How many proxy versions are checked against the builds endpoint before giving up. */
        private const val PROXY_PROBE_LIMIT = 12

        /** PaperMC's names for "this build is finished", in both the old and the current spelling. */
        private val STABLE_CHANNELS = setOf("STABLE", "RECOMMENDED")

        /** What a BungeeCord proxy is started with; see [resolveBungeeCord]. */
        const val BUNGEECORD_JAVA = 17

        /** How long an HTML listing may be before it is treated as something other than a listing. */
        private const val MAX_TEXT_BODY = 4 * 1024 * 1024

        // Forge, NeoForge and Quilt are still absent: each needs its own installer run on the
        // node, which is its own ticket. The enum already knows those types so a server imported
        // from disk can be labelled correctly.
        private val PROVIDERS = listOf(
            Provider("paper", "Paper", ProviderKind.PAPER, ServerType.PAPER, recommended = true),
            Provider("purpur", "Purpur", ProviderKind.PURPUR, ServerType.PURPUR),
            Provider("folia", "Folia", ProviderKind.PAPER, ServerType.FOLIA),
            // "build" is what tells the wizard this one is compiled on the node rather than
            // downloaded, which is a ten-minute first install and a git the host has to have.
            Provider("spigot", "Spigot", ProviderKind.SPIGOT_BUILDTOOLS, ServerType.SPIGOT, note = "build"),
            Provider("fabric", "Fabric", ProviderKind.FABRIC, ServerType.FABRIC),
            Provider("vanilla", "Vanilla", ProviderKind.VANILLA, ServerType.VANILLA),
            Provider("velocity", "Velocity", ProviderKind.PAPER, ServerType.VELOCITY),
            Provider(
                "waterfall",
                "Waterfall",
                ProviderKind.PAPER,
                ServerType.WATERFALL,
                note = "deprecated"
            ),
            // Last, and after Waterfall, because Velocity is what a new network should be built
            // on; BungeeCord is here for the networks and plugins that still need it.
            Provider(
                "bungeecord",
                "BungeeCord",
                ProviderKind.BUNGEECORD_JENKINS,
                ServerType.BUNGEECORD,
                note = "jenkins"
            )
        )
    }
}
