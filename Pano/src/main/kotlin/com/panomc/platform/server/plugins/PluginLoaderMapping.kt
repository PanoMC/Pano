package com.panomc.platform.server.plugins

import com.panomc.platform.server.ServerType

/**
 * What a server's software means to each plugin directory.
 *
 * Every source names loaders differently and none of them name them the way Pano does, so this is
 * the one translation table: Modrinth wants loader slugs, Hangar wants a platform enum, CurseForge
 * wants numeric class and loader ids. Keeping it pure and in one place is what makes it testable
 * and what stops three separate provider implementations from each inventing their own idea of
 * what "a Paper server" can run.
 *
 * The loader lists are deliberately generous. A Bukkit-family server loads a plugin built for
 * Spigot or Bukkit just as happily as one built for Paper, and hiding those would hide most of
 * Modrinth from a Paper server.
 */
object PluginLoaderMapping {
    /** Modrinth loader facets that a server of this type can actually load. */
    fun modrinthLoaders(type: ServerType): List<String> = when (type) {
        ServerType.PAPER, ServerType.SPIGOT, ServerType.BUKKIT, ServerType.PURPUR, ServerType.FOLIA ->
            listOf("paper", "spigot", "bukkit", "purpur", "folia")

        ServerType.VELOCITY -> listOf("velocity")
        ServerType.BUNGEECORD, ServerType.WATERFALL -> listOf("bungeecord", "waterfall")
        ServerType.FABRIC -> listOf("fabric")
        ServerType.QUILT -> listOf("quilt", "fabric")
        ServerType.FORGE -> listOf("forge")
        ServerType.NEOFORGE -> listOf("neoforge")

        // Vanilla loads nothing at all; the search is left unfiltered rather than faked, and the
        // install endpoint is what refuses to put a jar where nothing would read it.
        ServerType.VANILLA -> emptyList()
    }

    /**
     * Whether the source should be searched for `project_type:mod` rather than `plugin`.
     *
     * Modrinth files the Bukkit world under "plugin" and everything loader-based under "mod", and
     * a search that asks for the wrong one comes back empty rather than wrong, which is worse.
     */
    fun modrinthProjectType(type: ServerType): String = if (isModLoader(type)) "mod" else "plugin"

    /** The Hangar platform a version has to publish for, or null when Hangar has nothing to offer. */
    fun hangarPlatform(type: ServerType): String? = when (type) {
        ServerType.PAPER, ServerType.SPIGOT, ServerType.BUKKIT, ServerType.PURPUR, ServerType.FOLIA -> "PAPER"
        ServerType.VELOCITY -> "VELOCITY"
        ServerType.BUNGEECORD, ServerType.WATERFALL -> "WATERFALL"
        else -> null
    }

    /** CurseForge class id: 5 is Bukkit Plugins, 6 is Mods. */
    fun curseForgeClassId(type: ServerType): Int = if (isModLoader(type)) CURSEFORGE_CLASS_MODS else CURSEFORGE_CLASS_PLUGINS

    /**
     * CurseForge's `modLoaderType`, or null for "any".
     *
     * Bukkit plugins are not a loader on CurseForge at all — they are their own class — so asking
     * for one there would filter the entire class away.
     */
    fun curseForgeLoaderType(type: ServerType): Int? = when (type) {
        ServerType.FABRIC -> 4
        ServerType.QUILT -> 5
        ServerType.FORGE -> 1
        ServerType.NEOFORGE -> 6
        else -> null
    }

    /**
     * Loader names CurseForge actually tags its files with, for this software.
     *
     * Only the mod loaders: a Bukkit plugin on CurseForge lives in its own class rather than under
     * a loader, and its files carry no loader tag at all, so requiring one would mark every
     * plugin incompatible.
     */
    fun curseForgeLoaderNames(type: ServerType): List<String> = when (type) {
        ServerType.FABRIC -> listOf("fabric")
        ServerType.QUILT -> listOf("quilt", "fabric")
        ServerType.FORGE -> listOf("forge")
        ServerType.NEOFORGE -> listOf("neoforge")
        else -> emptyList()
    }

    /** Where the jar goes inside the server directory. */
    fun targetDir(type: ServerType): String = if (isModLoader(type)) "mods" else "plugins"

    /** Whether this software reads `mods/` and is addressed by loader rather than by plugin API. */
    fun isModLoader(type: ServerType): Boolean =
        type == ServerType.FABRIC || type == ServerType.QUILT ||
            type == ServerType.FORGE || type == ServerType.NEOFORGE

    /**
     * Whether anything can be installed for this software at all.
     *
     * Vanilla has no plugin or mod platform, so the honest answer to "search for a plugin" is that
     * there is nowhere to put one.
     */
    fun supportsPlugins(type: ServerType): Boolean = type != ServerType.VANILLA

    /**
     * The game versions a search should accept for [softwareVersion].
     *
     * Only the exact version, plus its `major.minor` line when the server is on a patch release:
     * a plugin published for 1.21 usually runs on 1.21.4 and every source records it under the
     * version it was built against, so filtering on the patch alone hides most of the catalog.
     * A proxy is left unfiltered because proxy builds are versioned by the proxy, not by Minecraft.
     */
    fun gameVersions(type: ServerType, softwareVersion: String?): List<String> {
        if (type.isProxy) {
            return emptyList()
        }

        val version = softwareVersion?.trim().orEmpty()

        if (version.isEmpty() || !VERSION_PATTERN.matches(version)) {
            return emptyList()
        }

        val parts = version.split(".")

        if (parts.size < 3) {
            return listOf(version)
        }

        return listOf(version, "${parts[0]}.${parts[1]}")
    }

    const val CURSEFORGE_CLASS_PLUGINS = 5
    const val CURSEFORGE_CLASS_MODS = 6

    /** CurseForge's class id for Minecraft modpacks, which only the server-less catalogue asks for. */
    const val CURSEFORGE_CLASS_MODPACKS = 4471

    /** CurseForge's Minecraft game id, which is the only game Pano ever asks about. */
    const val CURSEFORGE_GAME_ID = 432

    private val VERSION_PATTERN = Regex("^\\d+(\\.\\d+){1,2}$")
}
