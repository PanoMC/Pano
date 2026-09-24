package com.panomc.platform.node

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.LocalNodeJavaMissing
import com.panomc.platform.util.OperatingSystem
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs and watches the `pano-node` daemon on Pano's own machine.
 *
 * The local node is a separate process rather than something inside this JVM, and that is the
 * whole point: restarting Pano, or updating it, must not take down the Minecraft servers it
 * manages. The same reasoning is why `local-node.stop-with-pano` defaults to false and the daemon
 * is left running when Pano stops -- an operator restarting the panel is not asking for their
 * servers to go offline.
 *
 * Supervision is deliberately modest. A daemon that exits is started again after a backoff, and
 * the backoff resets once it has managed to stay up for a while, so a mis-configured install
 * retries slowly instead of spinning. Exit code 75 is the daemon saying it has staged an update of
 * itself and wants to be restarted, which is not a failure and does not count towards the backoff.
 *
 * Exit code 78 is the daemon saying Pano removed it (SM-64): it has deleted what it held and left a
 * retired marker, and it is never restarted. Deleting the local node goes through [beginRetire] /
 * [finishRetire], which stop supervising *before* the uninstall is sent and delete `node-data`
 * once the daemon is gone, so the panel can set up a fresh local node afterwards.
 *
 * Nothing here decides whether the node is trusted: it pairs through the ordinary
 * `POST /api/node/connect` with a one-time bootstrap token from [NodeBootstrapTokenStore], which
 * is what makes it arrive already approved.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class LocalNodeManager(
    private val vertx: Vertx,
    private val logger: Logger,
    private val configManager: ConfigManager,
    private val databaseManager: DatabaseManager,
    private val nodeBootstrapTokenStore: NodeBootstrapTokenStore,
    private val nodeJarSync: NodeJarSync
) {
    /** What the panel shows for the local node's process. */
    enum class LocalNodeStatus {
        /** Turned off in config.conf; nothing will be spawned. */
        DISABLED,

        /** No local node has been set up yet. */
        NOT_SET_UP,

        /** Set up, but no process is running right now. */
        STOPPED,

        /** A process exists. */
        RUNNING,

        /** The daemon could not be started, and the reason is in [lastError]. */
        FAILED
    }

    @Volatile
    private var process: Process? = null

    /**
     * A daemon this Pano did not start but found running on the data directory, typically the one
     * the previous Pano left behind. Watched for exit like [process] is, but its exit code is not
     * ours to read.
     */
    @Volatile
    private var adopted: ProcessHandle? = null

    @Volatile
    private var jarPath: String? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var attempts = 0

    @Volatile
    private var stopping = false

    /**
     * Set once the daemon has failed three times in a row without ever staying up, which stops the
     * supervisor from restarting something that is not going to work. Cleared by a setup request,
     * because that is an admin saying they changed whatever was wrong.
     */
    @Volatile
    private var givenUp = false

    @Volatile
    private var javaMajor: Int? = null

    @Volatile
    private var javaPath: String? = null

    private val starting = AtomicBoolean(false)

    /**
     * Set while the local node is being deleted (SM-64): an exit is then the uninstall finishing,
     * or the daemon being put down for a forced delete, and is never answered with a restart.
     */
    @Volatile
    private var retiring = false

    /**
     * Set when the daemon exited with 78 (or left its retired marker) on its own: it says Pano
     * removed it, and restarting it would only make it say so again. Cleared by a setup request.
     */
    @Volatile
    private var retired = false

    /**
     * Starts the daemon again on boot when a local node was set up before.
     *
     * The row is what proves a local node exists. The process from last time either died with the
     * Pano that spawned it or, by default, is still running -- in which case it is adopted rather
     * than joined by a second one (see [LocalNodeLauncher.runningInstance]). No bootstrap token is
     * minted here: the daemon paired once and has its own token in `node-data/config.conf`.
     */
    suspend fun init() {
        val config = configManager.config.effectiveLocalNode

        if (!config.enabled) {
            logger.info("Local node is disabled in config.conf; not starting one.")

            return
        }

        val sqlClient = databaseManager.getSqlClient()

        if (findLocalNode(sqlClient) == null) {
            return
        }

        logger.info("A local node is registered; starting its daemon.")

        try {
            launchDaemon(null)
        } catch (exception: Exception) {
            lastError = exception.message

            logger.warn("Could not start the local node: ${exception.message}")
        }
    }

    /**
     * Provisions a local node, or makes sure the one that exists is running.
     *
     * Returns the node's row id when it already paired, and null the very first time -- the row is
     * only created once the daemon itself calls `POST /api/node/connect`, which is moments later.
     */
    suspend fun setup(): JsonObject {
        val config = configManager.config.effectiveLocalNode

        if (!config.enabled) {
            throw IllegalStateException("Local node support is disabled in config.conf.")
        }

        val sqlClient = databaseManager.getSqlClient()
        val existing = findLocalNode(sqlClient)

        if (existing != null && isRunning()) {
            return status(existing.id)
        }

        // An admin pressing the button is a new attempt, not a continuation of the failed one.
        givenUp = false
        attempts = 0
        retired = false

        // A fresh token every time: the previous one either was spent by the daemon that is now
        // gone, or expired, and reusing one would mean a token outliving the process it was for.
        val token = if (existing == null) nodeBootstrapTokenStore.issue() else null

        try {
            launchDaemon(token)
        } catch (exception: Exception) {
            nodeBootstrapTokenStore.revoke(token)

            lastError = exception.message

            throw exception
        }

        return status(existing?.id)
    }

    /** Whatever the panel needs to draw the local-node card. */
    suspend fun status(): JsonObject {
        val sqlClient = databaseManager.getSqlClient()

        return status(findLocalNode(sqlClient)?.id)
    }

    private fun status(nodeId: Long?): JsonObject {
        val config = configManager.config.effectiveLocalNode

        val state = when {
            !config.enabled -> LocalNodeStatus.DISABLED
            isRunning() -> LocalNodeStatus.RUNNING
            lastError != null -> LocalNodeStatus.FAILED
            nodeId != null -> LocalNodeStatus.STOPPED
            else -> LocalNodeStatus.NOT_SET_UP
        }

        return JsonObject()
            .put("nodeId", nodeId)
            .put("status", state.name)
            .put("pid", process?.takeIf { it.isAlive }?.pid() ?: adopted?.takeIf { it.isAlive }?.pid())
            .put("jarPath", jarPath)
            .put("javaPath", javaPath)
            .put("javaMajor", javaMajor)
            .put("startedAt", startedAt.takeIf { it > 0 })
            .put("error", lastError)
            .put("givenUp", givenUp)
            .put("stopWithPano", config.stopWithPano)
    }

    /**
     * Stops the daemon when Pano shuts down, and only when the operator asked for that.
     *
     * The default is to leave it running: it is a separate process holding separate Minecraft
     * servers, and a panel restart is not a reason to disconnect players.
     */
    fun shutdown() {
        if (!configManager.config.effectiveLocalNode.stopWithPano) {
            return
        }

        stopping = true

        val running = process

        if (running != null && running.isAlive) {
            logger.info("Stopping the local node because local-node.stop-with-pano is true.")

            running.destroy()

            if (!running.waitFor(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                running.destroyForcibly()
            }

            return
        }

        val inherited = adopted?.takeIf { it.isAlive } ?: return

        logger.info("Stopping the local node because local-node.stop-with-pano is true.")

        inherited.destroy()

        try {
            inherited.onExit().get(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            inherited.destroyForcibly()
        }
    }

    fun isRunning() = process?.isAlive == true || adopted?.isAlive == true

    /** The local node's data directory, which Pano created and therefore removes (SM-64). */
    fun dataDirectory(): File = dataDir()

    /**
     * Stops supervising the daemon before it is told to uninstall itself (SM-64, §2.4.29 B).
     *
     * Must come first: the uninstall ends with the daemon exiting, and a supervisor that has not
     * been told would start it again two seconds later — onto a data directory that is being
     * deleted, with nothing to do but exit again.
     */
    fun beginRetire() {
        retiring = true
    }

    /**
     * The uninstall failed and the node was not deleted: supervise it again, and start it if it
     * went down in the meantime.
     */
    fun cancelRetire() {
        if (!retiring) {
            return
        }

        retiring = false

        if (!isRunning() && configManager.config.effectiveLocalNode.enabled && !givenUp && !retired) {
            scheduleRestart(RESTART_AFTER_UPDATE_MILLIS)
        }
    }

    /** What [finishRetire] managed. */
    data class RetireResult(
        /** Whether `node-data` is gone. */
        val dataDirRemoved: Boolean,
        val dataDir: String,
        /** Why it is not, for the log and the manual steps. */
        val error: String? = null,
        /** Containers a leftover Docker server still runs in; never removed by Pano. */
        val containers: List<String> = emptyList()
    )

    /**
     * Waits for the daemon to be gone and deletes `node-data`, then forgets everything about the
     * local node so the panel shows it NOT_SET_UP again (SM-64).
     *
     * After a successful uninstall the daemon exits on its own within a few seconds. For a forced
     * delete ([kill]) a daemon that is still there is put down the way `stop-with-pano` does it.
     * Servers left running by a daemon that crashed earlier are stopped before a single file goes,
     * and if one cannot be, nothing is deleted: removing a world from under a running server is the
     * one outcome worse than leaving files behind.
     */
    suspend fun finishRetire(kill: Boolean, waitMillis: Long = RETIRE_EXIT_WAIT_MILLIS): RetireResult {
        retiring = true

        val dataDir = dataDir()

        val deadline = System.currentTimeMillis() + waitMillis

        while (isRunning() && System.currentTimeMillis() < deadline) {
            delay(EXIT_POLL_MILLIS)
        }

        if (isRunning() && kill) {
            withContext(Dispatchers.IO) { putDown() }
        }

        if (isRunning()) {
            return RetireResult(false, dataDir.absolutePath, "The local node is still running.")
        }

        val remaining = withContext(Dispatchers.IO) {
            LocalNodeDataCleanup.stop(LocalNodeDataCleanup.leftoverServers(dataDir))
        }

        val containers = remaining.mapNotNull { it.container }

        if (remaining.any { it.pid != null }) {
            return RetireResult(
                false,
                dataDir.absolutePath,
                "Server process(es) ${remaining.mapNotNull { it.pid }} are still running from ${dataDir.absolutePath}."
            )
        }

        val keep = listOfNotNull(jarPath?.let { File(it) }, configManager.config.effectiveLocalNode.jarPath?.let { File(it) })

        val failures = withContext(Dispatchers.IO) { LocalNodeDataCleanup.delete(dataDir, keep) }

        process = null
        adopted = null
        jarPath = null
        javaMajor = null
        javaPath = null
        lastError = null
        givenUp = false
        attempts = 0
        startedAt = 0
        retired = false
        retiring = false

        if (failures.isNotEmpty()) {
            logger.warn("Could not remove ${failures.size} file(s) of the local node: ${failures.take(3)}")

            return RetireResult(false, dataDir.absolutePath, "Some files could not be removed.", containers)
        }

        logger.info("Removed the local node's data directory ${dataDir.absolutePath}.")

        return RetireResult(true, dataDir.absolutePath, null, containers)
    }

    /** Stops a daemon that did not exit on its own, spawned or adopted alike. */
    private fun putDown() {
        process?.takeIf { it.isAlive }?.let { running ->
            running.destroy()

            if (!running.waitFor(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        adopted?.takeIf { it.isAlive }?.let { inherited ->
            inherited.destroy()

            try {
                inherited.onExit().get(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                inherited.destroyForcibly()
            }
        }
    }

    /** Whether the daemon left its retired marker, which an adopted process's exit cannot say. */
    private fun hasRetiredMarker() = File(dataDir(), RETIRED_MARKER).isFile

    private fun dataDir() = File(LocalNodeLauncher.DATA_DIR_NAME).absoluteFile

    /**
     * Takes over a daemon that is already running on the data directory.
     *
     * It gets the same supervision a spawned one does, minus the exit code: a process this Pano
     * did not start reports no exit value, so any exit that was not ordered is treated as "start
     * it again", which covers both a crash and a self-update's 75.
     */
    private fun adopt(instance: LocalNodeLauncher.RunningInstance) {
        val handle = instance.pid?.let { ProcessHandle.of(it).orElse(null) }

        adopted = handle
        process = null
        startedAt = System.currentTimeMillis()
        lastError = null
        stopping = false

        if (handle == null) {
            logger.warn(
                "A local node is already running on ${dataDir().absolutePath} but its pid is unknown; " +
                    "leaving it be, and not restarting it if it exits."
            )

            return
        }

        logger.info("A local node is already running (pid ${handle.pid()}); adopting it instead of starting another.")

        handle.onExit().whenComplete { _, _ ->
            if (adopted !== handle) {
                return@whenComplete
            }

            adopted = null

            if (stopping) {
                logger.info("Local node stopped.")

                return@whenComplete
            }

            if (retiring || hasRetiredMarker()) {
                retired = !retiring

                logger.info("The local node was removed from Pano and exited; not restarting it.")

                return@whenComplete
            }

            logger.info("The adopted local node (pid ${handle.pid()}) exited; starting it again.")

            attempts = 0

            scheduleRestart(RESTART_AFTER_UPDATE_MILLIS)
        }
    }

    private suspend fun findLocalNode(sqlClient: SqlClient) =
        databaseManager.nodeDao.getAll(sqlClient).firstOrNull { it.kind == NodeKind.LOCAL }

    private suspend fun launchDaemon(bootstrapToken: String?) {
        if (isRunning()) {
            return
        }

        if (!starting.compareAndSet(false, true)) {
            return
        }

        try {
            val dataDir = dataDir()

            dataDir.mkdirs()

            // The daemon from before this Pano started is usually still there, and one is all the
            // directory can hold.
            val running = withContext(Dispatchers.IO) { LocalNodeLauncher.runningInstance(dataDir) }

            if (running != null) {
                adopt(running)

                return
            }

            val config = configManager.config.effectiveLocalNode
            val jar = resolveJar()
            val java = resolveJava(config)
            val serverConfig = configManager.config.server

            val logFile = File(LocalNodeLauncher.LOG_FILE_PATH).absoluteFile

            logFile.parentFile?.mkdirs()

            val arguments = LocalNodeLauncher.buildArguments(
                javaBin = java.launcher.absolutePath,
                jarPath = jar.absolutePath,
                panoUrl = LocalNodeLauncher.resolvePanoUrl(serverConfig.host, serverConfig.httpPort),
                bootstrapToken = bootstrapToken,
                dataDir = dataDir.absolutePath
            )

            // Process work never runs on the event loop: spawning waits on the OS, and the
            // supervisor below waits on the child for as long as it lives.
            val started = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(arguments)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File(nullDevice())))

                LocalNodeLauncher.sanitizeChildEnvironment(builder.environment())

                builder.start()
            }

            process = started
            jarPath = jar.absolutePath
            javaMajor = java.major
            javaPath = java.path
            startedAt = System.currentTimeMillis()
            lastError = null
            stopping = false

            logger.info(
                "Started the local node (pid ${started.pid()}) with Java ${java.major} from ${java.path} " +
                    "(${java.source}), logging to ${logFile.absolutePath}."
            )

            supervise(started)
        } finally {
            starting.set(false)
        }
    }

    /** Restarts the daemon when it dies, unless Pano is the one that stopped it. */
    private fun supervise(started: Process) {
        started.onExit().whenComplete { exited, _ ->
            if (process !== started) {
                return@whenComplete
            }

            process = null

            val exitCode = try {
                exited.exitValue()
            } catch (_: Exception) {
                -1
            }

            if (stopping) {
                logger.info("Local node stopped.")

                return@whenComplete
            }

            if (retiring) {
                logger.info("The local node exited with code $exitCode while being removed; not restarting it.")

                return@whenComplete
            }

            if (shouldStayDown(exitCode)) {
                retired = true

                logger.warn(
                    "The local node exited with code $exitCode: it was removed from Pano and has nothing to do. " +
                        "Not restarting it; delete the local node in the panel, or its node-data directory, to start over."
                )

                return@whenComplete
            }

            if (exitCode == SELF_UPDATE_EXIT_CODE) {
                logger.info("Local node staged an update of itself; restarting it.")

                attempts = 0

                scheduleRestart(RESTART_AFTER_UPDATE_MILLIS)

                return@whenComplete
            }

            // Lost the race to a daemon that was already there: not a failure, and nothing to
            // restart. Adopt the winner instead.
            if (exitCode == ALREADY_RUNNING_EXIT_CODE) {
                val running = LocalNodeLauncher.runningInstance(dataDir())

                if (running != null) {
                    adopt(running)
                } else {
                    logger.warn("The local node said another daemon holds its data directory, but none was found; restarting it.")

                    scheduleRestart(restartDelayMillis(1))
                }

                return@whenComplete
            }

            // A daemon that stayed up is a new incident, not the next failure in a loop.
            attempts = if (System.currentTimeMillis() - startedAt > STABLE_RUN_MILLIS) 1 else attempts + 1

            // The interesting part of a daemon that will not start is the last thing it printed:
            // a wrong Java, a port already taken and a corrupt jar all look identical from an exit
            // code alone, and the panel shows this verbatim.
            val lastLine = lastLogLine()

            lastError = buildString {
                append("The local node exited with code $exitCode.")

                if (!lastLine.isNullOrBlank()) {
                    append(" Last output: ")
                    append(lastLine)
                }
            }

            if (attempts >= MAX_CONSECUTIVE_FAILURES) {
                givenUp = true

                logger.error(
                    "Local node exited $attempts times in a row without staying up; not restarting it again. " +
                        lastError
                )

                return@whenComplete
            }

            val delay = restartDelayMillis(attempts)

            logger.warn("Local node exited with code $exitCode; restarting in ${delay / 1000}s.")

            scheduleRestart(delay)
        }
    }

    private fun scheduleRestart(delayMillis: Long) {
        vertx.setTimer(delayMillis) {
            CoroutineScope(vertx.dispatcher()).launch {
                if (!configManager.config.effectiveLocalNode.enabled || stopping || givenUp || retiring || retired) {
                    return@launch
                }

                try {
                    launchDaemon(null)
                } catch (exception: Exception) {
                    lastError = exception.message

                    attempts += 1

                    if (attempts >= MAX_CONSECUTIVE_FAILURES) {
                        givenUp = true

                        logger.error("Could not start the local node after $attempts tries: ${exception.message}")

                        return@launch
                    }

                    logger.warn("Could not restart the local node: ${exception.message}")

                    scheduleRestart(restartDelayMillis(attempts))
                }
            }
        }
    }

    /**
     * The Java the daemon is started with, which is never simply the one Pano runs on.
     *
     * Throws [LocalNodeJavaMissing] with the reason instead of spawning a process that is going to
     * die with an `UnsupportedClassVersionError` and be restarted forever.
     */
    private suspend fun resolveJava(config: PanoConfig.Companion.LocalNodeConfig): JavaRuntime {
        val lookup = withContext(Dispatchers.IO) { JavaRuntimeLocator.locate(config.javaPath) }

        val runtime = lookup.runtime

        if (runtime == null) {
            javaMajor = null
            javaPath = null

            throw LocalNodeJavaMissing(
                extras = mapOf("message" to (lookup.error ?: "No Java ${JavaRuntimeLocator.MINIMUM_MAJOR}+ runtime was found."))
            )
        }

        return runtime
    }

    /** The last thing the daemon printed, which is where the reason it died actually is. */
    private fun lastLogLine(): String? = try {
        val file = File(LocalNodeLauncher.LOG_FILE_PATH).absoluteFile

        if (!file.isFile) {
            null
        } else {
            file.readLines()
                .asReversed()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(MAX_ERROR_LINE_LENGTH)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The daemon jar to run: the one [NodeJarSync] keeps matching this Pano, unpacked from the copy
     * bundled in the Pano jar when an install has none or has one from another version.
     */
    private suspend fun resolveJar(): File = nodeJarSync.ensureCurrent()

    companion object {
        /** The daemon's "I staged an update of myself, start me again" exit code. */
        const val SELF_UPDATE_EXIT_CODE = 75

        /** The daemon found another one on its data directory (`NodeVersion.ALREADY_RUNNING_EXIT_CODE`). */
        const val ALREADY_RUNNING_EXIT_CODE = 76

        /** The daemon was removed from Pano and must not be restarted (`NodeVersion.RETIRED_EXIT_CODE`). */
        const val RETIRED_EXIT_CODE = 78

        /** The file a retired daemon leaves in its data directory (`RetiredMarker` in `:Node`). */
        const val RETIRED_MARKER = ".pano-node-retired"

        /** How long a delete waits for the uninstalled daemon to exit on its own. */
        const val RETIRE_EXIT_WAIT_MILLIS = 20_000L

        private const val EXIT_POLL_MILLIS = 250L

        /** Whether an exit with [exitCode] means "leave me down" rather than "restart me". */
        fun shouldStayDown(exitCode: Int) = exitCode == RETIRED_EXIT_CODE

        /** Fast exits in a row before the supervisor stops trying and reports FAILED. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        private const val SHUTDOWN_WAIT_SECONDS = 15L

        private const val MAX_ERROR_LINE_LENGTH = 500
        private const val RESTART_AFTER_UPDATE_MILLIS = 2_000L
        private const val STABLE_RUN_MILLIS = 60_000L

        /** Same shape as the daemon's own crash backoff, for the same reason. */
        fun restartDelayMillis(attempt: Int): Long = when {
            attempt <= 1 -> 5_000L
            attempt == 2 -> 15_000L
            attempt == 3 -> 60_000L
            else -> 300_000L
        }

        private fun nullDevice() = if (Main.OPERATING_SYSTEM == OperatingSystem.WINDOWS) "NUL" else "/dev/null"
    }
}
