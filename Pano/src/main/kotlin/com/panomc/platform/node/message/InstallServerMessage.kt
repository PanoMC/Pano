package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage
import com.panomc.platform.server.software.dto.SoftwareBuildSpec

/**
 * Tells a node to create a server directory and install the software into it (`INSTALL_SERVER`).
 *
 * Every URL is resolved by Pano before the message is sent, never by the node: the node must not
 * have to know the shape of Mojang's, PaperMC's, Purpur's or Fabric's APIs, and more importantly
 * the set of hosts it downloads from stays something Pano decides. A node that is handed neither
 * an [InstallServerSpec.downloadUrl] nor an [InstallServerSpec.build] has nothing to fall back on
 * and fails the task.
 */
data class InstallServerMessage(
    val serverUuid: String,
    val taskId: String,
    val spec: InstallServerSpec
) : NodeMessage

/**
 * Everything the node needs for one install.
 *
 * [properties] are `server.properties` entries the node writes after unpacking; [acceptEula] is
 * passed explicitly rather than assumed, so accepting Mojang's EULA stays a recorded decision made
 * by a person in the panel.
 */
data class InstallServerSpec(
    val name: String,
    val software: String,
    val version: String,
    /** Null means the node picks a runtime for [version] out of what its host has installed. */
    val javaMajor: Int?,
    val memoryMb: Int,
    val jvmArgs: List<String>,
    val port: Int,
    val acceptEula: Boolean,
    val properties: Map<String, String>,
    /** Direct download of the server jar, resolved from the software catalog. */
    val downloadUrl: String? = null,
    /** Installer jar for the software that needs one (Fabric, Forge, NeoForge). */
    val installerUrl: String? = null,
    /** MD5 of [downloadUrl]'s artifact, for an upstream that publishes one. */
    val md5: String? = null,
    /**
     * Set instead of [downloadUrl] when the jar has to be compiled on the node.
     *
     * Spigot only: SpigotMC may not redistribute the server jar, so the node runs their BuildTools
     * and installs what comes out. Everything the build needs is decided here, not there.
     */
    val build: SoftwareBuildSpec? = null,
    /**
     * The Pano plugin to put inside this server, or null when it gets none.
     *
     * Null is not a failure: vanilla, Forge and NeoForge have no Pano plugin at all, and a
     * release that could not be reached is reported on the task rather than aborting an install
     * that would otherwise work.
     */
    val panoPlugin: ManagedPluginSpec? = null,
    /**
     * What a reinstall carries over from the old directory (SM-66, §2.4.31). Null on a fresh
     * install, and on a reinstall means what it always meant: the `world*` folders only.
     */
    val keep: ReinstallKeepSpec? = null
)

/** `spec.keep` of a reinstall: the node copies exactly what is true here into the new directory. */
data class ReinstallKeepSpec(
    val worlds: Boolean,
    val plugins: Boolean,
    val configs: Boolean
)

/**
 * The Pano plugin build a managed server is installed with, and what to tell it about Pano.
 *
 * This is what replaces `/pano connect`: the credentials are issued by Pano before the server has
 * ever run, so the plugin finds a configured platform on its first boot and links itself to the
 * row it was created for.
 */
data class ManagedPluginSpec(
    /** Where the node gets the jar. `file:` when a development directory provided it. */
    val jarUrl: String,
    /** Directory inside the server the jar belongs in: `plugins` or, for mod loaders, `mods`. */
    val targetDir: String,
    /**
     * Where that platform's plugin reads `config.conf`, relative to the server directory.
     *
     * Sent rather than derived on the node: which folder a plugin uses is decided by the plugin
     * (Bukkit `plugins/Pano`, Velocity `plugins/pano`, Fabric `config/pano`), and Pano is the side
     * that already maps a [com.panomc.platform.server.ServerType] onto a plugin module.
     */
    val configPath: String,
    val config: ManagedPluginConfig,
    /**
     * Mods the plugin cannot start without, put next to it first: the Fabric build needs Fabric
     * API, and a Fabric server with the Pano mod and no Fabric API crashes on every start. The
     * node skips one that is already there, and leaves the plugin out when one is missing and
     * cannot be downloaded, so the server still starts. A node that predates the field ignores it.
     */
    val dependencies: List<ManagedPluginDependency> = emptyList()
)

/** One mod the Pano plugin depends on, as [ManagedPluginSpec.dependencies] lists it. */
data class ManagedPluginDependency(
    /** The mod id it provides (`fabric-api`), which is how the node recognises one already installed. */
    val modId: String,
    /** Human name for the task message. */
    val name: String,
    /** Where to download it; null when no build for this server's Minecraft version was found. */
    val downloadUrl: String? = null,
    val filename: String? = null,
    val sha512: String? = null,
    val sha1: String? = null
)

/** The `platform` block written into the plugin's `config.conf`. */
data class ManagedPluginConfig(
    val host: String,
    val port: Int,
    val ssl: Boolean,
    /** `ServerAuthenticationTokenType` JWT whose subject is the server row's id. */
    val token: String,
    /** Base64 AES-256 key, the same one stored on the server row. */
    val encryptionKey: String
)
