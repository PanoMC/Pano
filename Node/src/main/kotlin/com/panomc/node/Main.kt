package com.panomc.node

import com.panomc.node.agent.AgentLauncher
import com.panomc.node.agent.AgentLayout
import com.panomc.node.agent.AgentWorkerSupport
import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.host.HostPlatform
import com.panomc.node.host.ServiceInstaller
import com.panomc.node.net.PlatformConnection
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * The daemon's entry point.
 *
 * Everything that can fail before there is anything to supervise happens here, in order: the
 * arguments, the data directory, the stored config, and pairing. A node that cannot pair exits
 * with a message rather than sitting in a retry loop against a Pano that will never accept it --
 * the code is six digits and rotates every thirty seconds, so a wrong one is a typo, not a
 * transient failure.
 *
 * Pairing is skipped entirely once the config holds a token. Pano's own local node is started with
 * its bootstrap token on every launch, and re-pairing each time would register a brand new node in
 * the panel on every restart.
 *
 * A Pano Agent (SM-74) is dispatched first, before anything heavy is loaded: the process the admin
 * runs in their server folder is the small [AgentLauncher], and the daemon below runs as its
 * worker (`--agent-worker`), with its log in a file and the server's console on the terminal.
 */
object Main {
    @Volatile
    private var logger = NodeLogger("pano-node")

    private val shutdownLatch = CountDownLatch(1)

    @Volatile
    private var daemon: NodeDaemon? = null

    /**
     * Set when this process ends itself -- a self-update (75), a retirement (78) -- as opposed to
     * being stopped from outside. Only an outside stop makes an agent's worker take its server down
     * first; an internal exit leaves it running for the next worker to re-attach to.
     */
    @Volatile
    private var internalExit = false

    @JvmStatic
    fun main(args: Array<String>) {
        val options = try {
            NodeCli.parse(args)
        } catch (exception: IllegalArgumentException) {
            logger.error(exception.message ?: "Could not read the command line.")

            println()
            println(NodeCli.USAGE)

            exitProcess(2)
        }

        if (options.help) {
            println(NodeCli.USAGE)

            return
        }

        val agent = AgentLayout.resolve(options, NodeVersion.jarPath(), File("").absoluteFile)

        // The launcher is plain JDK and must stay that way: nothing below this line has run yet.
        if (agent != null && !options.agentWorker && options.service == null) {
            AgentLauncher(agent, AgentLauncher.passThrough(args), NodeVersion.jarPath(), NodeVersion.VERSION, options = options).run()
                ?.let { exitProcess(it) }

            return
        }

        runDaemon(options, agent)
    }

    private fun runDaemon(options: NodeOptions, agent: AgentLayout?) {
        val dataDir = agent?.dataDir ?: options.dataDir
        val worker = agent != null && options.agentWorker

        dataDir.mkdirs()

        if (worker) {
            logger = NodeLogger.agent("pano-agent", dataDir)

            AgentWorkerSupport.routeJavaLogging(logger)
        }

        logger.info("pano-node ${NodeVersion.VERSION} on ${HostPlatform.os}/${HostPlatform.arch}")

        if (options.service != null) {
            runServiceAction(options, dataDir, agent)

            return
        }

        // A node Pano removed stays removed (SM-64, §2.4.29 B). Without this a service manager that
        // starts it again would find no token and either complain forever or, handed a bootstrap
        // token like Pano's own local node is, pair all over again as a brand new node. 78 is the
        // code every supervisor has been told not to restart on.
        if (RetiredMarker.isRetired(dataDir)) {
            logger.info(
                "This node was removed from Pano; nothing to do. " +
                    "Delete ${dataDir.absolutePath} to set this host up as a node again."
            )

            exitProcess(NodeVersion.RETIRED_EXIT_CODE)
        }

        // Before pairing, not after: a second daemon that pairs first and then finds the directory
        // taken has already registered itself with Pano for nothing.
        val instanceLock = InstanceLock.acquire(dataDir)

        if (instanceLock == null) {
            val holder = InstanceLock.holderPid(dataDir)?.let { " (pid $it)" } ?: ""

            if (worker) {
                logger.error("Another Pano Agent is already running for ${agent?.serverDir?.path}$holder.")
            } else {
                logger.error("Another pano-node is already running on ${dataDir.absolutePath}$holder; leaving it to it.")
            }

            exitProcess(NodeVersion.ALREADY_RUNNING_EXIT_CODE)
        }

        val config = NodeConfigStore.load(dataDir)

        applyOverrides(config, options, agent)

        // An agent without its server has nothing to run and nothing it may be asked to run.
        if (config.agent && config.agentServerOrNull() == null) {
            logger.error("This is a Pano Agent but no server folder is set. Pass --server <absolute path>.")

            instanceLock.release()

            exitProcess(2)
        }

        val vertx = Vertx.vertx()

        if (!config.isPaired()) {
            if (!pair(vertx, config, options, dataDir, worker)) {
                vertx.close()
                instanceLock.release()

                exitProcess(if (worker) NodeVersion.NOT_PAIRED_EXIT_CODE else 1)
            }

            NodeConfigStore.save(dataDir, config)
        } else {
            NodeConfigStore.save(dataDir, config)

            if (options.pairingCode != null || options.bootstrapToken != null) {
                logger.info("Already paired with ${config.platformUrl}; ignoring the pairing credentials.")
            }
        }

        val started = NodeDaemon(
            vertx,
            config,
            dataDir,
            logger,
            options.runtime,
            options.javaAutoDownload,
            options.toolAutoDownload,
            agentWorker = worker
        )

        daemon = started

        Runtime.getRuntime().addShutdownHook(Thread {
            // Ctrl+C or SIGTERM to an agent is the admin stopping their server: it goes down cleanly
            // before the worker does. An ordinary node leaves its servers running (SM-51), and an
            // internal exit of either kind is a restart that re-attaches to them.
            if (worker && !internalExit) {
                started.stopServersBeforeExit()
            }

            shutdownLatch.countDown()
        })

        if (worker) {
            // The launcher is what the admin sees as the agent; a worker it no longer supervises
            // (the launcher was killed outright) would otherwise run the server on unattended.
            AgentWorkerSupport.exitWithParent(logger)
        }

        started.start()

        logger.info("Node \"${config.name}\" is running. Data directory: ${dataDir.absolutePath}")

        shutdownLatch.await()

        logger.info("Stopping pano-node.")

        started.stop()

        try {
            vertx.close()
        } catch (_: Exception) {
        }

        // Released before the restart, so the daemon that replaces this one is not turned away by
        // a lock this process is still about to drop.
        instanceLock.release()

        // The uninstall already deleted everything that mattered; what is left is the config the
        // socket was authenticated with, the lock and whatever else sat at the top level. Cleared
        // only now, with the socket closed and the lock released, so nothing writes a file back.
        if (started.isRetired()) {
            val left = RetiredMarker.clearAllBut(dataDir, started.filesInUse())

            if (left.isNotEmpty()) {
                logger.warn("Could not remove ${left.size} file(s) from ${dataDir.absolutePath}: ${left.take(3)}")
            }

            logger.info("This node was removed from Pano. Only ${RetiredMarker.FILE_NAME} is left in ${dataDir.absolutePath}.")

            exitProcess(NodeVersion.RETIRED_EXIT_CODE)
        }

        if (started.isSelfUpdateRequested()) {
            started.applyStagedUpdate()

            exitProcess(NodeVersion.SELF_UPDATE_EXIT_CODE)
        }
    }

    /** Ends the daemon from the inside, which is how a staged self update restarts it. */
    fun requestShutdown() {
        internalExit = true

        shutdownLatch.countDown()
    }

    /** Marks the exit that is about to happen as the daemon's own (see [internalExit]). */
    fun markInternalExit() {
        internalExit = true
    }

    private fun runServiceAction(options: NodeOptions, dataDir: File, agent: AgentLayout?) {
        val installer = ServiceInstaller(dataDir, logger, agent)

        if (options.service == NodeOptions.ServiceAction.UNINSTALL) {
            installer.uninstall()

            return
        }

        val jar = NodeVersion.jarPath()

        if (jar == null) {
            logger.error("--service install needs pano-node to be started from its jar.")

            exitProcess(2)
        }

        val java = File(File(File(System.getProperty("java.home")), "bin"), HostPlatform.javaExecutable)

        installer.install(jar, java.absolutePath)
    }

    /** What the command line and the environment change in the stored config. Internal for tests. */
    internal fun applyOverrides(config: NodeConfig, options: NodeOptions, agent: AgentLayout?) {
        options.platformUrl?.let { url ->
            if (!config.isPaired()) {
                config.platformUrl = url
            }
        }

        options.name?.let { config.name = it }

        // Written into config.conf like the name, so a later start without the flag or the
        // variable keeps the range the container was deployed with.
        options.portRange?.let { range ->
            config.portRangeStart = range.first
            config.portRangeEnd = range.last
        }

        if (config.name.isBlank() || (agent != null && !config.isPaired() && options.name == null)) {
            // An agent is shown in the panel as its server, so it is named after the server's folder.
            config.name = agent?.serverDir?.name?.takeIf { it.isNotBlank() } ?: HostPlatform.hostname
        }

        // Once an agent, always an agent: the flag is written into config.conf, and a later start
        // without it does not turn the daemon into an ordinary node. The folder is re-read on every
        // start, so a server folder that was moved (with its .pano-agent inside) keeps working.
        if (options.agent || agent != null) {
            config.agent = true
        }

        agent?.serverDir?.path?.let { config.agentServer = it }
    }

    private fun pair(vertx: Vertx, config: NodeConfig, options: NodeOptions, dataDir: File, worker: Boolean): Boolean {
        if (config.platformUrl.isBlank()) {
            logger.error(
                if (worker) {
                    "This Pano Agent is not linked to Pano yet. Run it once in a terminal (or your hosting panel's " +
                        "console) to answer a few questions, or with --pano <url> --code <code> from Panel -> Servers " +
                        "-> Add server -> Pano Agent."
                } else {
                    "No Pano URL. Pass --pano <url> or set ${NodeCli.ENV_URL}."
                }
            )

            return false
        }

        if (options.pairingCode.isNullOrBlank() && options.bootstrapToken.isNullOrBlank()) {
            logger.error(
                if (worker) {
                    "This Pano Agent is not linked to Pano yet. Pass --code <code> from Panel -> Servers -> " +
                        "Add server -> Pano Agent."
                } else {
                    "This node is not paired yet. Pass --code <6-digit code> from Panel -> Servers -> Nodes, " +
                        "or --bootstrap-token when Pano started this node itself."
                }
            )

            return false
        }

        val connection = PlatformConnection(vertx, logger, config)

        return try {
            connection.pair(
                pairingCode = options.pairingCode,
                bootstrapToken = options.bootstrapToken,
                name = config.name,
                dataPath = dataDir.absolutePath,
                runtime = options.runtime,
                agentServer = config.agentServerOrNull()
            )

            true
        } catch (exception: Exception) {
            val reason = exception.message?.substringAfterLast("Exception: ") ?: exception.toString()

            logger.error("Pairing failed: $reason")

            // For the launcher, which says why and asks for a new code while somebody is there (SM-76).
            if (worker) {
                try {
                    File(dataDir, AgentLauncher.PAIRING_ERROR_FILE).writeText(reason, Charsets.UTF_8)
                } catch (_: Exception) {
                }
            }

            false
        }
    }
}
