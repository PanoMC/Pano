package com.panomc.platform.node

import com.panomc.platform.ReleaseStage
import java.util.concurrent.ConcurrentHashMap
import com.panomc.platform.server.MinecraftJavaVersions
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.server.ServerType
import com.panomc.platform.update.ReleaseInfo
import com.panomc.platform.update.ReleaseLookup
import com.panomc.platform.update.ReleaseProduct
import io.vertx.core.Vertx
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
 * development install points a node at the jars it just built. Otherwise the newest release of the
 * plugin is looked up through [ReleaseLookup] (the Pano API first, GitHub as the fallback, per
 * `update-source`) and its jar downloaded from the GitHub release assets.
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
 * by prefix (GitHub lists them) or named after the tag (the Pano API does not). That lookup is cached
 * for an hour, like the software catalog, because every install would otherwise spend one of
 * api.github.com's sixty anonymous requests an hour whenever the Pano API cannot answer.
 *
 * Nothing here throws. A plugin that cannot be resolved means a managed server that installs
 * without one — an inconvenience an admin fixes with `/pano connect` — and is never a reason to
 * fail an install that would otherwise work.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class ManagedPluginJarResolver(
    private val configManager: ConfigManager,
    private val logger: Logger,
    private val vertx: Vertx,
    private val releaseLookup: ReleaseLookup
) {
    /**
     * One release lookup: the asset URL per platform, and the version of the release they came from
     * per platform — the version an install from these assets would put in a server (SM-61).
     */
    private data class CacheEntry(val assets: Map<String, String>, val versions: Map<String, String>, val storedAt: Long)

    /** One lookup per release channel: an alpha install and a beta one do not share an answer. */
    private val releaseCache = ConcurrentHashMap<ReleaseStage, CacheEntry>()

    /** Whether a background lookup for [latestVersionOrWarm] is already on its way. */
    private val warming: MutableSet<ReleaseStage> = ConcurrentHashMap.newKeySet()

    /**
     * The release channel a plugin for one server is taken from.
     *
     * A server that has no Pano plugin yet gets the channel this Pano itself follows: a beta Pano
     * puts a beta plugin into the servers it creates, never an alpha one. A plugin that is already
     * installed is updated within the channel it came from ([installedVersion] says which), so
     * somebody who put an alpha build in by hand keeps getting alphas and nobody is moved to a less
     * finished channel by an update.
     */
    fun channelFor(installedVersion: String?): ReleaseStage =
        channelOf(installedVersion) ?: configManager.config.releaseChannel

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
    fun latestVersionOrWarm(type: ServerType, installedVersion: String? = null): String? {
        val platform = platformOf(type) ?: return null

        if (localJarFor(platform, warn = false) != null) {
            return LOCAL_BUILD
        }

        val channel = channelFor(installedVersion)
        val entry = releaseCache[channel]

        if (entry == null || System.currentTimeMillis() - entry.storedAt > VERSION_TTL_MS) {
            warm(channel)
        }

        return entry?.versions?.get(platform)
    }

    private fun warm(channel: ReleaseStage) {
        if (!warming.add(channel)) {
            return
        }

        CoroutineScope(vertx.dispatcher()).launch {
            try {
                cachedAssets(channel)
            } finally {
                warming.remove(channel)
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
    suspend fun resolve(type: ServerType, installedVersion: String? = null): String? {
        val platform = platformOf(type) ?: return null

        // Relative on purpose: Pano does not know which address this particular node reaches it
        // on -- a LAN address, a tunnel, the public hostname -- and the node does.
        localJarFor(platform)?.let { return pluginJarPath(platform) }

        return releaseAssetUrl(platform, channelFor(installedVersion))
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

    private suspend fun releaseAssetUrl(platform: String, channel: ReleaseStage): String? {
        val assets = cachedAssets(channel) ?: return null

        val url = assets[platform]

        if (url == null) {
            logger.warn("The newest $PLUGIN_REPO release publishes no ${assetPrefix(platform)}*.jar.")
        }

        return url
    }

    /**
     * Asset download URLs of the newest plugin release on [channel], keyed by platform.
     *
     * The channel's own releases first. Only when it has none at all -- the plugin has never cut a
     * stable release, so a stable Pano would find nothing and its servers would silently never get
     * a plugin -- the next less finished channel is asked instead, and the log says so.
     */
    private suspend fun cachedAssets(channel: ReleaseStage): Map<String, String>? {
        releaseCache[channel]?.let { entry ->
            if (System.currentTimeMillis() - entry.storedAt <= CACHE_TTL_MS) {
                return entry.assets
            }
        }

        for (candidate in listOf(channel) + fallbacksOf(channel)) {
            val releases = try {
                releaseLookup.lookup(ReleaseProduct.PANO_MC_PLUGIN, candidate)
            } catch (e: Exception) {
                logger.warn("Could not look up the $PLUGIN_REPO ${candidate.stage} releases: ${e.message}")

                return null
            }

            val newest = newestAssets(releases.releases)

            if (newest.assets.isEmpty()) {
                continue
            }

            if (candidate != channel) {
                logger.info(
                    "$PLUGIN_REPO has no ${channel.stage} release yet; using its newest ${candidate.stage} release instead."
                )
            }

            releaseCache[channel] = CacheEntry(newest.assets, newest.versions, System.currentTimeMillis())

            return newest.assets
        }

        return null
    }

    companion object {
        /** Where the plugin's releases live. Verified against the repository's own git remote. */
        const val PLUGIN_REPO = "PanoMC/pano-mc-plugin"

        /** How long a release lookup is reused. Same hour the software catalog caches for. */
        const val CACHE_TTL_MS = 60 * 60 * 1000L

        /**
         * How old a release lookup the Overview's "update available" may be answered from (SM-61):
         * six hours, so the badge costs at most four of api.github.com's anonymous requests a day.
         */
        const val VERSION_TTL_MS = 6 * 60 * 60 * 1000L

        /** What a development jar reports as its version, and what [latestVersionOrWarm] says for one. */
        const val LOCAL_BUILD = "local-build"

        private val STABLE_VERSION = Regex("""^\d+\.\d+\.\d+$""")

        /**
         * The channel a plugin version was released on (`1.0.0-alpha.66` -> alpha, `1.0.0-beta.7` ->
         * beta, `1.0.0` -> release), or null when the version does not say: no plugin installed, or a
         * development build.
         */
        fun channelOf(version: String?): ReleaseStage? {
            val value = version?.trim()?.removePrefix("v")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

            return when {
                value.contains("-alpha") -> ReleaseStage.ALPHA
                value.contains("-beta") -> ReleaseStage.BETA
                STABLE_VERSION.matches(value) -> ReleaseStage.RELEASE
                else -> null
            }
        }

        /** Where to look when [channel] has no release at all: only ever towards less finished. */
        fun fallbacksOf(channel: ReleaseStage): List<ReleaseStage> = when (channel) {
            ReleaseStage.RELEASE -> listOf(ReleaseStage.BETA, ReleaseStage.ALPHA)
            ReleaseStage.BETA -> listOf(ReleaseStage.ALPHA)
            ReleaseStage.ALPHA -> emptyList()
        }

        /**
         * One release lookup: the asset URL per platform, and the version of the release they came from
         * per platform.
         */
        data class NewestAssets(val assets: Map<String, String>, val versions: Map<String, String>)

        /**
         * Picks the first release that actually carries plugin jars.
         *
         * The list comes newest first. When GitHub answered, a release whose assets are still
         * uploading, or one that only carries a LICENSE, is skipped rather than treated as "no plugin
         * exists". The Pano API lists no assets, so every platform's jar is named after the tag
         * (`pano-<platform>-<version>.jar`, how every release so far publishes them). Either way the
         * URL is built from the tag, never taken from the answer.
         */
        fun newestAssets(releases: List<ReleaseInfo>): NewestAssets {
            releases.forEach { release ->
                val resolved = mutableMapOf<String, String>()
                val version = releaseVersion(release.tag)

                if (release.assets.isEmpty()) {
                    if (version != null) {
                        PLATFORMS.forEach { platform ->
                            resolved[platform] = ReleaseProduct.PANO_MC_PLUGIN.assetUrl(release.tag, "${assetPrefix(platform)}$version.jar")
                        }
                    }
                } else {
                    PLATFORMS.forEach { platform ->
                        release.assets.firstOrNull { matchesAsset(it.name, platform) }?.let {
                            resolved[platform] = ReleaseProduct.PANO_MC_PLUGIN.assetUrl(release.tag, it.name)
                        }
                    }
                }

                if (resolved.isNotEmpty()) {
                    // The release's tag is the version of every jar in it (`v1.0.0-alpha.62`), which
                    // is what an installed plugin reports back once it runs.
                    return NewestAssets(
                        resolved,
                        if (version == null) emptyMap() else resolved.keys.associateWith { version }
                    )
                }
            }

            return NewestAssets(emptyMap(), emptyMap())
        }

        /** A release tag as a version: `v1.0.0-alpha.62` → `1.0.0-alpha.62`; blank is none. */
        fun releaseVersion(tag: String?): String? = tag?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }

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

        /**
         * The oldest Minecraft the Fabric build of the Pano mod loads on: its `fabric.mod.json`
         * declares `"minecraft": ">=26.1"` (and Java 25). Minecraft 26.1 is the first release without
         * obfuscation, and the mod is compiled against its real names; an older Fabric server
         * refuses to load it ("Incompatible mods found!") and so never starts at all.
         */
        const val FABRIC_MIN_MINECRAFT = "26.1"

        /**
         * Whether the Pano plugin for [type] can run on Minecraft [version]. Only the Fabric build
         * has a floor; an unknown version is given the benefit of the doubt, as it always was.
         */
        fun supportsMinecraft(type: ServerType, version: String?): Boolean {
            if (platformOf(type) != "fabric") {
                return true
            }

            val known = version?.trim()?.takeIf { it.isNotEmpty() } ?: return true

            return compareMinecraft(known, FABRIC_MIN_MINECRAFT) >= 0
        }

        /** The Java the Pano plugin is compiled for; older runtimes cannot load it at all. */
        const val PLUGIN_MIN_JAVA = 11

        /**
         * Whether this server will run on a Java too old for the Pano plugin.
         *
         * The pinned runtime when there is one, otherwise the lowest Java its Minecraft version
         * runs on -- which is what the node picks when the host has it. A 1.8 server lands on
         * Java 8, where the plugin fails with `UnsupportedClassVersionError` on every start and
         * the server never links; leaving the plugin out and saying so is the honest outcome.
         * A type with no plugin at all is not "too old", it is a different sentence.
         */
        fun javaTooOld(type: ServerType, version: String?, pinnedJava: Int?): Boolean {
            if (platformOf(type) == null) {
                return false
            }

            val java = pinnedJava?.takeIf { it > 0 } ?: MinecraftJavaVersions.minimumFor(version)

            return java < PLUGIN_MIN_JAVA
        }

        /**
         * Orders Minecraft versions by their numbers: `1.21.8` < `26.1` < `26.1.2` < `26.3`. A part's
         * leading digits count and the rest is ignored, so `26.4-snapshot-1` sorts as 26.4 and a
         * weekly snapshot like `25w45a` as 25 -- before the 26.1 it led up to.
         */
        fun compareMinecraft(a: String, b: String): Int {
            fun parts(version: String) = version.substringBefore('-').split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }

            val left = parts(a)
            val right = parts(b)

            for (index in 0 until maxOf(left.size, right.size)) {
                val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }

                if (difference != 0) {
                    return difference
                }
            }

            return 0
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
