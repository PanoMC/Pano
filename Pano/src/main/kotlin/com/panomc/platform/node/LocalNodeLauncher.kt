package com.panomc.platform.node

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/**
 * Builds the command line Pano starts its local node with.
 *
 * The `java` is chosen by [JavaRuntimeLocator] rather than inherited from this process: Pano runs
 * on Java 11 on plenty of hosts and `pano-node.jar` is compiled for 17, so passing down
 * `java.home` is how the daemon ends up dying with an `UnsupportedClassVersionError` on every
 * restart.
 *
 * A list, never a string: the data directory and the node name come from a config file and a
 * translation, and either could contain a space or a quote that a shell would read as syntax.
 *
 * The URL always points at loopback even when Pano listens on a wildcard address. A wildcard is a
 * listening address, not a connectable one -- Linux happens to route it to loopback while Windows
 * and macOS refuse the connection outright -- so handing `0.0.0.0` to the node would work on the
 * developer's machine and fail on half the installs.
 */
object LocalNodeLauncher {
    /** Name the local node registers itself under. */
    const val DEFAULT_NAME = "Local node"

    /** Directory, relative to Pano's working directory, the node keeps all its state in. */
    const val DATA_DIR_NAME = "node-data"

    /** Where the node's stdout is captured, so a failure to start is visible after the fact. */
    val LOG_FILE_PATH = "logs" + File.separator + "pano-node.log"

    /** The daemon's own lock and pid files inside the data directory (`InstanceLock` in `:Node`). */
    const val LOCK_FILE_NAME = "pano-node.lock"
    const val PID_FILE_NAME = "pano-node.pid"

    /** A daemon that already holds the data directory; [pid] is null when its pid file is unreadable. */
    data class RunningInstance(val pid: Long?)

    /**
     * Whether a daemon is already running on [dataDir], and which one.
     *
     * Pano leaves its local node running across its own restarts, so on boot the daemon from last
     * time is usually still there. Asked by trying the daemon's own lock: held means running,
     * whatever the pid file says, because a pid file outlives a daemon that was killed and a lock
     * does not. A lock that is free is released again at once; the daemon takes it for real.
     */
    fun runningInstance(dataDir: File): RunningInstance? {
        val lockFile = File(dataDir, LOCK_FILE_NAME)

        if (!lockFile.isFile) {
            return null
        }

        RandomAccessFile(lockFile, "rw").use { file ->
            val lock = try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }

            if (lock != null) {
                lock.release()

                return null
            }
        }

        return RunningInstance(File(dataDir, PID_FILE_NAME).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull())
    }

    /**
     * The argument list, with the bootstrap token left out entirely when there is none.
     *
     * A daemon that has already paired keeps its own token in `node-data/config.conf` and ignores
     * the flag, but passing an empty value would put a meaningless argument on the command line
     * (and into every process listing) for no reason.
     */
    fun buildArguments(
        javaBin: String,
        jarPath: String,
        panoUrl: String,
        bootstrapToken: String?,
        dataDir: String,
        name: String = DEFAULT_NAME
    ): List<String> {
        val arguments = mutableListOf(javaBin, "-jar", jarPath, "--pano", panoUrl)

        if (!bootstrapToken.isNullOrBlank()) {
            arguments.add("--bootstrap-token")
            arguments.add(bootstrapToken)
        }

        arguments.add("--data")
        arguments.add(dataDir)
        arguments.add("--name")
        arguments.add(name)

        return arguments
    }

    /**
     * Environment variables the node must not inherit from Pano's own JVM.
     *
     * A JVM applies `JAVA_TOOL_OPTIONS` to itself and then passes it, unchanged, to every process
     * it starts -- so the `-Dpano.node.jar=...` Pano was launched with reaches the node, and the
     * node hands it on again to every Minecraft server it supervises, which prints a "Picked up
     * JAVA_TOOL_OPTIONS" banner into the operator's console and applies Pano's flags to a JVM that
     * never asked for them. `CLASSPATH` is the same kind of leak: invisible for a `-jar` launch
     * right up until something resolves a class off it.
     *
     * Everything else is left alone. `PATH` is how the node finds the JDKs it reports, and `LANG`
     * and `LC_ALL` decide how a server renders its own log lines.
     */
    val LEAKED_JVM_ENVIRONMENT = listOf(
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH"
    )

    /** Strips [LEAKED_JVM_ENVIRONMENT] out of a child's environment, returning the same map. */
    fun sanitizeChildEnvironment(environment: MutableMap<String, String>): MutableMap<String, String> {
        LEAKED_JVM_ENVIRONMENT.forEach { environment.remove(it) }

        return environment
    }

    /** The loopback URL the node dials back on, given Pano's own bind address and port. */
    fun resolvePanoUrl(host: String, port: Int): String {
        val resolvedHost = when (host) {
            "0.0.0.0", "" -> "127.0.0.1"
            "::", "[::]" -> "[::1]"
            else -> host
        }

        return "http://$resolvedHost:$port"
    }
}
