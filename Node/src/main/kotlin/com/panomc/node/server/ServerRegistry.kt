package com.panomc.node.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaDownloads
import com.panomc.node.task.ReinstallCarryOver
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.PathSafety
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService

/**
 * Every server this node owns, and the directory each of them lives in.
 *
 * The directory is the source of truth: the registry is rebuilt by reading `<data>/servers` at
 * boot, so a node that was restarted, moved to another machine or restored from a backup comes
 * back with exactly the servers whose files are actually there. Nothing is remembered that has no
 * directory, and nothing with a directory is forgotten.
 *
 * With one addition: a server adopted in place (`IMPORT_SERVER` mode `IN_PLACE`) lives wherever it
 * already was, and those directories are listed in [ExternalServerIndex] rather than found by the
 * scan. They load exactly like the others, [directoryFor] answers with their real path, and they
 * are marked [ServerSpec.inPlace] so nothing that deletes a directory ever takes one of them for
 * the node's own.
 */
class ServerRegistry(
    private val dataDir: File,
    private val javaLocator: JavaRuntimeLocator,
    private val runtime: ServerRuntime,
    private val scheduler: ScheduledExecutorService,
    private val logger: NodeLogger,
    private val listener: ServerProcessListener,
    /** Handed to every server so a start can download the Java it lacks (SM-63). */
    private val javaDownloads: JavaDownloads? = null
) {
    private val servers = ConcurrentHashMap<String, ServerProcess>()

    /**
     * The in-place servers' directories, by uuid, as [ExternalServerIndex] holds them. Every change
     * goes through [recordExternal] / [forgetExternal], which write the file before the map, so the
     * index on disk is never behind what this daemon believes.
     */
    private val external = ConcurrentHashMap<String, File>()

    /**
     * Set when the index exists but could not be read at [load]. Nothing is recorded or forgotten
     * while it is: rewriting the file from an empty map would erase every adopted server the
     * operator could still recover by fixing it.
     */
    @Volatile
    private var externalIndexUnreadable = false

    private val externalLock = Any()

    /** The one directory a server may ever be created under. */
    val serversRoot: File = File(dataDir, "servers")

    /**
     * What every server's [ServerProcess.consoleTap] is set to, now and for servers registered
     * later: a Pano Agent's worker printing the console into its terminal (SM-74). Null otherwise.
     */
    @Volatile
    var consoleTap: ((String) -> Unit)? = null
        set(value) {
            field = value

            servers.values.forEach { it.consoleTap = value }
        }

    fun all(): List<ServerProcess> = servers.values.toList()

    fun get(uuid: String?): ServerProcess? = uuid?.let { servers[it] }

    /** Reads every `server.json` under the servers root back into memory. */
    fun load() {
        serversRoot.mkdirs()

        // A reinstall the last daemon did not finish is put right before anything is loaded, and
        // its temporary directories are never loaded as servers: they carry the same uuid in their
        // server.json as the real one (SM-66).
        ReinstallCarryOver.recover(serversRoot, logger)

        serversRoot.listFiles()?.filter { it.isDirectory && !ReinstallCarryOver.isTemporary(it.name) }?.forEach { directory ->
            val spec = readSpec(directory) ?: return@forEach

            val uuid = spec.uuid.ifBlank { directory.name }

            if (!PathSafety.isSafeSegment(uuid)) {
                logger.warn("Ignoring server directory ${directory.name}: unusable uuid.")

                return@forEach
            }

            servers[uuid] = ServerProcess(uuid, directory, spec, javaLocator, runtime, scheduler, logger, listener, javaDownloads)
        }

        loadExternal()

        logger.info("Loaded ${servers.size} managed server(s) from ${serversRoot.absolutePath}")

        servers.values.forEach { adopt(it) }
    }

    /**
     * Loads the servers [ExternalServerIndex] lists, each from its own directory.
     *
     * An entry whose directory is gone, or whose `server.json` is missing or names another uuid, is
     * skipped with a warning and *kept* in the index: an unmounted disk or a directory an operator
     * is in the middle of moving must not end the adoption for good. Only an explicit delete takes
     * an entry out.
     */
    private fun loadExternal() {
        val entries = ExternalServerIndex.read(dataDir)

        if (entries == null) {
            externalIndexUnreadable = true

            logger.error(
                "${ExternalServerIndex.file(dataDir).absolutePath} cannot be read; servers adopted in place " +
                    "are not loaded, and none can be adopted or released, until it is fixed."
            )

            return
        }

        entries.forEach { (uuid, path) ->
            if (!PathSafety.isSafeSegment(uuid)) {
                logger.warn("Ignoring external server entry \"$uuid\": unusable uuid.")

                return@forEach
            }

            val directory = File(path)

            if (!directory.isAbsolute) {
                logger.warn("Ignoring external server $uuid: \"$path\" is not an absolute path.")

                return@forEach
            }

            external[uuid] = directory

            if (!directory.isDirectory) {
                logger.warn("Server $uuid was adopted in place at $path, which is not there any more; skipping it.")

                return@forEach
            }

            val spec = readSpec(directory)

            if (spec == null) {
                logger.warn("Server $uuid was adopted in place at $path, but its ${SPEC_FILE} is missing; skipping it.")

                return@forEach
            }

            if (spec.uuid.isNotBlank() && spec.uuid != uuid) {
                logger.warn("Ignoring external server $uuid: $path/${SPEC_FILE} belongs to ${spec.uuid}.")

                return@forEach
            }

            if (servers.containsKey(uuid)) {
                logger.warn("Server $uuid exists both under ${serversRoot.absolutePath} and at $path; the adopted one wins.")
            }

            servers[uuid] = ServerProcess(
                uuid,
                directory,
                spec.copy(uuid = uuid, inPlace = true),
                javaLocator,
                runtime,
                scheduler,
                logger,
                listener,
                javaDownloads
            )
        }
    }

    /**
     * Takes back a server that was still running when this daemon started (SM-51, §2.4.16).
     *
     * Nothing is assumed from the record alone. The runtime has to agree that the process it names
     * is still there and still this server -- a live pid whose start time and command line match,
     * or a container that is still running -- and a record that cannot be confirmed is removed on
     * the spot, because a stale file would otherwise be re-checked forever and, on a host that
     * reuses pids, eventually point at a stranger.
     */
    private fun adopt(server: ServerProcess) {
        val record = ProcessRecordStore.read(server.directory) ?: return

        // A server started to be re-attached to comes back with everything (SM-62, §2.4.27); only
        // a record from before that — or a runtime that cannot — falls through to the old adoption.
        val reattachment = try {
            runtime.reattach(server.uuid, server.directory, record)
        } catch (exception: Exception) {
            logger.warn("Could not re-attach to server ${server.uuid}: ${exception.message}")

            null
        }

        if (reattachment != null) {
            logger.info(
                when (reattachment) {
                    is Reattachment.Exited ->
                        "Server ${server.uuid} exited while the node was down (exit ${reattachment.exitCode ?: "unknown"})."

                    else -> "Re-attaching to server ${server.uuid}, still running since before this daemon started."
                }
            )

            server.reattach(reattachment, record)

            return
        }

        val process = try {
            runtime.adopt(server.uuid, server.directory, record)
        } catch (exception: Exception) {
            logger.warn("Could not check the recorded process of server ${server.uuid}: ${exception.message}")

            null
        }

        if (process == null) {
            ProcessRecordStore.delete(server.directory)

            logger.info(
                "Server ${server.uuid} is no longer running as pid ${record.pid}; its ownership record was removed."
            )

            return
        }

        logger.info(
            "Adopting server ${server.uuid}: it has been running as pid ${process.pid ?: record.pid} " +
                "since before this daemon started."
        )

        server.adopt(process, record)
    }

    /** Every server this daemon inherited rather than started, so the hello can say so. */
    fun adopted(): List<ServerProcess> = all().filter { it.adopted }

    /**
     * Servers whose exit this daemon booked on start, from what they left behind (SM-62), and that
     * are still down: the hello announces those with their exit code. Taking them clears the flag,
     * so a reconnect does not report the same exit twice.
     */
    fun takeExitsBookedWhileAway(): List<ServerProcess> = all().filter { server ->
        server.exitBookedWhileAway.also { server.exitBookedWhileAway = false } && !server.state.isAlive
    }

    /** Starts everything marked autoStart. Called once the socket is up, never before. */
    fun startAutoStartServers() {
        all().filter { it.spec.autoStart }.forEach { server ->
            logger.info("Auto-starting server ${server.uuid}.")

            server.start("auto-start")
        }
    }

    /**
     * The directory a server with [uuid] has (or would have): its adopted directory when it was
     * taken in place, otherwise its place under the servers root, checked against the sandbox.
     */
    fun directoryFor(uuid: String): File =
        external[uuid]?.takeIf { PathSafety.isSafeSegment(uuid) } ?: PathSafety.resolveUnder(serversRoot, uuid)

    /** Whether [uuid] was adopted in place, and its directory is therefore never the node's to delete. */
    fun isInPlace(uuid: String?): Boolean = uuid != null && external.containsKey(uuid)

    /**
     * Every uuid adopted in place, loaded or not, so the backup sweep counts them as registered.
     * Null when the index could not be read, which is "unknown" rather than "none".
     */
    fun externalUuids(): Set<String>? = if (externalIndexUnreadable) null else external.keys.toSet()

    /** Every directory this node supervises or has adopted, for the adoption's overlap check. */
    fun managedDirectories(): List<File> = (servers.values.map { it.directory } + external.values).distinct()

    /**
     * Records [uuid] as living at [directory] and registers it, index first: a server this daemon
     * runs but the next one would not find is exactly the state the index exists to prevent.
     */
    fun registerInPlace(uuid: String, directory: File, spec: ServerSpec): ServerProcess {
        recordExternal(uuid, directory)

        return register(uuid, directory, spec.copy(uuid = uuid, inPlace = true))
    }

    /** Adds [uuid] to the index on disk, then to memory. */
    fun recordExternal(uuid: String, directory: File) {
        require(PathSafety.isSafeSegment(uuid)) { "Rejected unsafe server id \"$uuid\"." }
        require(directory.isAbsolute) { "An adopted server directory must be an absolute path." }

        synchronized(externalLock) {
            check(!externalIndexUnreadable) {
                "${ExternalServerIndex.file(dataDir).absolutePath} cannot be read; fix or remove it first."
            }

            val next = external.mapValues { it.value.path } + (uuid to directory.path)

            ExternalServerIndex.write(dataDir, next)

            external[uuid] = directory
        }
    }

    /** Takes [uuid] out of the index on disk, then out of memory. A uuid it does not hold is a no-op. */
    fun forgetExternal(uuid: String) {
        synchronized(externalLock) {
            if (!external.containsKey(uuid)) {
                return
            }

            check(!externalIndexUnreadable) {
                "${ExternalServerIndex.file(dataDir).absolutePath} cannot be read; fix or remove it first."
            }

            ExternalServerIndex.write(dataDir, (external.keys - uuid).associateWith { external.getValue(it).path })

            external.remove(uuid)
        }
    }

    /** Registers a freshly installed server, replacing any previous entry for the same uuid. */
    fun register(uuid: String, directory: File, spec: ServerSpec): ServerProcess {
        val server = ServerProcess(uuid, directory, spec, javaLocator, runtime, scheduler, logger, listener, javaDownloads)

        server.consoleTap = consoleTap

        servers[uuid] = server

        return server
    }

    /**
     * Forgets a server. The caller is responsible for its files.
     *
     * The runtime is told as well, because a container is not a file and deleting the directory
     * would otherwise leave one behind holding this server's name and published port.
     */
    fun unregister(uuid: String) {
        servers.remove(uuid)

        runtime.remove(uuid)
    }

    /**
     * Ports already spoken for by this node's own servers.
     *
     * Only this node's: a port that something else on the host holds is caught by the bind check
     * in [PortAllocator], which is the only thing that can see outside this process.
     */
    fun takenPorts(exceptUuid: String? = null): Set<Int> = servers.values
        .filter { it.uuid != exceptUuid }
        .map { it.spec.port }
        .filter { it > 0 }
        .toSet()

    fun readSpec(directory: File): ServerSpec? {
        val file = File(directory, SPEC_FILE)

        if (!file.isFile) {
            return null
        }

        return try {
            gson.fromJson(file.readText(), ServerSpec::class.java)?.sanitised()
        } catch (exception: Exception) {
            logger.warn("Could not read ${file.absolutePath}: ${exception.message}")

            null
        }
    }

    fun writeSpec(directory: File, spec: ServerSpec) {
        directory.mkdirs()

        File(directory, SPEC_FILE).writeText(gson.toJson(spec))
    }

    /**
     * Lets go of every server on the way out (SM-51, §2.4.16).
     *
     * By default the servers are left running. A daemon exit is almost never an operator asking
     * for their Minecraft servers to go down: it is a restart, a self-update, a crash or a
     * `systemctl restart`, and stopping a server full of players for any of those was the bug this
     * replaces. The ownership records stay on disk and the next daemon adopts what it finds.
     *
     * [stopServers] is the old behaviour, kept behind `node.stop-servers-on-exit` for operators
     * who would rather have their servers down than unsupervised. Deleting a server, a POWER STOP
     * and every other explicit request are unaffected either way.
     */
    fun shutdown(stopServers: Boolean = false) {
        if (stopServers) {
            all().forEach { it.shutdown() }

            return
        }

        val running = all().filter { it.state.isAlive }

        if (running.isNotEmpty()) {
            logger.info("Leaving ${running.size} server(s) running; the next daemon takes them back.")
        }

        all().forEach { it.detach() }
    }

    companion object {
        const val SPEC_FILE = "server.json"

        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    }
}
