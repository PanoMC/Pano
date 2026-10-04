package com.panomc.platform.server.plugins

import com.panomc.platform.node.ManagedPluginJarResolver
import com.panomc.platform.node.dto.ScannedPluginData
import com.panomc.platform.server.ServerType

/**
 * The decisions behind "update the Pano plugin on this server", kept pure so each one can be read
 * and tested on its own.
 *
 * Updating the plugin is not one mechanism but two, and which one a server gets is the first thing
 * to settle. A managed server has a node that owns its directory, so the node writes the new jar
 * next to the old one exactly as it installs any other plugin, verifying the checksum and removing
 * the jar it replaces only once the new one is in place. A linked server has nobody on its machine
 * but the plugin itself, so the plugin downloads its own successor and swaps it in when the server
 * stops (or hands it to Bukkit's `plugins/update/` folder, which does the same thing at boot). A
 * managed server whose node is offline but whose plugin is connected can still take the second
 * route, which is why the order below is "node first, then the plugin" rather than "by kind".
 *
 * Neither route applies anything to a running server. A JVM reads its plugin directory once, at
 * boot, so every successful update ends in the same place: a jar on disk that loads on the next
 * restart, which is what the endpoints report as `restartRequired`.
 */
object PanoPluginUpdatePlan {
    /** Which side of a server delivers the new jar. The wire names are what the panel reads. */
    enum class Mode(val wire: String) {
        /** The node writes the jar into the server's directory (`INSTALL_PLUGIN`). */
        NODE("node"),

        /** The plugin downloads, verifies and stages its own successor (`PANO_PLUGIN_UPDATE`). */
        PLUGIN("plugin")
    }

    /** The software runs no Pano plugin at all: vanilla, Forge, NeoForge. */
    const val REASON_NO_PLUGIN_MODULE = "NO_PLUGIN_MODULE"

    /** A managed server whose node is away and whose plugin cannot stand in for it. */
    const val REASON_NODE_OFFLINE = "NODE_OFFLINE"

    /** A linked server whose plugin is not connected: nothing on that machine can be told. */
    const val REASON_SERVER_OFFLINE = "SERVER_OFFLINE"

    /**
     * The plugin is connected but does not announce `self-update`: it predates the feature, or it
     * could not work out which jar it was loaded from and so has nothing it could replace.
     */
    const val REASON_PLUGIN_TOO_OLD = "PLUGIN_TOO_OLD"

    /** The installed and the newest version are both known, and they are the same. */
    const val REASON_UP_TO_DATE = "UP_TO_DATE"

    /** Neither a development jar nor the newest release could be fetched right now. */
    const val REASON_JAR_UNAVAILABLE = "JAR_UNAVAILABLE"

    /** The node did not say what is in the server's plugin directory, so nothing safe can be sent. */
    const val REASON_SCAN_FAILED = "SCAN_FAILED"

    /** Pano could not hand the push over; the socket went away between the check and the send. */
    const val REASON_SEND_FAILED = "SEND_FAILED"

    /** The per-user, per-server install limit was reached during an "update all". */
    const val REASON_RATE_LIMITED = "RATE_LIMITED"

    /** The user may not manage plugins on that server; an "update all" skips it rather than failing. */
    const val REASON_NO_PERMISSION = "NO_PERMISSION"

    /** The name every platform's descriptor gives the plugin: plugin.yml, bungee.yml, velocity and fabric. */
    const val PANO_PLUGIN_NAME = "Pano"

    /**
     * Which route an update of this server takes, or null when there is none right now.
     *
     * Node first for a managed server, because the node is the side that can write the directory
     * while the server is stopped as well as while it runs; the plugin only when the node cannot be
     * reached and the plugin says it can replace itself. A linked server has only the plugin.
     */
    fun modeFor(
        managed: Boolean,
        nodeConnected: Boolean,
        pluginConnected: Boolean,
        pluginCanSelfUpdate: Boolean
    ): Mode? = when {
        managed && nodeConnected -> Mode.NODE
        pluginConnected && pluginCanSelfUpdate -> Mode.PLUGIN
        else -> null
    }

    /**
     * Why [modeFor] said no, in the words the panel shows.
     *
     * Only meaningful when [modeFor] returned null for the same inputs; asked anyway, it still
     * answers the most useful thing it can rather than throwing.
     */
    fun refusalFor(
        type: ServerType,
        managed: Boolean,
        pluginConnected: Boolean
    ): String = when {
        ManagedPluginJarResolver.platformOf(type) == null -> REASON_NO_PLUGIN_MODULE
        pluginConnected -> REASON_PLUGIN_TOO_OLD
        managed -> REASON_NODE_OFFLINE
        else -> REASON_SERVER_OFFLINE
    }

    /**
     * Whether a refusal for [reason] is one the admin can get past by putting the jar in place
     * themselves.
     *
     * A linked server's plugin that cannot replace itself, or is not connected to be asked, leaves
     * only the admin with access to its `plugins/` folder. A managed server is not offered this: its
     * node does the job as soon as it is back, and a hand-placed jar would race the node's own
     * install. Software without a Pano plugin has nothing to download.
     */
    fun canUpdateByHand(reason: String, managed: Boolean): Boolean =
        !managed && (reason == REASON_PLUGIN_TOO_OLD || reason == REASON_SERVER_OFFLINE)

    /**
     * Whether a server running [installed] should be offered [latest].
     *
     * Only a definite yes counts. [PanoPluginStatus.updateAvailable] answers null for a development
     * jar, a server that never reported a version and a release that has not been looked up yet,
     * and "update all" must never start work on a maybe.
     */
    fun needsUpdate(installed: String?, latest: String?): Boolean =
        PanoPluginStatus.updateAvailable(installed, latest) == true

    /** One server as "update all" sees it. */
    data class Candidate(val serverId: Long, val type: ServerType, val installedVersion: String?)

    /**
     * The servers "update all" should start an update on.
     *
     * [latestFor] is asked per software rather than once, because the Bukkit family, the two proxies
     * and Fabric each run their own module and a release may carry some of them and not others.
     * Software with no Pano plugin at all is never a candidate, whatever its row says.
     */
    fun serversNeedingUpdate(candidates: List<Candidate>, latestFor: (ServerType) -> String?): List<Candidate> =
        candidates.filter { candidate ->
            ManagedPluginJarResolver.platformOf(candidate.type) != null &&
                needsUpdate(candidate.installedVersion, latestFor(candidate.type))
        }

    /**
     * The Pano jar already sitting in a managed server's plugin directory, from the node's scan.
     *
     * This is the file the new jar supersedes, and it matters: release assets carry their version
     * in the name (`pano-spigot-1.0.0-alpha.62.jar`), so an update that did not remove the old one
     * would leave two copies of the same plugin — which Bukkit resolves by picking one at random,
     * Velocity by refusing the second and Fabric by refusing to start at all.
     *
     * The descriptor's name decides first, because it is what the platform itself keys plugins by.
     * The file name is only the fallback for a jar whose descriptor could not be read, and then only
     * the exact release asset pattern for this [platform] or the plain `pano.jar` a hand install
     * uses — never any `pano-*` jar, which would also match unrelated jars such as
     * `pano-limbo-auth`. A jar switched off with `.disabled` is not loaded and is left alone.
     */
    fun panoJarIn(scanned: List<ScannedPluginData>, platform: String): String? {
        val loadable = scanned.filter { it.enabled && it.file.lowercase().endsWith(JAR_SUFFIX) }

        loadable.firstOrNull { it.name.equals(PANO_PLUGIN_NAME, ignoreCase = true) }?.let { return it.file }

        return loadable.firstOrNull { entry ->
            entry.file.equals(HAND_INSTALLED_NAME, ignoreCase = true) ||
                ManagedPluginJarResolver.matchesAsset(entry.file, platform)
        }?.file
    }

    private const val JAR_SUFFIX = ".jar"

    private const val HAND_INSTALLED_NAME = "pano.jar"
}
