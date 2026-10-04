package com.panomc.platform.server.backup

import java.io.StringReader
import java.util.Properties

/**
 * Pano's guess at what a `WORLDS` backup would take, for the panel to show before anybody commits
 * to one.
 *
 * Only a preview: the node or plugin decides for real when the backup runs, with the same rule —
 * `level-name` from `server.properties` (default `world`), its `_nether` and `_the_end` when they
 * exist, and every other top-level directory holding a `level.dat` (Bukkit multiworld). Kept pure
 * so the rule is testable without a socket; the endpoint does the listing.
 */
object BackupWorlds {
    const val DEFAULT_LEVEL_NAME = "world"

    /**
     * Top-level directories that are never worlds, skipped so the preview does not spend a round
     * trip per plugin folder asking whether it holds a `level.dat`.
     */
    val NEVER_WORLDS = setOf(
        "plugins", "mods", "config", "libraries", "logs", "cache", "crash-reports", "versions",
        "backups", "bundler", "debug", "defaultconfigs", "kubejs", ".pano-node", ".fabric",
        ".paper-remapped", "journeymap", "resourcepacks", "datapacks"
    )

    /** Most directories the preview will probe for a `level.dat`. */
    const val MAX_PROBES = 64

    /** `level-name` out of `server.properties`, or the default when the file or key is missing. */
    fun levelNameOf(serverProperties: String?): String {
        if (serverProperties.isNullOrBlank()) {
            return DEFAULT_LEVEL_NAME
        }

        val properties = Properties()

        return try {
            properties.load(StringReader(serverProperties))

            properties.getProperty("level-name")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LEVEL_NAME
        } catch (_: Exception) {
            DEFAULT_LEVEL_NAME
        }
    }

    /** The directories worth asking about: every top-level one that is not the level's own or known not to be a world. */
    fun probeCandidates(levelName: String, directories: List<String>): List<String> {
        val own = ownWorlds(levelName)

        return directories
            .filter { it !in own && it.lowercase() !in NEVER_WORLDS && !it.startsWith(".") }
            .sorted()
            .take(MAX_PROBES)
    }

    /**
     * The worlds a `WORLDS` backup would take: the level's own three that exist, first and in that
     * order, then the other directories found to hold a `level.dat`, alphabetically.
     */
    fun select(levelName: String, directories: List<String>, withLevelDat: Collection<String>): List<String> {
        val present = directories.toSet()

        val own = ownWorlds(levelName).filter { it in present }

        val others = withLevelDat.filter { it in present && it !in own }.distinct().sorted()

        return own + others
    }

    private fun ownWorlds(levelName: String) = listOf(levelName, "${levelName}_nether", "${levelName}_the_end")
}
