package com.panomc.node.task

import com.panomc.node.RetiredMarker
import com.panomc.node.host.ServerDiskUsage
import com.panomc.node.host.ServiceInstaller
import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.host.UninstallSteps
import com.panomc.node.net.NodeUninstallMessage
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Removes this node from its host because Pano deleted it (`NODE_UNINSTALL`, SM-64, §2.4.29 B).
 *
 * Deleting a node used to be a Pano-only affair: the row went, the token was revoked, and the
 * daemon on the other machine kept running, kept every server and every backup it had, and looped
 * on "Pano rejected this node's token" until somebody logged in and cleaned up by hand. Now the
 * node does its own cleanup, in the order that loses nothing it should not:
 *
 * 1. every server is stopped the way a delete stops one (graceful stop, then terminate, then
 *    kill), all at once so a host with ten servers is not ten graceful timeouts, and new work from
 *    Pano is refused from the first moment ([prepare]);
 * 2. everything the node put in its data directory is deleted — `servers`, `backups`, `java`,
 *    `cache`, `updates`, `service`, `logs` — and the retired marker is written, so a supervisor that
 *    starts the daemon again gets a clean "nothing to do" instead of a re-pairing;
 * 3. the service registration is removed when this process is allowed to (root, under the unit
 *    `install.sh` wrote), and otherwise the exact commands are handed back as `manualSteps`;
 * 4. DONE goes to Pano with `removedBytes` and `manualSteps`, and only then does [onRetired] close
 *    the socket, clear the rest of the data directory and exit with 78.
 *
 * A failure anywhere before the DONE reports FAILED and hands control back ([abort]): the node is
 * still paired, Pano still has its row, and the admin can retry or force the removal.
 */
class UninstallService(
    private val dataDir: File,
    private val registry: ServerRegistry,
    private val reporter: TaskSink,
    private val logger: NodeLogger,
    /** How this daemon was installed, read when an uninstall starts. */
    private val environment: () -> UninstallEnvironment,
    private val serviceInstaller: ServiceInstaller,
    /** Stops accepting work and pauses schedules; the daemon's half of step 1. */
    private val prepare: () -> Unit,
    /** Undoes [prepare] after a failed uninstall. */
    private val abort: () -> Unit,
    /** Called with each server's uuid as it is dropped, for the daemon's per-server state. */
    private val forgetServer: (String) -> Unit,
    /** Called after DONE went out: close the socket, finish the wipe, exit 78. */
    private val onRetired: () -> Unit,
    /** Written into the marker so a later look at the host says where it was removed from. */
    private val platformUrl: () -> String? = { null },
    private val nodeName: () -> String? = { null },
    /** Files this process runs from that happen to live under the data directory. */
    private val keep: () -> List<File> = { emptyList() },
    /**
     * A Pano Agent's server folder (SM-74). Its data directory is `<folder>/.pano-agent`, which is
     * all the uninstall deletes: the server's own files, and the agent's jar, stay where they are.
     */
    private val agentFolder: () -> String? = { null },
    private val commands: CommandRunner = CommandRunner.SYSTEM
) {
    /** Runs a host command and returns its exit code (-1 when it could not be started). */
    fun interface CommandRunner {
        fun run(command: List<String>): Int

        companion object {
            val SYSTEM = CommandRunner { command ->
                try {
                    val process = ProcessBuilder(command).redirectErrorStream(true).start()

                    process.inputStream.bufferedReader().use { it.readText() }

                    if (process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.exitValue() else {
                        process.destroyForcibly()

                        -1
                    }
                } catch (_: Exception) {
                    -1
                }
            }
        }
    }

    private val running = AtomicBoolean(false)

    fun uninstall(message: NodeUninstallMessage) {
        val taskId = message.taskId

        if (taskId.isNullOrBlank()) {
            logger.warn("Ignoring a NODE_UNINSTALL with no task id.")

            return
        }

        if (!running.compareAndSet(false, true)) {
            logger.warn("An uninstall is already running; ignoring the second NODE_UNINSTALL.")

            return
        }

        val folder = agentFolder()

        logger.notice(
            if (folder != null) {
                "Removed from Pano: stopping the server and deleting ${dataDir.absolutePath}. The server's own files stay."
            } else {
                "Pano removed this node; stopping every server and deleting everything it holds."
            }
        )

        // Read before anything is deleted: the unit `--service install` wrote lives in the data
        // directory, and whether it existed decides which commands the operator is given.
        val host = try {
            environment()
        } catch (exception: Exception) {
            logger.warn("Could not inspect how this node was installed: ${exception.message}")

            null
        }

        try {
            prepare()

            stopServers(taskId)

            reporter.running(taskId, null, KIND, DELETE_PERCENT, "Deleting files")

            val removedBytes = deleteData(taskId)

            RetiredMarker.write(dataDir, platformUrl(), nodeName())

            reporter.running(taskId, null, KIND, SERVICE_PERCENT, "Removing the service")

            val serviceHandled = host?.let { removeService(it) } ?: false

            val manualSteps = host?.let { UninstallSteps.build(it, serviceHandled) }
                ?: listOf("rm -rf ${UninstallSteps.shellQuote(dataDir.absolutePath)}")

            logger.info("Removed ${removedBytes / (1024 * 1024)} MB. This node is retired.")

            manualSteps.takeIf { it.isNotEmpty() }?.let { steps ->
                logger.info("To finish removing it from this machine, run:")

                steps.forEach { logger.info("  $it") }
            }

            reporter.done(
                taskId,
                null,
                KIND,
                "Removed",
                JsonObject()
                    .put("removedBytes", removedBytes)
                    .put("manualSteps", JsonArray(manualSteps))
            )

            onRetired()
        } catch (exception: Exception) {
            logger.error("Uninstalling this node failed: ${exception.message}", exception)

            reporter.failed(taskId, null, KIND, exception.message ?: exception.javaClass.simpleName)

            running.set(false)

            abort()
        }
    }

    /**
     * Stops every server in parallel, the way `DELETE_SERVER` stops one, and drops it.
     *
     * In parallel because a graceful stop may take the full thirty seconds plus the terminate and
     * kill that follow, and Pano waits two minutes for the whole uninstall, not per server.
     */
    private fun stopServers(taskId: String) {
        val servers = registry.all()

        reporter.running(taskId, null, KIND, STOP_PERCENT, "Stopping ${servers.size} server(s)")

        val latch = CountDownLatch(servers.size)

        servers.forEach { server ->
            Thread({
                try {
                    server.shutdown()
                } catch (exception: Exception) {
                    logger.warn("Stopping ${server.uuid} failed: ${exception.message}")
                } finally {
                    latch.countDown()
                }
            }, "pano-node-uninstall-${server.uuid.take(8)}").apply { isDaemon = true }.start()
        }

        while (!latch.await(PROGRESS_INTERVAL_SECONDS, TimeUnit.SECONDS)) {
            val stopped = servers.size - latch.count.toInt()

            reporter.running(
                taskId,
                null,
                KIND,
                STOP_PERCENT + (DELETE_PERCENT - STOP_PERCENT) * stopped / servers.size.coerceAtLeast(1),
                "Stopped $stopped of ${servers.size} server(s)"
            )
        }

        servers.forEach { server -> drop(server) }
    }

    private fun drop(server: ServerProcess) {
        forgetServer(server.uuid)

        // A server adopted in place is not the node's to delete (its directory is not under the
        // data directory, so the wipe below never reaches it): it loses only what the node put in
        // it, exactly as a DELETE_SERVER would take it away. Best effort, because the node is
        // going either way and a leftover `server.json` is something an operator can delete.
        if (registry.isInPlace(server.uuid)) {
            InPlaceAdoption.releaseFiles(server.directory).takeIf { it.isNotEmpty() }?.let { left ->
                logger.warn("Could not remove Pano's files from ${server.directory.absolutePath}: ${left.joinToString(", ")}")
            }

            try {
                registry.forgetExternal(server.uuid)
            } catch (exception: Exception) {
                logger.warn("Could not drop ${server.uuid} from the external-server index: ${exception.message}")
            }
        }

        // Also removes a Docker server's container, which is not a file and would otherwise
        // outlive the directory holding this server's name and port.
        registry.unregister(server.uuid)
    }

    /**
     * Deletes everything the node wrote into its data directory and returns how much it held.
     *
     * `config.conf` survives this step on purpose: it is what the socket reporting the progress is
     * authenticated with, and it goes in the very last clean-up, after the DONE.
     */
    private fun deleteData(taskId: String): Long {
        val targets = DATA_DIRECTORIES.map { File(dataDir, it) }.filter { it.exists() }
        val kept = keep().map { it.absoluteFile.normalize() }

        var removedBytes = 0L
        val failures = mutableListOf<String>()

        targets.forEachIndexed { index, target ->
            val size = try {
                if (target.isDirectory) ServerDiskUsage.directorySize(target) else target.length()
            } catch (_: Exception) {
                0L
            }

            val protected = kept.filter { it.toPath().startsWith(target.absoluteFile.normalize().toPath()) }

            val left = if (protected.isEmpty()) {
                if (target.deleteRecursively()) emptyList() else listOf(target.absolutePath)
            } else {
                RetiredMarker.clearAllBut(target, protected)
            }

            if (left.isEmpty()) {
                removedBytes += size
            } else {
                failures.addAll(left)
            }

            reporter.running(
                taskId,
                null,
                KIND,
                DELETE_PERCENT + (SERVICE_PERCENT - DELETE_PERCENT) * (index + 1) / targets.size,
                "Deleted ${target.name}"
            )
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException("Some files could not be removed: ${failures.take(5).joinToString(", ")}")
        }

        return removedBytes
    }

    /**
     * Removes the service registration when this process may, and reports whether it did.
     *
     * Only one case is fully automatic: running as root under the unit `install.sh` wrote. Then the
     * unit is disabled (not stopped — that would be this very process), deleted along with its
     * environment file and the install directory, and systemd reloaded, so nothing is left to start
     * the daemon again. A unit `--service install` wrote into the data directory is only a template
     * the operator copied somewhere; the template is removed and the copy is theirs to delete.
     */
    private fun removeService(host: UninstallEnvironment): Boolean {
        if (host.selfServiceUnit != null) {
            try {
                serviceInstaller.uninstall()
            } catch (exception: Exception) {
                logger.warn("Could not remove the service file: ${exception.message}")
            }
        }

        val unit = host.systemdUnit

        if (host.os != "linux" || unit == null || !host.isRoot || host.inContainer) {
            return false
        }

        // A Pano Agent has a unit of its own (`pano-agent-<id>`) beside any node on the host.
        val service = host.serviceName

        if (commands.run(listOf("systemctl", "disable", service)) != 0) {
            logger.warn("systemctl disable $service failed; leaving the unit for the operator.")

            return false
        }

        val removed = File(unit).delete()

        commands.run(listOf("systemctl", "daemon-reload"))

        // Only a node has anything else on the host: an agent's jar sits in its server's folder,
        // which is never the uninstall's to delete, and it runs as whoever runs that server.
        if (host.agentFolder == null) {
            File(host.systemdEnvPath).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }

            host.installDir
                ?.let { File(it) }
                ?.takeIf { it.isDirectory && it.name.contains(service) && !it.absolutePath.startsWith(dataDir.absolutePath) }
                ?.deleteRecursively()

            if (commands.run(listOf("id", UninstallEnvironment.SERVICE_USER)) == 0) {
                commands.run(listOf("userdel", UninstallEnvironment.SERVICE_USER))
            }
        }

        if (removed) {
            logger.info("Disabled and removed the $service service.")
        }

        return removed
    }

    companion object {
        const val KIND = "NODE_UNINSTALL"

        /** Everything under the data directory the node itself creates, apart from its config. */
        val DATA_DIRECTORIES = listOf("servers", "backups", "java", "tools", "cache", "updates", "service", "logs")

        private const val SERVICE_NAME = "pano-node"

        private const val STOP_PERCENT = 5
        private const val DELETE_PERCENT = 45
        private const val SERVICE_PERCENT = 90

        private const val PROGRESS_INTERVAL_SECONDS = 2L
        private const val COMMAND_TIMEOUT_SECONDS = 30L
    }
}
