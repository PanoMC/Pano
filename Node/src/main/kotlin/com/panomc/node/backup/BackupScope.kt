package com.panomc.node.backup

import com.panomc.node.files.BackupExcludeMatcher
import com.panomc.node.files.ServerFileDenylist
import com.panomc.node.server.ServerProperties
import com.panomc.node.util.PathSafety
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** How a backup is stored: one self-contained archive, or a deduplicated snapshot. */
enum class BackupMode {
    FULL,
    SNAPSHOT;

    companion object {
        /** Absent means [FULL], which is what every Pano before backups v2 meant; anything else unknown is refused. */
        fun parse(value: String?): BackupMode? {
            if (value.isNullOrBlank()) {
                return FULL
            }

            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}

/** What part of the server directory a backup takes. */
enum class BackupScopeKind {
    ALL,
    WORLDS,
    CUSTOM;

    companion object {
        /** Absent means [ALL]; anything else unknown is refused. */
        fun parse(value: String?): BackupScopeKind? {
            if (value.isNullOrBlank()) {
                return ALL
            }

            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}

/** A scope that cannot be taken, carrying one of the wire error codes as its message. */
class BackupScopeException(val code: String) : RuntimeException(code)

/**
 * The top-level paths a backup starts from, once a scope has been turned into real directories.
 *
 * [roots] are server-relative, `/`-separated, sorted and never nested inside one another: a CUSTOM
 * include of both `world` and `world/region` is simply `world`, because an archive that walked
 * both would hold every region file twice — and a zip refuses the second copy outright.
 * [wholeDirectory] is true for ALL, where the archive is taken from the server directory itself
 * rather than from a list, which is exactly what a FULL backup did before scopes existed.
 */
data class ResolvedScope(
    val kind: BackupScopeKind,
    val roots: List<String>,
    val wholeDirectory: Boolean
)

/**
 * Turns ALL / WORLDS / CUSTOM into the directories a backup actually reads, for both modes.
 *
 * One resolver for FULL and SNAPSHOT on purpose: an operator who switches a schedule from one mode
 * to the other must get a backup of the same files, and two copies of "what is a world" would
 * drift the first time one of them learned about a new server layout.
 *
 * Worlds are found the way a server finds them rather than by name: the `level-name` in
 * `server.properties` (a vanilla or Paper server can call its world anything), the `_nether` and
 * `_the_end` directories Bukkit splits a world into next to it, and every other top-level
 * directory that holds a `level.dat` — which is what a Multiverse world created in-game looks
 * like, and what no naming rule would ever catch.
 */
object BackupScope {
    const val ERROR_NO_WORLDS = "NO_WORLDS"
    const val ERROR_INVALID_SCOPE = "INVALID_SCOPE"

    const val LEVEL_DAT = "level.dat"
    const val DEFAULT_LEVEL_NAME = "world"

    private const val PROPERTIES_FILE = "server.properties"
    private const val LEVEL_NAME_KEY = "level-name"
    private val DIMENSION_SUFFIXES = listOf("_nether", "_the_end")

    /**
     * The roots [kind] selects in [serverDirectory].
     *
     * Throws [BackupScopeException] with [ERROR_NO_WORLDS] when WORLDS finds none, and with
     * [ERROR_INVALID_SCOPE] for a CUSTOM without an include list or whose includes select nothing
     * that exists: a scheduled backup of a folder that was renamed away should fail loudly, not
     * quietly succeed as an empty snapshot that looks like a safety net.
     */
    fun resolve(
        serverDirectory: File,
        kind: BackupScopeKind,
        include: List<String>?,
        exclude: BackupExcludeMatcher
    ): ResolvedScope = when (kind) {
        BackupScopeKind.ALL -> ResolvedScope(kind, topLevel(serverDirectory), wholeDirectory = true)

        BackupScopeKind.WORLDS -> {
            val worlds = worldDirectories(serverDirectory)
                .filterNot { ServerFileDenylist.isDenied(it) || exclude.matches(it) }

            if (worlds.isEmpty()) {
                throw BackupScopeException(ERROR_NO_WORLDS)
            }

            ResolvedScope(kind, collapse(worlds), wholeDirectory = false)
        }

        BackupScopeKind.CUSTOM -> {
            val roots = customRoots(serverDirectory, include)

            if (roots.isEmpty()) {
                throw BackupScopeException(ERROR_INVALID_SCOPE)
            }

            ResolvedScope(kind, collapse(roots), wholeDirectory = false)
        }
    }

    /**
     * Every world directory in [serverDirectory], sorted: the level, its dimensions, and every other
     * top-level directory with a `level.dat` in it.
     *
     * Only directories that exist are returned. The level itself counts even before its first
     * `level.dat` is written, because a server that was started once has the folder and is about to
     * have the file.
     */
    fun worldDirectories(serverDirectory: File): List<String> {
        val result = sortedSetOf<String>()

        val level = levelName(serverDirectory)

        if (level != null) {
            (listOf(level) + DIMENSION_SUFFIXES.map { "$level$it" }).forEach { candidate ->
                if (isRealDirectory(serverDirectory, candidate)) {
                    result.add(candidate)
                }
            }
        }

        serverDirectory.listFiles()?.forEach { child ->
            val path = child.toPath()

            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return@forEach
            }

            if (Files.isRegularFile(path.resolve(LEVEL_DAT), LinkOption.NOFOLLOW_LINKS)) {
                result.add(child.name)
            }
        }

        return result.filterNot { ServerFileDenylist.isDenied(it) }
    }

    /**
     * The world directories inside a backup, from the list of files it holds.
     *
     * A world, for the purposes of a restore, is a top-level directory with a `level.dat` directly
     * in it — that is the one test that works on a list of paths without a `server.properties` to
     * read, and it is the same test on both backup kinds.
     */
    fun worldsIn(filePaths: Sequence<String>): Set<String> = filePaths
        .map { ServerFileDenylist.normalise(it) }
        .mapNotNull { path ->
            val parts = path.split('/')

            parts.takeIf { it.size == 2 && it[1] == LEVEL_DAT }?.get(0)
        }
        .filterNot { ServerFileDenylist.isDenied(it) }
        .toSortedSet()

    /**
     * `level-name` from `server.properties`, or `world` when there is no file or no key.
     *
     * A level name is a path to a server, and a hostile one could name a directory outside it, so
     * anything that is not a plain relative path is dropped rather than followed.
     */
    fun levelName(serverDirectory: File): String? {
        val configured = ServerProperties.read(File(serverDirectory, PROPERTIES_FILE))[LEVEL_NAME_KEY]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_LEVEL_NAME

        val normalised = ServerFileDenylist.normalise(configured)

        if (normalised.isEmpty() || normalised.split('/').any { !PathSafety.isSafeSegment(it) }) {
            return null
        }

        return normalised
    }

    /**
     * Drops every root that sits inside another one, and sorts what is left.
     *
     * `world` and `world/region` both selected is one walk of `world`; `world` and `world_nether`
     * are two, which is why the test is on a whole segment rather than a string prefix.
     */
    fun collapse(roots: Collection<String>): List<String> {
        val sorted = roots.map { ServerFileDenylist.normalise(it) }.filter { it.isNotEmpty() }.distinct().sorted()

        val result = mutableListOf<String>()

        sorted.forEach { root ->
            if (result.none { root == it || root.startsWith("$it/") }) {
                result.add(root)
            }
        }

        return result
    }

    private fun customRoots(serverDirectory: File, include: List<String>?): List<String> {
        val entries = include.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }

        if (entries.isEmpty()) {
            throw BackupScopeException(ERROR_INVALID_SCOPE)
        }

        val roots = mutableListOf<String>()

        entries.forEach { entry ->
            val normalised = ServerFileDenylist.normalise(entry)

            if (normalised.isEmpty()) {
                // "/" or "." — the whole server, which is what ALL is for; taken as asked anyway.
                roots.addAll(topLevel(serverDirectory))

                return@forEach
            }

            if (normalised.contains('*')) {
                // A glob selects matching top-level entries, the only place it is unambiguous.
                val regex = BackupExcludeMatcher.globToRegex(normalised)

                serverDirectory.listFiles()?.forEach { child ->
                    if (regex.matches(child.name) && !Files.isSymbolicLink(child.toPath()) &&
                        !ServerFileDenylist.isDenied(child.name)
                    ) {
                        roots.add(child.name)
                    }
                }

                return@forEach
            }

            if (normalised.split('/').any { !PathSafety.isSafeSegment(it) }) {
                throw BackupScopeException(ERROR_INVALID_SCOPE)
            }

            if (ServerFileDenylist.isDenied(normalised)) {
                return@forEach
            }

            val file = try {
                PathSafety.resolveRelative(serverDirectory, normalised)
            } catch (_: Exception) {
                throw BackupScopeException(ERROR_INVALID_SCOPE)
            }

            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) {
                roots.add(normalised)
            }
        }

        return roots
    }

    /**
     * The top-level entries of the server directory a backup of everything would walk.
     *
     * Excludes are not applied here but path by path during the walk, exactly as a FULL archive of
     * the whole directory applies them — an exact-path exclude of a directory leaves the directory
     * entry out, not everything under it, and both modes have to agree on that.
     */
    private fun topLevel(serverDirectory: File): List<String> =
        serverDirectory.listFiles()
            ?.filterNot { Files.isSymbolicLink(it.toPath()) }
            ?.map { it.name }
            ?.filterNot { ServerFileDenylist.isDenied(it) }
            ?.sorted()
            .orEmpty()

    private fun isRealDirectory(serverDirectory: File, relative: String): Boolean {
        val file = try {
            PathSafety.resolveRelative(serverDirectory, relative)
        } catch (_: Exception) {
            return false
        }

        return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
}
