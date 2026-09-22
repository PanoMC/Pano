package com.panomc.platform.node

import com.panomc.platform.config.ConfigManager
import com.panomc.platform.server.ServerType
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the `pano-mc-plugin` build that belongs in a managed server, so it comes up already linked.
 *
 * Two sources, in this order. A `managed-servers.plugin-jar-dir` in config.conf wins: it is how a
 * development install points a node at the jars it just built. Otherwise the newest GitHub release
 * of the plugin is asked for its assets.
 *
 * A local jar used to be handed over as a `file://` URL, which quietly assumed every node runs on
 * this machine. A node on a VPS cannot open `/home/someone/pano-mc-plugin/...`, so its install
 * threw, the plugin never landed, and the install still reported DONE — a managed server that came
 * up unlinked with nothing saying why. So a local jar is now published instead: the URL is
 * [pluginJarPath], which the node resolves against its own Pano address and downloads like any
 * other artifact.
 *
 * **The release assets are versioned** (`pano-spigot-1.0.0-alpha.62.jar`), so there is no
 * `releases/latest/download/<name>` shortcut: the release has to be looked up and the asset found
 * by prefix. That lookup is cached for an hour, like the software catalog, because every install
 * would otherwise spend one of api.github.com's sixty anonymous requests an hour.
 *
 * Nothing here throws. A plugin that cannot be resolved means a managed server that installs
 * without one — an inconvenience an admin fixes with `/pano connect` — and is never a reason to
 * fail an install that would otherwise work.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedPluginJarResolver(
    private val webClient: WebClient,
    private val configManager: ConfigManager,
    private val logger: Logger,
    private val vertx: Vertx
) {
    /**
     * One release lookup: the asset URL per platform, and the version of the release they came from
     * per platform — the version an install from these assets would put in a server (SM-61).
     */
    private data class CacheEntry(val assets: Map<String, String>, val versions: Map<String, String>, val storedAt: Long)

    private val releaseCache = AtomicReference<CacheEntry?>(null)

    /** Whether a background lookup for [latestVersionOrWarm] is already on its way. */
    private val warming = AtomicBoolean(false)

    /**
     * The Pano plugin version an install for [type] would put in the server right now, without ever
     * waiting on GitHub (SM-61, §2.4.26).
     *
     * [LOCAL_BUILD] when a development jar directory is configured — that jar is what an install
     * takes, and it has no version to compare. Otherwise the newest release's version as last looked
     * up, or null before the first lookup has landed. A lookup older than [VERSION_TTL_MS] (or none
     * at all) is refreshed in the background, and until it lands the old answer — or null — is what
     * the caller gets: the Overview must never sit on api.github.com.
     */
    fun latestVersionOrWarm(type: ServerType): String? {
        val platform = platformOf(type) ?: return null

        if (localJarFor(platform, warn = false) != null) {
            return LOCAL_BUILD
        }

        val entry = releaseCache.get()

        if (entry == null || System.currentTimeMillis() - entry.storedAt > VERSION_TTL_MS) {
            warm()
        }

        return entry?.versions?.get(platform)
    }

    private fun warm() {
        if (!warming.compareAndSet(false, true)) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                cachedAssets()
            } finally {
                warming.set(false)
            }
        }
    }

    /**
     * The URL a node should fetch the plugin for [type] from, or null when there is none.
     *
     * Null covers both "this software runs no Pano plugin" (vanilla and the Forge family) and
     * "the release could not be reached right now"; the caller tells those apart with
     * [platformOf] and reports the difference on the install task.
     */
    suspend fun resolve(type: ServerType): String? {
        val platform = platformOf(type) ?: return null

        // Relative on purpose: Pano does not know which address this particular node reaches it
        // on -- a LAN address, a tunnel, the public hostname -- and the node does.
        localJarFor(platform)?.let { return pluginJarPath(platform) }

        return releaseAssetUrl(platform)
    }

    /**
     * The configured development directory's jar for [platform], if one is there.
     *
     * Public because `GET /api/node/plugin-jars/:platform` serves exactly this file: the endpoint
     * and the URL that points at it have to agree about which jar is meant, and the only way to
     * guarantee that is for both to ask the same question.
     */
    fun localJarFor(platform: String, warn: Boolean = true): File? {
        val configured = configManager.config.effectiveManagedServers.pluginJarDir
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val directory = File(configured)

        if (!directory.isDirectory) {
            if (warn) {
                logger.warn("managed-servers.plugin-jar-dir points at ${directory.absolutePath}, which is not a directory.")
            }

            return null
        }

        val jar = findLocalJar(directory, platform)

        if (jar == null && warn) {
            logger.warn("No ${assetPrefix(platform)}*.jar under ${directory.absolutePath}.")
        }

        return jar
    }

    private suspend fun releaseAssetUrl(platform: String): String? {
        val assets = cachedAssets() ?: return null

        val url = assets[platform]

        if (url == null) {
            logger.warn("The newest $PLUGIN_REPO release publishes no ${assetPrefix(platform)}*.jar.")
        }

        return url
    }

    /**
     * Asset download URLs of the newest plugin release, keyed by platform.
     *
     * `releases` rather than `releases/latest`: the plugin ships on prerelease channels, and
     * `latest` skips every prerelease — on a repository that has never cut a stable release it
     * answers 404 and managed servers would silently never get a plugin.
     */
    private suspend fun cachedAssets(): Map<String, String>? {
        releaseCache.get()?.let { entry ->
            if (System.currentTimeMillis() - entry.storedAt <= CACHE_TTL_MS) {
                return entry.assets
            }
        }

        val releases = try {
            val response = webClient
                .getAbs("https://api.github.com/repos/$PLUGIN_REPO/releases?per_page=$RELEASE_PAGE_SIZE")
                .timeout(REQUEST_TIMEOUT_MS)
                .send()
                .coAwait()

            if (response.statusCode() != 200) {
                logger.warn("GitHub answered ${response.statusCode()} for the $PLUGIN_REPO releases.")

                null
            } else {
                response.bodyAsJsonArray()
            }
        } catch (e: Exception) {
            logger.warn("Could not reach GitHub for the $PLUGIN_REPO releases: ${e.message}")

            null
        } ?: return null

        val newest = newestAssets(releases)

        if (newest.assets.isEmpty()) {
            return null
        }

        releaseCache.set(CacheEntry(newest.assets, newest.versions, System.currentTimeMillis()))

        return newest.assets
    }

    /**
     * Picks the first release that actually carries plugin jars.
     *
     * The list comes back newest first. A release whose assets are still uploading, or one that
     * only carries a LICENSE, is skipped rather than treated as "no plugin exists".
     */
    private data class NewestAssets(val assets: Map<String, String>, val versions: Map<String, String>)

    private fun newestAssets(releases: JsonArray): NewestAssets {
        releases.forEach { element ->
            val release = element as? io.vertx.core.json.JsonObject ?: return@forEach

            if (release.getBoolean("draft", false)) {
                return@forEach
            }

            val assets = release.getJsonArray("assets") ?: return@forEach
            val resolved = mutableMapOf<String, String>()

            PLATFORMS.forEach { platform ->
                assets.forEach inner@{ assetElement ->
                    val asset = assetElement as? io.vertx.core.json.JsonObject ?: return@inner
                    val name = asset.getString("name") ?: return@inner

                    if (!matchesAsset(name, platform)) {
                        return@inner
                    }

                    asset.getString("browser_download_url")?.let { resolved.putIfAbsent(platform, it) }
                }
            }

            if (resolved.isNotEmpty()) {
                // The release's tag is the version of every jar in it (`v1.0.0-alpha.62`), which is
                // what an installed plugin reports back once it runs.
                val version = releaseVersion(release.getString("tag_name"))

                return NewestAssets(
                    resolved,
                    if (version == null) emptyMap() else resolved.keys.associateWith { version }
                )
            }
        }

        return NewestAssets(emptyMap(), emptyMap())
    }

    companion object {
        /** Where the plugin's releases live. Verified against the repository's own git remote. */
        const val PLUGIN_REPO = "PanoMC/pano-mc-plugin"

        /** How long a release lookup is reused. Same hour the software catalog caches for. */
        const val CACHE_TTL_MS = 60 * 60 * 1000L

        private const val REQUEST_TIMEOUT_MS = 15_000L

        /**
         * How old a release lookup the Overview's "update available" may be answered from (SM-61):
         * six hours, so the badge costs at most four of api.github.com's anonymous requests a day.
         */
        const val VERSION_TTL_MS = 6 * 60 * 60 * 1000L

        /** What a development jar reports as its version, and what [latestVersionOrWarm] says for one. */
        const val LOCAL_BUILD = "local-build"

        /** A release tag as a version: `v1.0.0-alpha.62` → `1.0.0-alpha.62`; blank is none. */
        fun releaseVersion(tag: String?): String? = tag?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
        private const val RELEASE_PAGE_SIZE = 10

        /** How deep the development directory is searched, so `<repo>/<module>/build/libs` is found. */
        const val MAX_LOCAL_SEARCH_DEPTH = 4

        /** The plugin modules, named exactly as their release assets are. */
        val PLATFORMS = listOf("spigot", "bungeecord", "velocity", "fabric")

        /**
         * The plugin module a server type runs, or null when that software has no Pano plugin.
         *
         * The Bukkit family all run the Spigot build, Waterfall runs BungeeCord's, and Quilt runs
         * Fabric's. Vanilla, Forge and NeoForge have no module at all, which is not a gap to be
         * filled here: those servers are managed through the node alone.
         */
        fun platformOf(type: ServerType): String? = when {
            type.isBukkitFamily -> "spigot"
            type == ServerType.VELOCITY -> "velocity"
            type == ServerType.BUNGEECORD || type == ServerType.WATERFALL -> "bungeecord"
            type == ServerType.FABRIC || type == ServerType.QUILT -> "fabric"
            else -> null
        }

        /** Where the jar goes inside the server directory. Mod loaders read `mods`, the rest `plugins`. */
        fun targetDirOf(type: ServerType): String =
            if (type == ServerType.FABRIC || type == ServerType.QUILT) "mods" else "plugins"

        /**
         * Where that platform's plugin reads its `config.conf` from, relative to the server dir.
         *
         * Each platform's data folder is decided by the plugin, not by Pano, so this mirrors what
         * the four mains actually do: Bukkit and BungeeCord use the plugin name verbatim,
         * Velocity uses its lowercase plugin id, and the Fabric mod uses `config/pano`.
         */
        fun configPathOf(type: ServerType): String? = when (platformOf(type)) {
            "spigot", "bungeecord" -> "plugins/Pano/config.conf"
            "velocity" -> "plugins/pano/config.conf"
            "fabric" -> "config/pano/config.conf"
            else -> null
        }

        /** Where Pano publishes the plugin jars it holds locally. */
        const val PLUGIN_JAR_PATH = "/api/node/plugin-jars"

        /**
         * The URL a node should fetch [platform]'s plugin from when Pano is serving it itself.
         *
         * Path only, with no host. Whoever receives it resolves it against the Pano address it is
         * already talking to, which is the only address known to work from that host.
         */
        fun pluginJarPath(platform: String) = "$PLUGIN_JAR_PATH/$platform"

        /** The stem every release asset of [platform] starts with. */
        fun assetPrefix(platform: String) = "pano-$platform-"

        /**
         * Whether [name] is a plugin jar for [platform].
         *
         * Prefix and extension only: the version is part of every asset name and is deliberately
         * not parsed, so a change in how semantic-release formats it cannot stop this matching.
         */
        fun matchesAsset(name: String, platform: String): Boolean {
            val lower = name.lowercase()

            return lower.startsWith(assetPrefix(platform)) && lower.endsWith(".jar")
        }

        /**
         * The newest matching jar under [directory], searched a few levels deep.
         *
         * Deep enough that pointing at a `pano-mc-plugin` checkout finds
         * `<module>/build/libs/pano-<platform>-local-build.jar`, and pointing straight at one
         * module's `build/libs` works too.
         */
        fun findLocalJar(directory: File, platform: String, maxDepth: Int = MAX_LOCAL_SEARCH_DEPTH): File? {
            if (!directory.isDirectory || maxDepth < 0) {
                return null
            }

            val children = directory.listFiles() ?: return null

            val here = children
                .filter { it.isFile && matchesAsset(it.name, platform) }
                .maxByOrNull { it.lastModified() }

            val deeper = children
                .filter { it.isDirectory }
                .mapNotNull { findLocalJar(it, platform, maxDepth - 1) }
                .maxByOrNull { it.lastModified() }

            return listOfNotNull(here, deeper).maxByOrNull { it.lastModified() }
        }
    }
}
