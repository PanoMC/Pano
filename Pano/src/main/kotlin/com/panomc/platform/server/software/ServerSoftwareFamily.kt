package com.panomc.platform.server.software

import com.panomc.platform.server.ServerType

/**
 * Which softwares can hand their files to one another when a server changes software (SM-66,
 * §2.4.31).
 *
 * A family is a set of softwares that read the same plugin or mod directory and the same config
 * files: a Paper plugin runs on Purpur, a Fabric mod does not run on Paper, and no plugin runs on
 * Velocity that was written for a game server. Backend families run a world; proxy families run
 * none.
 *
 * The node holds its own copy of this table in `com.panomc.node.task.SoftwareFamily` (it decides
 * which directories a reinstall carries over and cannot share code with the platform, like
 * `MinecraftJavaVersions`). Change one and change the other.
 */
enum class ServerSoftwareFamily(
    val id: String,
    val isProxy: Boolean,
    /**
     * Where this family keeps what the operator added, relative to the server directory. Empty for
     * vanilla, which has nothing to carry.
     */
    val pluginPaths: List<String>
) {
    BUKKIT("bukkit", false, listOf("plugins")),
    FABRIC("fabric", false, listOf("mods", "config")),
    FORGE("forge", false, listOf("mods", "config")),
    VANILLA("vanilla", false, emptyList()),
    VELOCITY("velocity", true, listOf("plugins")),
    BUNGEE("bungee", true, listOf("plugins"));

    /** `backend` or `proxy`, as the reinstall preview spells it. */
    val kind: String get() = if (isProxy) KIND_PROXY else KIND_BACKEND

    /** Whether this family has a plugin or mod directory at all. */
    val hasPlugins: Boolean get() = pluginPaths.isNotEmpty()

    /**
     * Whether this family's game server reads the vanilla files (`server.properties`, the
     * whitelist, ops and bans), which is what lets Paper, Fabric and vanilla swap configs.
     */
    val sharesVanillaConfig: Boolean get() = this == BUKKIT || this == FABRIC || this == VANILLA

    companion object {
        const val KIND_BACKEND = "backend"
        const val KIND_PROXY = "proxy"

        /** Software id (as the catalog and the server row spell it) to family. */
        private val BY_SOFTWARE = mapOf(
            "paper" to BUKKIT,
            "purpur" to BUKKIT,
            "folia" to BUKKIT,
            "spigot" to BUKKIT,
            "craftbukkit" to BUKKIT,
            "bukkit" to BUKKIT,
            "fabric" to FABRIC,
            "quilt" to FABRIC,
            "forge" to FORGE,
            "neoforge" to FORGE,
            "vanilla" to VANILLA,
            "velocity" to VELOCITY,
            "bungeecord" to BUNGEE,
            "waterfall" to BUNGEE
        )

        /** The family of software [id], or null for one Pano does not know. */
        fun ofSoftware(id: String?): ServerSoftwareFamily? = id?.lowercase()?.let { BY_SOFTWARE[it] }

        /** The family a [ServerType] belongs to; every type has one. */
        fun ofType(type: ServerType): ServerSoftwareFamily = when {
            type.isBukkitFamily -> BUKKIT
            type == ServerType.FABRIC || type == ServerType.QUILT -> FABRIC
            type == ServerType.FORGE || type == ServerType.NEOFORGE -> FORGE
            type == ServerType.VELOCITY -> VELOCITY
            type == ServerType.BUNGEECORD || type == ServerType.WATERFALL -> BUNGEE
            else -> VANILLA
        }

        /**
         * The family of a server row: its software id when it has one, else its type. An imported
         * server may have no software id at all but always has a type.
         */
        fun of(software: String?, type: ServerType): ServerSoftwareFamily = ofSoftware(software) ?: ofType(type)
    }
}
