package com.panomc.node.task

import com.panomc.node.net.ReinstallKeep
import com.panomc.node.util.NodeLogger
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Which softwares can hand their files to one another (SM-66, §2.4.31).
 *
 * Pano holds the same table in `com.panomc.platform.server.software.ServerSoftwareFamily` and
 * decides from it what a reinstall may keep; this copy decides which directories that means on
 * disk. The daemon ships as its own jar against Pano versions it was not built with, so the two
 * cannot share code — change one and change the other.
 */
enum class SoftwareFamily(
    val isProxy: Boolean,
    /** What the operator added, relative to the server directory. Empty for vanilla. */
    val pluginPaths: List<String>
) {
    BUKKIT(false, listOf("plugins")),
    FABRIC(false, listOf("mods", "config")),
    FORGE(false, listOf("mods", "config")),
    VANILLA(false, emptyList()),
    VELOCITY(true, listOf("plugins")),
    BUNGEE(true, listOf("plugins"));

    companion object {
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

        /** The family of software [id], or null for one this node does not know. */
        fun of(id: String?): SoftwareFamily? = id?.lowercase()?.let { BY_SOFTWARE[it] }
    }
}

/**
 * Moves what a reinstall keeps from the old server directory into the freshly installed one, and
 * swaps the two with a way back (SM-66, §2.4.31).
 *
 * Two kinds of carrying, on purpose. Worlds are *moved*: they are the big part (gigabytes), the
 * move is a rename inside one servers root and therefore instant, and a failed swap moves them
 * straight back. Plugins, mods and config files are *copied* before the swap, because the fresh
 * install writes into the same places (the Pano plugin's jar and config, `server.properties`'s
 * port) and the old copy must stay exactly as it was in case the change is undone.
 *
 * The old directory survives as `<uuid>.old-<timestamp>` until the new install is registered, so
 * a failure at any point leaves the server as it was: [Swap.rollback] renames it back.
 */
object ReinstallCarryOver {
    /** Suffix of the directory a reinstall installs into before the swap. */
    const val INSTALLING_SUFFIX = ".installing"

    /** Infix of the old directory kept until the new one is DONE. */
    const val OLD_INFIX = ".old-"

    private const val WORLD_PREFIX = "world"
    private const val LEVEL_DAT = "level.dat"

    /**
     * The vanilla files every game server reads: carried between Bukkit, Fabric and vanilla too,
     * where nothing else would make sense on the other side.
     */
    val VANILLA_CONFIG_FILES = listOf(
        "server.properties",
        "whitelist.json",
        "ops.json",
        "banned-players.json",
        "banned-ips.json",
        "usercache.json"
    )

    /**
     * Every config file a server of the same family keeps. Only the ones that exist are copied;
     * `config/paper-*.yml` is matched by [isPaperConfig]. `forwarding.secret` belongs to
     * `velocity.toml`: without it the proxy makes a new secret and every backend refuses it.
     */
    val FAMILY_CONFIG_FILES = VANILLA_CONFIG_FILES + listOf(
        "banned-players.txt",
        "bukkit.yml",
        "spigot.yml",
        "purpur.yml",
        "velocity.toml",
        "forwarding.secret",
        "config.yml",
        "eula.txt"
    )

    /** The Pano plugin's own jar, which the fresh install brings in the right build for itself. */
    private val PANO_JAR = Regex("^pano(-(spigot|bungeecord|velocity|fabric)-.*)?\\.jar$", RegexOption.IGNORE_CASE)

    /** Whether [name] is a Pano plugin jar the reinstall must not carry over. */
    fun isPanoPluginJar(name: String): Boolean = PANO_JAR.matches(name)

    private fun isPaperConfig(relative: String): Boolean {
        val name = relative.substringAfter("config/", "")

        return relative.startsWith("config/") && name.startsWith("paper-") && name.endsWith(".yml") && !name.contains('/')
    }

    /**
     * The worlds in [directory]: every top-level directory holding a `level.dat` (so a custom
     * `level-name` survives) and everything called `world*`. With [legacy] only the latter, which is
     * what a reinstall without a keep list has always carried.
     */
    fun worlds(directory: File, legacy: Boolean = false): List<File> = directory.listFiles()
        ?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        ?.filter { it.name.startsWith(WORLD_PREFIX) || (!legacy && File(it, LEVEL_DAT).isFile) }
        ?.sortedBy { it.name }
        .orEmpty()

    /**
     * The config files to copy, relative to the server directory: the whole family list when the
     * family is unchanged (or unknown on either side, where Pano already decided it was allowed),
     * the vanilla files otherwise. Only files that exist.
     */
    fun configFiles(directory: File, from: SoftwareFamily?, to: SoftwareFamily?): List<String> {
        val sameFamily = from == null || to == null || from == to

        val names = if (sameFamily) FAMILY_CONFIG_FILES else VANILLA_CONFIG_FILES

        val paper = if (sameFamily) {
            File(directory, "config").listFiles()
                ?.filter { it.isFile }
                ?.map { "config/${it.name}" }
                ?.filter { isPaperConfig(it) }
                .orEmpty()
        } else {
            emptyList()
        }

        return (names.filter { File(directory, it).isFile } + paper).sorted()
    }

    /**
     * The plugin or mod directories to copy, relative to the server directory: [from]'s own, or —
     * for a software this node cannot place — `plugins` and `mods`, whichever exists.
     */
    fun pluginPaths(directory: File, from: SoftwareFamily?): List<String> {
        val candidates = from?.pluginPaths ?: listOf("plugins", "mods")

        return candidates.filter { File(directory, it).isDirectory }
    }

    /**
     * Copies the plugins and config files [keep] asks for from [oldDirectory] into [workDirectory].
     *
     * Never overwrites what the fresh install already put there, and never carries a Pano plugin
     * jar: the new install fetched the build for the new software, and the old one — maybe the
     * Spigot module on what is now Velocity — would only fail to load or load twice. Returns the
     * relative paths it copied, for the console.
     */
    fun copyKept(
        oldDirectory: File,
        workDirectory: File,
        keep: ReinstallKeep,
        oldSoftware: String?,
        newSoftware: String?
    ): List<String> {
        if (!oldDirectory.isDirectory) {
            return emptyList()
        }

        val from = SoftwareFamily.of(oldSoftware)
        val to = SoftwareFamily.of(newSoftware)

        val copied = mutableListOf<String>()

        if (keep.plugins == true) {
            pluginPaths(oldDirectory, from).forEach { relative ->
                copyTree(File(oldDirectory, relative), File(workDirectory, relative), skipTopLevel = ::isPanoPluginJar)

                copied += "$relative/"
            }
        }

        if (keep.configs == true) {
            configFiles(oldDirectory, from, to).forEach { relative ->
                val target = File(workDirectory, relative)

                if (!target.exists()) {
                    target.parentFile?.mkdirs()

                    Files.copy(File(oldDirectory, relative).toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)

                    copied += relative
                }
            }
        }

        return copied
    }

    /**
     * Copies [source] into [target], keeping what is already there and skipping the top-level
     * files [skipTopLevel] names. Symlinks are copied as links, never followed: a link out of the
     * server directory must not turn into a copy of whatever it points at.
     */
    private fun copyTree(source: File, target: File, skipTopLevel: (String) -> Boolean) {
        val sourcePath = source.toPath()
        val targetPath = target.toPath()

        Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(targetPath.resolve(sourcePath.relativize(dir).toString()))

                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = sourcePath.relativize(file)

                if (relative.nameCount == 1 && skipTopLevel(relative.toString())) {
                    return FileVisitResult.CONTINUE
                }

                val destination = targetPath.resolve(relative.toString())

                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    return FileVisitResult.CONTINUE
                }

                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)

                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
    }

    /**
     * Puts [workDirectory] in [finalDirectory]'s place, moving the worlds [keep] asks for across.
     *
     * [keep] null is a reinstall from a Pano that sends no keep list: the `world*` folders, as
     * before. Throws after undoing itself when a step fails, so the caller only has to report it.
     */
    fun swap(finalDirectory: File, workDirectory: File, keep: ReinstallKeep?, now: Long = System.currentTimeMillis()): Swap {
        val swap = Swap(finalDirectory, workDirectory, File(finalDirectory.parentFile, "${finalDirectory.name}$OLD_INFIX$now"))

        try {
            swap.perform(keep)
        } catch (exception: Exception) {
            swap.rollback()

            throw exception
        }

        return swap
    }

    /**
     * One swap in progress: which worlds moved, where the old directory went, and the way back.
     */
    class Swap internal constructor(
        val finalDirectory: File,
        val workDirectory: File,
        val oldDirectory: File
    ) {
        private val movedWorlds = mutableListOf<String>()
        private var oldMoved = false
        private var workMoved = false

        /** The worlds that were moved into the new directory, by name. */
        val worlds: List<String> get() = movedWorlds.toList()

        internal fun perform(keep: ReinstallKeep?) {
            if (finalDirectory.exists()) {
                move(finalDirectory, oldDirectory)

                oldMoved = true
            }

            val carryWorlds = keep == null || keep.worlds != false

            if (oldMoved && carryWorlds) {
                worlds(oldDirectory, legacy = keep == null).forEach { world ->
                    val target = File(workDirectory, world.name)

                    // A fresh install never has a world yet; if it somehow does, the old one wins,
                    // because it is the one somebody played on.
                    if (target.exists()) {
                        target.deleteRecursively()
                    }

                    move(world, target)

                    movedWorlds += world.name
                }
            }

            move(workDirectory, finalDirectory)

            workMoved = true
        }

        /**
         * Undoes whatever [perform] did, in reverse: the new directory back to its working name,
         * the worlds back into the old directory, the old directory back in place. Best effort —
         * each step is tried even when an earlier one failed, so as much as possible ends up where
         * it was.
         */
        fun rollback() {
            if (workMoved) {
                attempt { move(finalDirectory, workDirectory) }

                workMoved = false
            }

            movedWorlds.reversed().forEach { name ->
                attempt { move(File(workDirectory, name), File(oldDirectory, name)) }
            }

            movedWorlds.clear()

            if (oldMoved) {
                attempt {
                    if (finalDirectory.exists()) {
                        finalDirectory.deleteRecursively()
                    }

                    move(oldDirectory, finalDirectory)
                }

                oldMoved = false
            }

            workDirectory.deleteRecursively()
        }

        /** The new install is registered and reported: the old directory can go. */
        fun commit(logger: NodeLogger? = null) {
            if (oldDirectory.exists() && !oldDirectory.deleteRecursively()) {
                // Left for the next boot's sweep rather than failing a finished install over it.
                logger?.warn("Could not delete ${oldDirectory.absolutePath}; it is removed on the next start.")
            }
        }

        private inline fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }

    /** Renames [source] to [target], atomically where the filesystem can. */
    private fun move(source: File, target: File) {
        if (source.renameTo(target)) {
            return
        }

        Files.move(source.toPath(), target.toPath())
    }

    /** Whether [name] is one of a reinstall's temporary directories, never a server of its own. */
    fun isTemporary(name: String): Boolean = name.endsWith(INSTALLING_SUFFIX) || name.contains(OLD_INFIX)

    /**
     * Puts back what a daemon that died in the middle of a reinstall left behind, before the
     * servers are loaded.
     *
     * - `<uuid>` gone and `<uuid>.old-*` there: the swap was interrupted. The worlds already moved
     *   into `<uuid>.installing` go back, and the old directory takes its place again — the change
     *   did not happen, which is what Pano's failed task says too.
     * - both there: the swap finished and only the clean-up did not, so the old one goes.
     * - `<uuid>.installing` left over: an install that never swapped; it is deleted.
     */
    fun recover(serversRoot: File, logger: NodeLogger) {
        val entries = serversRoot.listFiles()?.filter { it.isDirectory } ?: return

        entries.filter { it.name.contains(OLD_INFIX) }.sortedByDescending { it.name }.forEach { old ->
            val uuid = old.name.substringBefore(OLD_INFIX)
            val final = File(serversRoot, uuid)
            val working = File(serversRoot, "$uuid$INSTALLING_SUFFIX")

            try {
                if (File(final, SERVER_JSON).isFile) {
                    old.deleteRecursively()

                    logger.info("Removed the old directory of reinstalled server $uuid.")
                } else {
                    if (working.isDirectory) {
                        worlds(working).filter { !File(old, it.name).exists() }.forEach { world ->
                            move(world, File(old, world.name))
                        }
                    }

                    if (final.exists()) {
                        final.deleteRecursively()
                    }

                    move(old, final)

                    logger.warn("Restored server $uuid from an interrupted reinstall.")
                }
            } catch (exception: Exception) {
                logger.warn("Could not recover ${old.name} after an interrupted reinstall: ${exception.message}")
            }
        }

        serversRoot.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(INSTALLING_SUFFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private const val SERVER_JSON = "server.json"
}
