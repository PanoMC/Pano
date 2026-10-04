package com.panomc.node.task

import com.panomc.node.agent.AgentLayout
import com.panomc.node.host.HostPlatform
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decides whether a directory on this host may be adopted where it is (`IMPORT_SERVER` mode
 * `IN_PLACE`), and refuses with a reason the panel can name when it may not.
 *
 * Adoption is the one import that does not copy, and everything that makes a copy safe is gone
 * with it: the node will write its `server.json` and `.pano-node/` into a directory somebody else
 * owns, the panel's file manager will open onto it, and a start will launch whatever jar it holds
 * from there. So the path is checked for what it *is* before anything is written into it:
 *
 * - `PATH_NOT_ABSOLUTE`, `PATH_NOT_FOUND`, `PATH_NOT_A_DIRECTORY`: the path itself;
 * - `PATH_NOT_ALLOWED`: the node's own data directory (or anything in it, or anything that holds
 *   it — the file manager would then reach the node's token), the filesystem root, the system
 *   directories and everything under them, the node user's home directory itself, and a directory
 *   this user cannot both read and write. The one data directory a server folder may hold is a
 *   Pano Agent's own `.pano-agent` (SM-74), which the file manager and backups never reach;
 * - `ALREADY_MANAGED`: a directory this node already supervises, one inside such a directory or
 *   holding one, and a directory that carries another Pano node's `server.json`;
 * - `SERVER_RUNNING`: a server that is running right now from that directory. Two daemons, or a
 *   daemon and a `screen` session, supervising one world is how worlds get corrupted, so the old
 *   launcher has to be stopped first. On Linux any process whose working directory is the
 *   directory (or inside it) counts -- except this daemon's own ancestors, and for a Pano Agent
 *   anything that is not a JVM (the shell it was started from, a `tail -f` on the log); everywhere,
 *   a world whose `session.lock` somebody holds does.
 * - `NO_SERVER_JAR`: nothing in it could be launched.
 *
 * The error a refused task reports is `"<CODE>: <sentence>"`, and the code also rides along as
 * `errorCode`, so the panel can translate the refusal and still show the sentence to anyone whose
 * panel does not know the code yet.
 */
object InPlaceAdoption {
    const val PATH_NOT_ABSOLUTE = "PATH_NOT_ABSOLUTE"
    const val PATH_NOT_FOUND = "PATH_NOT_FOUND"
    const val PATH_NOT_A_DIRECTORY = "PATH_NOT_A_DIRECTORY"
    const val PATH_NOT_ALLOWED = "PATH_NOT_ALLOWED"
    const val ALREADY_MANAGED = "ALREADY_MANAGED"
    const val SERVER_RUNNING = "SERVER_RUNNING"
    const val NO_SERVER_JAR = "NO_SERVER_JAR"

    /** Refused for reinstall and change-software, which build a new directory next to the old one. */
    const val IN_PLACE_UNSUPPORTED = "IN_PLACE_UNSUPPORTED"

    /** An adoption refused for [code]; [message] is already `"<CODE>: <sentence>"`. */
    class Refused(val code: String, sentence: String) : IllegalStateException("$code: $sentence")

    /**
     * Unix system directories: never a server, and everything under them is the operating
     * system's. `/var/lib` rather than `/var` because `/var/www`-style layouts are common, while
     * everything in `/var/lib` belongs to some package.
     */
    val UNIX_SYSTEM_ROOTS = listOf(
        "/bin", "/boot", "/dev", "/etc", "/lib", "/lib32", "/lib64", "/libx32", "/proc", "/run", "/sbin", "/sys",
        "/usr", "/var/lib"
    )

    /** What [check] needs to know about the host, injectable so the rules can be tested anywhere. */
    class Host(
        val dataDir: File,
        val windows: Boolean = HostPlatform.os == "windows",
        val home: File? = System.getProperty("user.home")?.takeIf { it.isNotBlank() }?.let { File(it) },
        /** Whether the process with this pid, found working in the folder, means a server runs there. */
        val countsAsServer: (Long) -> Boolean = ::isRunningServer,
        /** Working directories of the processes on this host, as pid to real path. */
        val processDirectories: () -> Sequence<Pair<Long, Path>> = ::linuxProcessDirectories
    )

    /**
     * Checks [rawPath] against every rule and returns the directory's real path, or throws
     * [Refused]. [registry] is what this node already supervises; [chosenJar] is the jar a Pano
     * Agent's admin named (SM-76), which counts as the server jar when the folder has it.
     */
    fun check(rawPath: String?, registry: ServerRegistry, host: Host, chosenJar: String? = null): File {
        val directory = resolve(rawPath)

        checkAllowed(directory, host)
        checkNotManaged(directory, registry.managedDirectories())
        checkRunning(directory, host.processDirectories, host.countsAsServer)

        val chosen = chosenJar?.takeIf { ServerInspection.isTopLevelFile(directory, it) }

        if (chosen == null && ServerInspection.findServerJar(directory) == null) {
            throw Refused(NO_SERVER_JAR, "That folder holds no server jar.")
        }

        return directory
    }

    /** The path as a real, existing directory, or the first of the three path refusals. */
    fun resolve(rawPath: String?): File {
        val path = rawPath?.trim().orEmpty()

        if (path.isEmpty() || !File(path).isAbsolute) {
            throw Refused(PATH_NOT_ABSOLUTE, "The server folder has to be an absolute path on this machine.")
        }

        val file = File(path)

        if (!file.exists()) {
            throw Refused(PATH_NOT_FOUND, "There is no folder at $path on this machine.")
        }

        if (!file.isDirectory) {
            throw Refused(PATH_NOT_A_DIRECTORY, "$path is a file, not a folder.")
        }

        return try {
            file.toPath().toRealPath().toFile()
        } catch (exception: Exception) {
            throw Refused(PATH_NOT_FOUND, "$path could not be resolved: ${exception.message}")
        }
    }

    /** `PATH_NOT_ALLOWED`: the node's own files, the system's, and what this user cannot write. */
    fun checkAllowed(directory: File, host: Host) {
        val path = directory.toPath()

        if (isSystemPath(directory.path, host.windows)) {
            throw Refused(PATH_NOT_ALLOWED, "${directory.path} is a system folder, not a server folder.")
        }

        val data = realOrNormal(host.dataDir)

        // A Pano Agent keeps its data in the very folder it adopts; nothing else may.
        val agentData = data == path.resolve(AgentLayout.DATA_DIRECTORY)

        if (path.startsWith(data) || (data.startsWith(path) && !agentData)) {
            throw Refused(PATH_NOT_ALLOWED, "That folder is this node's own data folder, or holds it.")
        }

        if (host.home != null && path == realOrNormal(host.home)) {
            throw Refused(PATH_NOT_ALLOWED, "That is the node user's home folder, not a server folder.")
        }

        if (!Files.isReadable(path) || !Files.isWritable(path) || (!host.windows && !Files.isExecutable(path))) {
            throw Refused(
                PATH_NOT_ALLOWED,
                "The user this node runs as (${System.getProperty("user.name") ?: "unknown"}) cannot read and " +
                    "write ${directory.path}."
            )
        }
    }

    /**
     * `ALREADY_MANAGED`: this node supervises that directory, one inside it or one around it, or it
     * carries a `server.json` another Pano node wrote.
     */
    fun checkNotManaged(directory: File, managed: Collection<File>) {
        val path = directory.toPath()

        managed.map { realOrNormal(it) }.forEach { other ->
            when {
                other == path ->
                    throw Refused(ALREADY_MANAGED, "This node already manages ${directory.path}.")

                path.startsWith(other) ->
                    throw Refused(ALREADY_MANAGED, "${directory.path} is inside $other, which this node already manages.")

                other.startsWith(path) ->
                    throw Refused(ALREADY_MANAGED, "${directory.path} holds $other, which this node already manages.")
            }
        }

        val spec = File(directory, ServerRegistry.SPEC_FILE)

        if (spec.isFile) {
            val owner = try {
                Gson().fromJson(spec.readText(), ServerSpec::class.java)?.uuid
            } catch (_: Exception) {
                null
            }

            throw Refused(
                ALREADY_MANAGED,
                if (!owner.isNullOrBlank()) {
                    "${directory.path} is already a Pano-managed server ($owner). If the node that ran it is gone, " +
                        "delete its ${ServerRegistry.SPEC_FILE} and .pano-node folder first."
                } else {
                    "${directory.path} already has a ${ServerRegistry.SPEC_FILE}, which is where Pano keeps its own " +
                        "settings; move it out of the way first."
                }
            )
        }
    }

    /**
     * `SERVER_RUNNING`: something runs from [directory] right now.
     *
     * Two independent signals, because each is blind somewhere. A process's working directory
     * (Linux, `/proc/<pid>/cwd`) sees any launcher that `cd`s into the folder, which is every one of
     * them, but only for processes this user may inspect. A held `session.lock` sees a running
     * Minecraft server whoever owns it and on every OS, but only once the world has loaded.
     */
    fun checkRunning(
        directory: File,
        processDirectories: () -> Sequence<Pair<Long, Path>>,
        countsAsServer: (Long) -> Boolean = ::isRunningServer
    ) {
        val path = directory.toPath()
        val self = ProcessHandle.current().pid()

        val running = try {
            processDirectories().firstOrNull { (pid, cwd) -> pid != self && cwd.startsWith(path) && countsAsServer(pid) }
        } catch (_: Exception) {
            null
        }

        if (running != null) {
            throw Refused(
                SERVER_RUNNING,
                "Process ${running.first} is running in ${running.second}. Stop the server's current launcher " +
                    "(its systemd service, screen or tmux session, or another panel) and try again."
            )
        }

        heldSessionLock(directory)?.let { lock ->
            throw Refused(
                SERVER_RUNNING,
                "${lock.path} is held, so the server is still running. Stop its current launcher (its systemd " +
                    "service, screen or tmux session, or another panel) and try again."
            )
        }
    }

    /**
     * Whether a process working in a folder being adopted means a server runs there: anything but
     * this daemon's own ancestors, which for a Pano Agent are its launcher and the shell (or
     * `screen`, or `tmux`) it was started from in that very folder.
     */
    fun isRunningServer(pid: Long): Boolean = pid !in ANCESTORS

    /**
     * [isRunningServer] for a Pano Agent, which is started from inside the folder it adopts: only a
     * JVM counts. The admin's other shells in that folder, an editor or a `tail -f logs/latest.log`
     * are not a server, and refusing the link over them would send people hunting for nothing.
     */
    fun isRunningServerForAgent(pid: Long): Boolean {
        if (!isRunningServer(pid)) {
            return false
        }

        val command = try {
            ProcessHandle.of(pid).orElse(null)?.info()?.command()?.orElse(null)
        } catch (_: Exception) {
            null
        } ?: return true

        return isJavaCommand(command)
    }

    /** Whether [command] (a process's executable) is a Java launcher. */
    fun isJavaCommand(command: String): Boolean =
        File(command).name.lowercase().let { it == "java" || it == "java.exe" || it == "javaw.exe" }

    /**
     * This process's ancestors, read once. They only change when one of them exits, and an agent's
     * worker whose launcher exits stops with it.
     */
    private val ANCESTORS: Set<Long> by lazy {
        generateSequence(ProcessHandle.current().parent().orElse(null)) { it.parent().orElse(null) }
            .take(MAX_ANCESTORS)
            .map { it.pid() }
            .toSet()
    }

    private const val MAX_ANCESTORS = 64

    /** The first `session.lock` in a top-level folder of [directory] that another process holds. */
    fun heldSessionLock(directory: File): File? = directory.listFiles()
        ?.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
        ?.map { File(it, SESSION_LOCK) }
        ?.firstOrNull { it.isFile && isHeld(it) }

    /**
     * Whether somebody holds a lock on [file]. Minecraft takes an exclusive `FileChannel` lock on
     * its `session.lock` for as long as the world is open; a lock this very JVM holds throws
     * [OverlappingFileLockException] instead of returning null, and counts the same. A file this
     * user cannot open for writing proves nothing either way.
     */
    fun isHeld(file: File): Boolean = try {
        RandomAccessFile(file, "rw").use { access ->
            val lock = try {
                access.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                return true
            }

            if (lock == null) {
                true
            } else {
                lock.release()

                false
            }
        }
    } catch (_: Exception) {
        false
    }

    /** Whether [path] is the root, a system directory or inside one. */
    fun isSystemPath(path: String, windows: Boolean): Boolean {
        if (windows) {
            val normal = path.replace('/', '\\').trimEnd('\\').lowercase()

            if (Regex("^[a-z]:$").matches(normal) || normal.isEmpty()) {
                return true
            }

            val rest = normal.substringAfter(":", "")

            return rest == "\\windows" || rest.startsWith("\\windows\\") || rest.startsWith("\\program files")
        }

        val normal = Paths.get(path).normalize().toString().trimEnd('/').ifEmpty { "/" }

        if (normal == "/") {
            return true
        }

        return UNIX_SYSTEM_ROOTS.any { root -> normal == root || normal.startsWith("$root/") }
    }

    /** Every readable `/proc/<pid>/cwd` on this host; empty anywhere without a `/proc`. */
    fun linuxProcessDirectories(): Sequence<Pair<Long, Path>> {
        val proc = File("/proc")

        if (!proc.isDirectory) {
            return emptySequence()
        }

        return (proc.listFiles() ?: emptyArray()).asSequence()
            .mapNotNull { entry -> entry.name.toLongOrNull()?.let { it to entry } }
            .mapNotNull { (pid, entry) ->
                try {
                    pid to File(entry, "cwd").toPath().toRealPath()
                } catch (_: Exception) {
                    null
                }
            }
    }

    /**
     * Removes what the node put into an adopted [directory] — `server.json` and `.pano-node/` — and
     * nothing else, returning whatever could not be removed. The rest of the directory is the
     * server's own and stays exactly as it is.
     */
    fun releaseFiles(directory: File): List<String> {
        val left = mutableListOf<String>()

        val spec = File(directory, ServerRegistry.SPEC_FILE)

        if (spec.exists() && !spec.delete()) {
            left.add(spec.absolutePath)
        }

        val own = ProcessRecordStore.directory(directory)

        // Not followed if it is a link: deleteRecursively would walk into wherever it points.
        if (Files.isSymbolicLink(own.toPath())) {
            if (!own.delete()) left.add(own.absolutePath)
        } else if (own.exists() && !own.deleteRecursively()) {
            left.add(own.absolutePath)
        }

        return left
    }

    private fun realOrNormal(file: File): Path = try {
        file.toPath().toRealPath()
    } catch (_: Exception) {
        file.toPath().toAbsolutePath().normalize()
    }

    private const val SESSION_LOCK = "session.lock"
}
