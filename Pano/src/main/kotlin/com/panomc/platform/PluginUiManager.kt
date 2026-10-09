package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.util.DevMode
import com.panomc.platform.util.FileResourceUtil.getOwnResourceStream
import com.panomc.platform.util.HashUtil.hash
import com.panomc.platform.util.PluginDevUtil
import io.vertx.core.json.JsonObject
import org.pf4j.PluginState
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** A file of a plugin's built package and the validator it is served with. */
class PackageEntry(val bytes: ByteArray, val etag: String)

/**
 * A plugin whose UI is left out because [heldBy] already owns its [namespace] (issue
 * `NAMESPACE_CLASH`, doc 01 §7). The plugin keeps running.
 */
data class NamespaceClash(val namespace: String, val pluginId: String, val heldBy: String)

/** Which entries of `plugin-ui.zip` are public package files (doc 02 §6) and how they are named. */
object PluginPackage {
    const val MANIFEST = "pano-plugin.json"

    /** Folders served as files; `client/` and `server/` are the theme's and the panel's business. */
    val FOLDERS = setOf("contract", "controllers", "samples", "widgets")

    /**
     * The entry [raw] names, in canonical form, or null when it must not be looked up at all: empty,
     * absolute, backslashes, NUL, empty / `.` / `..` segments (also after the router decoded `%2e`).
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw.contains('\\') || raw.contains('\u0000')) return null

        val segments = raw.split('/')

        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

        return segments.joinToString("/")
    }

    /** The two sheets of `client/` a widget's shadow root needs; nothing else under `client/` is served. */
    val CLIENT_SHEETS = setOf("client/fallback.css", "client/plugin.css")

    /** `pano-plugin.json`, a file below `contract/`, `controllers/`, `samples/`, `widgets/`, or exactly one of [CLIENT_SHEETS]. */
    fun isServable(entry: String): Boolean {
        val normalized = normalize(entry) ?: return false

        if (normalized == MANIFEST || normalized in CLIENT_SHEETS) return true

        val segments = normalized.split('/')

        return segments.size >= 2 && segments[0] in FOLDERS
    }

    fun contentType(entry: String): String = when (entry.substringAfterLast('.', "").lowercase()) {
        "mjs", "js" -> "text/javascript"
        "json" -> "application/json"
        "svelte" -> "text/plain; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        else -> "application/octet-stream"
    }
}

class PluginUiManager(
    /** The running config (for [DevMode]); a function so the panel switch applies without a restart. */
    private val configProvider: () -> PanoConfig? = { DevMode.currentConfig() },
    /** The `plugin-ui` source folder of a plugin in Development Mode, or null. */
    private val uiSourceDir: (String) -> File? = { PluginDevUtil.getPluginResourceDir(it, "plugin-ui") },
    private val environment: () -> Main.Companion.EnvironmentType = { Main.ENVIRONMENT }
) {
    private val log = LoggerFactory.getLogger(PluginUiManager::class.java)

    // ConcurrentHashMap: mutated on Vert.x worker threads (plugin load/unload) while HTTP handlers
    // iterate it on the event loop. Iteration is weakly-consistent and won't throw a CME.
    private val pluginUiRegisterList = ConcurrentHashMap<PanoPlugin, String>()

    // Where each plugin's plugin-ui.zip is read from (the class loader its hash was taken from).
    private val uiClassLoaders = ConcurrentHashMap<PanoPlugin, ClassLoader>()

    // Order a plugin got enabled in: 0 for every plugin registered while Pano boots (ties go to the
    // smaller plugin id), then 1, 2, ... for plugins enabled later. Decides who keeps a namespace.
    private val registrationOrder = ConcurrentHashMap<PanoPlugin, Long>()
    private val orderCounter = AtomicLong()

    @Volatile
    private var bootEnded = false

    // Package files of the real zip, per plugin and uiHash: only the served entries are kept.
    private class ZipEntries(val hash: String, val entries: Map<String, ByteArray>, val manifest: JsonObject?)

    private val zipCache = ConcurrentHashMap<PanoPlugin, ZipEntries>()

    private val reportedClashes = ConcurrentHashMap.newKeySet<NamespaceClash>()

    private fun devMode() = DevMode.isActive(configProvider(), environment())

    internal fun getRegisteredPlugins() = pluginUiRegisterList.toList()

    /**
     * UI hashes advertised to themes/panel ([GetSiteInfoAPI]) and authorized for zip download.
     *
     * [PanoPlugin.load] registers UI before start succeeds; failed/disabled plugins must not be
     * exposed or clients will try to fetch `/api/v1/plugins/:id/_/ui.zip` and error.
     *
     * A plugin whose namespace another active plugin already holds is left out (see
     * [getNamespaceClashes]); it keeps running. The first call also ends "boot" for the winner rule:
     * plugins enabled afterwards rank behind the ones that were there.
     */
    internal fun getActiveRegisteredPlugins(pluginManager: PluginManager): List<Pair<PanoPlugin, String>> {
        bootEnded = true

        val active = activeRegistered(pluginManager)
        val refused = clashesAmong(active).map { it.pluginId }.toSet()

        return active.filter { (plugin, _) -> plugin.pluginId !in refused }
    }

    /** Every plugin that is running but left out of site info because its namespace is held. */
    internal fun getNamespaceClashes(pluginManager: PluginManager): List<NamespaceClash> =
        clashesAmong(activeRegistered(pluginManager))

    private fun activeRegistered(pluginManager: PluginManager) =
        pluginUiRegisterList.toList().filter { (plugin, _) ->
            pluginManager.getPlugin(plugin.pluginId)?.pluginState == PluginState.STARTED
        }

    private fun clashesAmong(active: List<Pair<PanoPlugin, String>>): List<NamespaceClash> {
        if (active.size < 2) return emptyList()

        val clashes = mutableListOf<NamespaceClash>()

        active.map { it.first }
            .groupBy { PluginNamespace.ofManifest(it.pluginId, manifest(it)) }
            .filterValues { it.size > 1 }
            .forEach { (namespace, holders) ->
                val ranked = holders.sortedWith(
                    compareBy<PanoPlugin>({ registrationOrder[it] ?: 0L }, { it.pluginId })
                )
                val winner = ranked.first()

                ranked.drop(1).forEach { loser ->
                    val clash = NamespaceClash(namespace, loser.pluginId, winner.pluginId)

                    clashes.add(clash)

                    if (reportedClashes.add(clash)) {
                        log.warn(
                            "NAMESPACE_CLASH: plugin '{}' and '{}' both use the namespace '{}'; the UI of '{}' is left out. " +
                                "Set `namespace` in the pano.plugin.js of '{}' to fix it.",
                            winner.pluginId, loser.pluginId, namespace, loser.pluginId, loser.pluginId
                        )
                    }
                }
            }

        return clashes
    }

    internal fun getRegisteredPlugin(plugin: PanoPlugin) = pluginUiRegisterList[plugin]

    internal fun initializePlugin(plugin: PanoPlugin) {
        calculatePluginUiHash(plugin, plugin.javaClass.classLoader)
    }

    internal fun unRegisterPlugin(plugin: PanoPlugin) {
        pluginUiRegisterList.remove(plugin)
        uiClassLoaders.remove(plugin)
        registrationOrder.remove(plugin)
        zipCache.remove(plugin)
        reportedClashes.removeIf { it.pluginId == plugin.pluginId || it.heldBy == plugin.pluginId }
    }

    internal fun calculatePluginUiHash(
        plugin: PanoPlugin,
        classLoader: ClassLoader,
        isDevelopment: Boolean = devMode(),
        hasUiSourceDir: (String) -> Boolean = { uiSourceDir(it) != null }
    ) {
        val pluginUiZipFile = classLoader.getOwnResourceStream("plugin-ui.zip")

        if (pluginUiZipFile == null) {
            // The dev hash is only for plugins that really have a UI source dir (a Kotlin-only plugin
            // must not advertise a UI it does not have).
            if (isDevelopment && hasUiSourceDir(plugin.pluginId)) {
                register(plugin, DEV_HASH, classLoader)
            }
            return
        }

        register(plugin, pluginUiZipFile.use { it.hash() }, classLoader)
    }

    private fun register(plugin: PanoPlugin, hash: String, classLoader: ClassLoader) {
        zipCache.remove(plugin)
        uiClassLoaders[plugin] = classLoader
        registrationOrder.putIfAbsent(plugin, if (bootEnded) orderCounter.incrementAndGet() else 0L)
        pluginUiRegisterList[plugin] = hash
    }

    /**
     * One file of the plugin's package (`pano-plugin.json`, files under `contract/`, `controllers/`,
     * `samples/`, `widgets/`), or null for anything else. Source: the `plugin-ui` source folder
     * while [DevMode] is on and the plugin has one (the ETag is the content hash then), else the
     * classpath `plugin-ui.zip` (cached per uiHash; the ETag is the uiHash). Blocking; call it off
     * the event loop.
     */
    internal fun readPackageEntry(plugin: PanoPlugin, entry: String): PackageEntry? {
        val normalized = PluginPackage.normalize(entry) ?: return null

        if (!PluginPackage.isServable(normalized)) return null

        val devDir = if (devMode()) uiSourceDir(plugin.pluginId) else null

        if (devDir != null) {
            return readDevEntry(devDir, normalized)
        }

        val hash = pluginUiRegisterList[plugin] ?: return null
        val bytes = zipEntries(plugin, hash)?.entries?.get(normalized) ?: return null

        return PackageEntry(bytes, hash)
    }

    /** The parsed `pano-plugin.json` of [plugin], null when it has none or it is not a JSON object. */
    internal fun manifest(plugin: PanoPlugin): JsonObject? {
        val devDir = if (devMode()) uiSourceDir(plugin.pluginId) else null

        if (devDir != null) {
            return parseObject(readDevEntry(devDir, PluginPackage.MANIFEST)?.bytes)
        }

        val hash = pluginUiRegisterList[plugin] ?: return null

        return zipEntries(plugin, hash)?.manifest
    }

    private fun readDevEntry(dir: File, entry: String): PackageEntry? {
        val file = File(dir, entry)

        // A symbolic link out of the folder must not become a way to read other files.
        if (!file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()) || !file.isFile) return null

        val bytes = file.readBytes()

        return PackageEntry(bytes, sha256(bytes))
    }

    private fun zipEntries(plugin: PanoPlugin, hash: String): ZipEntries? {
        zipCache[plugin]?.takeIf { it.hash == hash }?.let { return it }

        if (hash == DEV_HASH) return null

        val classLoader = uiClassLoaders[plugin] ?: plugin.javaClass.classLoader
        val stream = classLoader.getOwnResourceStream("plugin-ui.zip") ?: return null
        val entries = linkedMapOf<String, ByteArray>()

        ZipInputStream(stream).use { zip ->
            while (true) {
                val zipEntry = zip.nextEntry ?: break

                if (zipEntry.isDirectory) continue

                val name = PluginPackage.normalize(zipEntry.name) ?: continue

                if (PluginPackage.isServable(name)) {
                    entries[name] = zip.readBytes()
                }
            }
        }

        val loaded = ZipEntries(hash, entries, parseObject(entries[PluginPackage.MANIFEST]))

        zipCache[plugin] = loaded

        return loaded
    }

    private fun parseObject(bytes: ByteArray?): JsonObject? = bytes?.let {
        try {
            JsonObject(String(it, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Hash of a plugin whose UI is served from its source folder (Development Mode). */
        const val DEV_HASH = "dev-build"
    }
}
