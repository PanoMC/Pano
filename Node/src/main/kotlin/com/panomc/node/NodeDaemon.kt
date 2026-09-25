package com.panomc.node

import com.google.gson.Gson
import com.panomc.node.agent.AgentFiles
import com.panomc.node.agent.AgentLaunch
import com.panomc.node.agent.AgentLaunchReader
import com.panomc.node.agent.AgentLayout
import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodePortRange
import com.panomc.node.console.ColorSpan
import com.panomc.node.console.ConsoleBuffer
import com.panomc.node.console.ConsoleHistoryMerge
import com.panomc.node.console.ConsoleLevel
import com.panomc.node.console.ConsoleLine
import com.panomc.node.console.ServerLogSearch
import com.panomc.node.console.ServerLogTail
import com.panomc.node.host.ByteRate
import com.panomc.node.host.HostMetrics
import com.panomc.node.host.HostNetwork
import com.panomc.node.host.HostPlatform
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.java.JavaDownloads
import com.panomc.node.java.JavaPackageResolver
import com.panomc.node.java.JavaRuntimeInstaller
import com.panomc.node.java.JavaRuntimeService
import com.panomc.node.java.JavaUser
import com.panomc.node.net.JavaCatalogMessage
import com.panomc.node.net.JavaInstallMessage
import com.panomc.node.net.JavaRemoveMessage
import com.panomc.node.host.ProcessMetrics
import com.panomc.node.host.ServerDiskUsage
import com.panomc.node.host.ServiceInstaller
import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.files.BackupOrphanSweep
import com.panomc.node.files.BackupService
import com.panomc.node.files.FileService
import com.panomc.node.files.PluginScanService
import com.panomc.node.files.TransferService
import com.panomc.node.net.BackupCreateMessage
import com.panomc.node.net.BackupDeleteMessage
import com.panomc.node.net.BackupListMessage
import com.panomc.node.net.BackupRestoreMessage
import com.panomc.node.net.ConsoleHistoryMessage
import com.panomc.node.net.ConsoleSearchMessage
import com.panomc.node.net.ConsoleStreamMessage
import com.panomc.node.net.FileRequestMessage
import com.panomc.node.net.DeleteServerMessage
import com.panomc.node.net.ImportServerMessage
import com.panomc.node.net.InstallPanoPluginMessage
import com.panomc.node.net.InstallPluginMessage
import com.panomc.node.net.InstallServerMessage
import com.panomc.node.net.NodeProtocol
import com.panomc.node.net.NodeUninstallMessage
import com.panomc.node.net.PlatformConnection
import com.panomc.node.net.PlatformUrls
import com.panomc.node.net.PluginScanMessage
import com.panomc.node.net.PluginToggleMessage
import com.panomc.node.net.PowerMessage
import com.panomc.node.net.SelfUpdateMessage
import com.panomc.node.net.SendCommandMessage
import com.panomc.node.net.SetMetricsIntervalMessage
import com.panomc.node.net.SetNodeMetricsIntervalMessage
import com.panomc.node.net.ServerMetricsFrames
import com.panomc.node.net.ServerPluginStateMessage
import com.panomc.node.net.SyncSchedulesMessage
import com.panomc.node.net.TransferPullMessage
import com.panomc.node.net.TransferPushMessage
import com.panomc.node.net.UpdateStartupMessage
import com.panomc.node.server.DockerRuntime
import com.panomc.node.server.ExternalServerIndex
import com.panomc.node.server.MetricsSchedule
import com.panomc.node.server.PanoPluginInstaller
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerListPing
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProperties
import com.panomc.node.server.ServerRuntime
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.task.BuildToolsInstaller
import com.panomc.node.task.DeleteService
import com.panomc.node.task.ImportService
import com.panomc.node.task.InPlaceAdoption
import com.panomc.node.task.InstallService
import com.panomc.node.task.LoaderInstaller
import com.panomc.node.task.PluginInstallService
import com.panomc.node.schedule.ScheduleRunner
import com.panomc.node.task.SelfUpdateService
import com.panomc.node.task.StartupService
import com.panomc.node.task.TaskReporter
import com.panomc.node.task.UninstallService
import com.panomc.node.tools.GitToolInstaller
import com.panomc.node.tools.GitToolResolver
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The daemon itself: everything wired together and ticking.
 *
 * It owns three clocks and nothing else of consequence. Every 250 ms it flushes whatever console
 * output is queued for the servers Pano is watching, every ten seconds it reports the host's and
 * each running server's vital signs, and on every connect it sends a hello that lets Pano
 * reconcile what it believed against what is actually running here. The node is the authority on
 * that last point, because it is the side holding the process handles.
 *
 * As a Pano Agent's worker ([agentWorker], SM-74) it also stands in for the server jar the admin
 * used to run: its one server's console is printed to stdout (the terminal the agent runs in), the
 * lines typed there are sent to the server like console commands from Pano, the server is started
 * when the worker starts, and a Ctrl+C stops the server before the worker exits.
 */
class NodeDaemon(
    private val vertx: Vertx,
    private val config: NodeConfig,
    private val dataDir: File,
    private val logger: NodeLogger,
    /** `PROCESS` or `DOCKER`, from the command line or the environment. */
    private val runtimeId: String = ProcessRuntime.ID,
    /** `PANO_NODE_JAVA_AUTO_DOWNLOAD`, which beats `node.java-auto-download` without being saved. */
    private val javaAutoDownloadOverride: Boolean? = null,
    /** `PANO_NODE_TOOL_AUTO_DOWNLOAD`, which beats `node.tool-auto-download` the same way. */
    private val toolAutoDownloadOverride: Boolean? = null,
    /** Whether this daemon is a Pano Agent's worker, started by its launcher (SM-74). */
    private val agentWorker: Boolean = false
) : ServerProcessListener {
    /**
     * How this node runs servers.
     *
     * Chosen once, at construction, and verified in [start] before anything is loaded: a node
     * asked for a runtime its host cannot provide refuses to start rather than accepting installs
     * it would fail every time.
     */
    private val runtime: ServerRuntime =
        if (runtimeId.equals(DockerRuntime.ID, ignoreCase = true)) DockerRuntime(logger) else ProcessRuntime()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "pano-node-scheduler").apply { isDaemon = true }
    }

    private val javaLocator = JavaRuntimeLocator(dataDir)

    private val connection = PlatformConnection(vertx, logger, config)

    /** Whether a missing Java may be downloaded right now (SM-63). */
    private val javaAutoDownload: Boolean
        get() = javaAutoDownloadOverride ?: config.javaAutoDownload

    /** Whether a missing build tool (git for BuildTools) may be downloaded right now (SM-67). */
    private val toolAutoDownload: Boolean
        get() = toolAutoDownloadOverride ?: config.toolAutoDownload

    // Handed to every server before the service behind it exists: the registry is built first
    // because the service needs its server list, and a start only ever calls this much later.
    private val javaDownloads = object : JavaDownloads {
        override val enabled: Boolean get() = javaAutoDownload

        override fun installForStart(serverUuid: String, major: Int): String? =
            javaService.installForStart(serverUuid, major)
    }

    private val registry = ServerRegistry(dataDir, javaLocator, runtime, scheduler, logger, this, javaDownloads)

    // Measured off the event loop and remembered for five minutes, because the answer is a walk
    // of the whole server directory (SM-53, §2.4.18 A).
    private val diskUsage = ServerDiskUsage(vertx, logger)

    // The task reporter drops a server's measured size as each job that rewrote its files ends:
    // a restore, an import, a reinstall or a delete changes it all at once, and five minutes of
    // the previous figure would be five minutes of a wrong one.
    private val reporter = TaskReporter(connection) { uuid -> diskUsage.invalidate(uuid) }

    private val javaResolver = JavaPackageResolver()

    // Managed Java runtimes (SM-63, §2.4.28): downloaded into <data>/java, found by the locator
    // like any other runtime, and removable only when the node installed them itself.
    private val javaService = JavaRuntimeService(
        locator = javaLocator,
        installer = JavaRuntimeInstaller(dataDir, javaResolver, javaLocator, logger),
        resolver = javaResolver,
        tasks = reporter,
        announce = { payload -> connection.send(NodeProtocol.Outbound.NODE_JAVA_RUNTIMES, payload) },
        users = { javaUsers() },
        autoDownload = { javaAutoDownload },
        logger = logger
    )

    // Everything Pano hosts itself arrives as a path; this is what turns those into URLs that
    // work from this host in particular.
    private val platformUrls = PlatformUrls(config)

    private val pluginInstaller = PanoPluginInstaller(logger, platformUrls, runtime)

    // One set shared by both task services, because the collision this prevents is between them:
    // an install and an import running at the same time used to allocate the same port.
    private val portReservations = PortReservations()

    // The one place that decides a managed server's port, shared for the same reason the
    // reservations are: an install and an import running at once have to see each other's claims,
    // and both have to probe the host before writing a number into server.properties.
    private val portResolver =
        PortResolver(registry, portReservations, connection, logger) { config.portRange() }

    // Spigot has no jar to download: it is compiled here, cached per revision under
    // <data>/cache/spigot, and one build at a time however many installs ask for one. A host with
    // no git gets the node's own portable one in <data>/tools/git (SM-67), on the build's PATH only.
    private val buildToolsInstaller = BuildToolsInstaller(
        dataDir,
        javaLocator,
        logger,
        javaService = javaService,
        gitInstaller = GitToolInstaller(dataDir, GitToolResolver(), logger),
        toolAutoDownload = { toolAutoDownload }
    )

    private val installService = InstallService(
        registry,
        reporter,
        logger,
        pluginInstaller,
        portReservations,
        portResolver,
        platformUrls,
        buildToolsInstaller,
        javaService,
        agentServer = { config.agentServerOrNull() }
    )

    // Takes the server's backups with it (SM-64): they live outside its directory so a reinstall
    // keeps them, and a delete is the one thing that is meant not to.
    private val deleteService = DeleteService(registry, reporter, logger, dataDir, portReservations)

    private val startupService = StartupService(registry, logger)

    // A start an install carried is the auto-start's: same issuer, same "[Pano:auto-start] > start".
    private val pluginInstallService =
        PluginInstallService(registry, reporter, pluginInstaller, platformUrls, logger) { server ->
            server.start(AUTO_START_ISSUER)
        }

    private val loaderInstaller = LoaderInstaller(javaLocator, logger, javaService)

    private val selfUpdateService =
        SelfUpdateService(dataDir, reporter, platformUrls, logger) { requestSelfUpdateRestart() }

    private val fileService = FileService(registry, logger)

    private val pluginScanService = PluginScanService(registry, logger)

    private val backupService = BackupService(dataDir, registry, reporter, connection, logger)

    // Backups live outside the server directory, so the transfer service is given the one door to
    // them rather than a path rule that would also open every other directory on the host.
    private val transferService = TransferService(
        registry,
        config,
        logger,
        virtualSource = { uuid, path -> backupService.resolveVirtual(uuid, path) },
        // A snapshot has no archive on disk; its download is a zip written while it is sent.
        virtualArchive = { uuid, path -> backupService.resolveVirtualArchive(uuid, path) }
    )

    private val importService = ImportService(
        registry,
        reporter,
        connection,
        transferService,
        loaderInstaller,
        logger,
        dataDir,
        portReservations,
        portResolver,
        // An agent is run from its server's folder, by whoever stopped the server there and started
        // the agent in its place: the shells around it are not a running server, a JVM is.
        adoptionHost = InPlaceAdoption.Host(
            dataDir,
            countsAsServer = if (config.agent) InPlaceAdoption::isRunningServerForAgent else InPlaceAdoption::isRunningServer
        ),
        agentServer = { config.agentServerOrNull() },
        onAdopted = { server -> onAdoptedInPlace(server) }
    )

    private val scheduleRunner = ScheduleRunner(registry, backupService, connection, logger)

    /**
     * Set once this node has removed itself from the host for Pano (SM-64, §2.4.29 B). [Main]
     * reads it on the way out to clear the rest of the data directory and exit with 78.
     */
    private val retired = AtomicBoolean(false)

    private val uninstallService = UninstallService(
        dataDir = dataDir,
        registry = registry,
        reporter = reporter,
        logger = logger,
        environment = {
            UninstallEnvironment.detect(
                dataDir,
                ServiceInstaller(dataDir, logger, agentLayout()).unitFile(),
                // The jar the admin runs: an agent's worker runs from its own copy in the data
                // directory, which goes with it (SM-76).
                if (config.agent) AgentFiles.agentJar?.path ?: NodeVersion.jarPath() else NodeVersion.jarPath(),
                agentFolder = config.agentServerOrNull()
            )
        },
        serviceInstaller = ServiceInstaller(dataDir, logger, agentLayout()),
        prepare = { beginRetiring() },
        abort = { cancelRetiring() },
        forgetServer = { uuid -> forgetServer(uuid) },
        onRetired = { retire() },
        platformUrl = { config.platformUrl },
        nodeName = { config.name },
        keep = { filesInUse() },
        agentFolder = { config.agentServerOrNull() }
    )

    @Volatile
    private var consoleTimer: Long = -1

    @Volatile
    private var metricsTimer: Long = -1

    @Volatile
    private var scheduleTimer: Long = -1

    @Volatile
    private var selfUpdateRequested = false

    /**
     * The server an agent adopted and is about to start for the first time, until Pano's plugin
     * install for it arrives (which then starts it once the jar is in) or
     * [FIRST_START_PLUGIN_WAIT_MILLIS] have passed -- whichever comes first (SM-74).
     */
    private val pendingFirstStart = AtomicReference<String?>(null)

    /**
     * The server an agent just adopted, until it is first up: then the terminal is told, once, how
     * to start it from now on (SM-76).
     */
    private val announceLinked = AtomicReference<String?>(null)

    /**
     * The servers whose Pano plugin is connected, as Pano last told this node (§2.4.17 B).
     *
     * Absence is the default and the safe one: a node that has been told nothing pings, which
     * costs one loopback connection every ten seconds and produces a player count that would
     * otherwise not exist. Being in the set only ever *removes* work.
     */
    private val pluginConnected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether the previous metrics tick is still going.
     *
     * A server list ping waits up to two seconds and a host can hold a dozen servers, so a tick
     * can outlast its own interval. Skipping the next one is right where queueing would not be:
     * the frame is a snapshot, and an old snapshot sent late is worse than one not sent.
     */
    private val metricsRunning = AtomicBoolean(false)

    // The host's traffic counters, turned into rates between two NODE_METRICS ticks (§2.4.22 A).
    // Only ever touched from the metrics timer, one tick at a time.
    private val hostRx = ByteRate()
    private val hostTx = ByteRate()

    /**
     * Per-server traffic meters for the runtimes that can count a server's own traffic (Docker).
     *
     * Keyed by uuid and dropped with the server; only the server-metrics tick touches them, and
     * [metricsRunning] already keeps two of those from overlapping.
     */
    private val serverNet = ConcurrentHashMap<String, Pair<ByteRate, ByteRate>>()

    /**
     * When each server's vitals are due: a full report every ten seconds, and process samples in
     * between while Pano has asked for a faster rate (§2.4.23 A).
     */
    private val metricsSchedule = MetricsSchedule()

    /**
     * When the host's own `NODE_METRICS` is due (SM-65, §2.4.30): every ten seconds by default, and
     * as often as Pano asks while somebody watches the nodes page — the same lease, the same
     * half-second tick, and the same class as a server's process samples, under one fixed key.
     */
    private val hostMetricsSchedule = MetricsSchedule()

    /**
     * The last server list ping each running server answered, so a process report between two full
     * ones still carries the player count and MOTD instead of looking like a server nobody is on.
     * Refreshed on every full report and dropped when a ping fails, exactly as the frame was before.
     */
    private val lastPing = ConcurrentHashMap<String, ServerListPing.Status>()

    @Volatile
    private var serverMetricsTimer: Long = -1

    @Volatile
    private var lastSkipWarning: Long = 0

    val platformConnection: PlatformConnection get() = connection

    /** Whether the daemon stopped because it staged an update of itself. */
    fun isSelfUpdateRequested() = selfUpdateRequested

    /**
     * Swaps a staged update over the running jar, on the way out.
     *
     * Done here rather than only on the next start so that one restart is enough: on Linux and
     * macOS the move succeeds while this JVM is still alive, and the process the service manager
     * starts next is already the new jar. Where it cannot (Windows locks the jar), [start] tries
     * again and restarts once more.
     */
    fun applyStagedUpdate(): Boolean = selfUpdateService.applyPending()

    /** Whether this daemon uninstalled itself and should exit as retired (SM-64). */
    fun isRetired() = retired.get()

    /**
     * What this very process runs from, when that is inside the data directory: its own jar and its
     * JVM. Deleting either from under a live process is not something every OS allows, so the wipe
     * leaves them and the operator's manual steps remove the directory as a whole.
     */
    fun filesInUse(): List<File> = listOfNotNull(
        NodeVersion.jarPath()?.let { File(it) },
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { File(it) }
    )

    fun start() {
        runtime.verify()

        // A staged update is moved into place here, but this JVM was started from the old jar: only
        // a fresh start runs the new code, so exit with the restart code right away and let the
        // service manager bring the new jar up (systemd's SuccessExitStatus=75 / Pano's supervisor).
        if (selfUpdateService.applyPending()) {
            logger.notice("Restarting to run the applied update.")

            Main.markInternalExit()

            kotlin.system.exitProcess(NodeVersion.SELF_UPDATE_EXIT_CODE)
        }

        if (config.agent) {
            followMovedAgentFolder()
        }

        registry.load()

        // After the load, so a re-attached server's catch-up -- what it printed while no worker was
        // reading -- is not replayed into the terminal: the console there starts now.
        if (agentWorker) {
            registry.consoleTap = { line -> printToTerminal(line) }

            startTerminalInput()
        }

        // After the servers are loaded, and before anything could create a backup: a backup
        // folder with no server directory and no registered server belongs to a server that was
        // deleted before deletes took backups along (SM-64, §2.4.29 A).
        try {
            // Adopted servers count as registered even when their directory is not mounted yet;
            // the sweep also reads the index itself, so an unreadable one stops it altogether.
            BackupOrphanSweep.sweep(
                dataDir,
                registry.all().map { it.uuid }.toSet() + registry.externalUuids().orEmpty(),
                logger
            )
        } catch (exception: Exception) {
            logger.warn("Could not look for orphaned backups: ${exception.message}")
        }

        // After the servers are loaded, because a runtime a re-attached server still runs from is
        // exactly the one the sweep must leave alone.
        javaService.bootCleanup()

        registerHandlers()

        connection.onConnected { onConnected() }

        connection.start()

        consoleTimer = vertx.setPeriodic(ConsoleBuffer.FLUSH_INTERVAL_MILLIS) { flushConsoles() }
        metricsTimer = vertx.setPeriodic(MetricsSchedule.TICK_MS) {
            if (hostMetricsSchedule.decide(HOST_METRICS_KEY, docker = false) != MetricsSchedule.Decision.NONE) {
                reportMetrics()
            }
        }
        serverMetricsTimer = vertx.setPeriodic(MetricsSchedule.TICK_MS) { reportServerMetricsTick() }
        scheduleTimer = vertx.setPeriodic(ScheduleRunner.TICK_INTERVAL_MILLIS) { runSchedules() }

        // Only after the socket exists: a server that boots before Pano can hear about it would
        // have its whole startup land in a buffer nobody is reading.
        registry.startAutoStartServers()
    }

    fun stop() {
        if (consoleTimer >= 0) {
            vertx.cancelTimer(consoleTimer)
        }

        if (metricsTimer >= 0) {
            vertx.cancelTimer(metricsTimer)
        }

        if (serverMetricsTimer >= 0) {
            vertx.cancelTimer(serverMetricsTimer)
        }

        if (scheduleTimer >= 0) {
            vertx.cancelTimer(scheduleTimer)
        }

        connection.stop()

        // Servers are left running unless the operator asked otherwise: a daemon that is exiting
        // is restarting, updating or being stopped by a service manager, and none of those is a
        // reason to take a server full of players down (SM-51, §2.4.16).
        registry.shutdown(config.stopServersOnExit)

        scheduler.shutdownNow()
    }

    /**
     * Stops every running server gracefully and waits for it -- the power-stop path, with its
     * timeouts and its escalation to a kill. What a Pano Agent's worker does on Ctrl+C or SIGTERM
     * before it exits, because to the admin the agent *is* their server (SM-74).
     */
    fun stopServersBeforeExit() {
        val running = registry.all().filter { it.state.isAlive }

        if (running.isEmpty()) {
            return
        }

        logger.notice("Stopping the server before the agent exits.")

        running.map { server ->
            Thread({
                try {
                    server.shutdown()
                } catch (exception: Exception) {
                    logger.warn("Stopping ${server.uuid} failed: ${exception.message}")
                }
            }, "pano-agent-stop-${server.uuid.take(8)}").apply { start() }
        }.forEach { it.join() }
    }

    /** A Pano Agent's first-run answers, until it has its server (SM-76); null otherwise. */
    private fun agentLaunch(): AgentLaunch? =
        if (config.agentServerOrNull() != null && registry.all().isEmpty()) AgentLaunchReader.read(dataDir) else null

    /** The agent's folders, for the service unit and the uninstall; null on an ordinary node. */
    private fun agentLayout(): AgentLayout? = config.agentServerOrNull()?.let { AgentLayout(File(it), dataDir) }

    /**
     * Points the external index at the server folder this agent runs in now, when the folder it
     * recorded is gone and this one holds the same server: an admin who moves the whole folder,
     * `.pano-agent` and all, moved the agent with it (SM-74).
     */
    private fun followMovedAgentFolder() {
        val folder = config.agentServerOrNull()?.let { File(it) } ?: return
        val uuid = registry.readSpec(folder)?.uuid?.takeIf { it.isNotBlank() } ?: return

        try {
            if (ExternalServerIndex.followMove(dataDir, uuid, folder)) {
                logger.info("The server folder moved to ${folder.path}; following it.")
            }
        } catch (exception: Exception) {
            logger.warn("Could not follow the server folder to ${folder.path}: ${exception.message}")
        }
    }

    /** One line into the terminal a Pano Agent runs in, as the server printed it. */
    private fun printToTerminal(line: String) {
        val out = System.out

        synchronized(out) {
            out.println(line)
            out.flush()
        }
    }

    /**
     * Reads the lines typed into the agent's terminal (the launcher copies them to this process's
     * stdin) and sends each to the server, through the same path as a console command from Pano.
     * End of input only ends the reading.
     */
    private fun startTerminalInput() {
        Thread({
            try {
                System.`in`.bufferedReader().lineSequence().forEach { line -> onTerminalLine(line) }
            } catch (_: Exception) {
            }
        }, "pano-agent-terminal").apply { isDaemon = true }.start()
    }

    private fun onTerminalLine(line: String) {
        val command = line.trim().takeIf { it.isNotEmpty() } ?: return

        val server = registry.all().firstOrNull()

        if (server == null) {
            logger.notice("No server is linked to this agent yet, so there is nothing to send \"$command\" to.")

            return
        }

        if (!server.state.isAlive) {
            logger.notice("The server is not running. Start it from Pano.")

            return
        }

        if (!server.sendCommand(command, ServerProcess.TERMINAL_ISSUER)) {
            logger.notice("Could not send \"$command\" to the server.")
        }
    }

    /**
     * An agent's server was just adopted in place: start it, the first time, once Pano's plugin is in
     * it -- Pano installs the plugin right after the adoption, and a server started before that
     * would run without it until its next restart. [FIRST_START_PLUGIN_WAIT_MILLIS] caps the wait
     * for software Pano has no plugin for; once the install has arrived it is the install's end,
     * however long the download takes, that starts the server.
     */
    private fun onAdoptedInPlace(server: ServerProcess) {
        if (!agentWorker) {
            return
        }

        announceLinked.set(server.uuid)

        if (!server.spec.autoStart) {
            return
        }

        pendingFirstStart.set(server.uuid)

        logger.notice("The server starts as soon as Pano has added its plugin to it.")

        scheduler.schedule({ firstStart(server.uuid) }, FIRST_START_PLUGIN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    }

    /**
     * Starts the adopted server, once, whichever of the plugin install and the timer is first.
     * The install claims [pendingFirstStart] when it arrives, which makes this a no-op for both.
     */
    private fun firstStart(uuid: String) {
        if (!pendingFirstStart.compareAndSet(uuid, null)) {
            return
        }

        registry.get(uuid)?.start(AUTO_START_ISSUER)
    }

    override fun onState(
        server: ServerProcess,
        state: ServerProcessState,
        exitCode: Int?,
        pid: Long?,
        since: Long
    ) {
        if (state == ServerProcessState.RUNNING && announceLinked.compareAndSet(server.uuid, null)) {
            val jar = AgentFiles.agentJar?.name ?: AgentLayout.JAR_NAME

            logger.notice("Linked. From now on start the server with: java -jar $jar")
            logger.notice("To run it as a service: java -jar $jar --service install")
        }

        // A crash is the one state whose explanation is only in the console, and the console is
        // exactly what nobody was streaming when it happened: a server dies at four in the morning
        // and Pano's buffer holds nothing at all. So the tail goes up with the crash, whether or
        // not anyone asked for this server's output.
        val crashLines = if (state == ServerProcessState.CRASHED) {
            server.console.snapshot().takeLast(CRASH_LINES)
        } else {
            emptyList()
        }

        if (crashLines.isNotEmpty()) {
            sendConsoleLines(server.uuid, crashLines)
        }

        connection.send(
            NodeProtocol.Outbound.SERVER_STATE,
            JsonObject()
                .put("serverUuid", server.uuid)
                .put("state", state.name)
                .put("exitCode", exitCode)
                .put("pid", pid)
                .put("since", since)
                // When the process itself started, sent with RUNNING only: the uptime a managed
                // server with no plugin can be given at all. Null for every other state.
                .put("startedAt", if (state == ServerProcessState.RUNNING) server.processStartedAt else null)
                // Whether this process was inherited from a previous daemon, and whether anything
                // can still be written to it. The panel shows the first as a hint in the header
                // and Pano routes console commands by the second (SM-51, §2.4.16).
                .put("adopted", server.adopted)
                .put("stdinAvailable", server.stdinAvailable)
                // Carried on the state itself rather than left for Pano to dig out of the batch
                // above: the two frames are handled concurrently over there, so a notification
                // that read the buffer might read it a moment too early.
                .put("reason", server.stateReason?.message ?: reasonFor(server, crashLines))
                .apply {
                    // Machine-readable, and only when the node knows (SM-63): a start refused for
                    // want of Java carries the major, so the panel can offer to download it.
                    server.stateReason?.code?.let { put("reasonCode", it) }
                    server.stateReason?.javaMajor?.let { put("javaMajor", it) }
                }
        )
    }

    /** Every server as the Java removal check sees it (SM-63). */
    private fun javaUsers(): List<JavaUser> = registry.all().map { server ->
        JavaUser(
            serverUuid = server.uuid,
            alive = server.state.isAlive,
            javaHome = server.javaHome,
            pinnedMajor = server.spec.javaMajor.takeIf { it > ServerSpec.AUTO_JAVA_MAJOR }
        )
    }

    override fun onConsoleReady(server: ServerProcess) {
        flush(server)
    }

    private fun registerHandlers() {
        connection.on(NodeProtocol.Inbound.INSTALL_SERVER) { text ->
            installService.install(gson.fromJson(text, InstallServerMessage::class.java), reinstall = false)
        }

        connection.on(NodeProtocol.Inbound.REINSTALL_SERVER) { text ->
            installService.install(gson.fromJson(text, InstallServerMessage::class.java), reinstall = true)
        }

        connection.on(NodeProtocol.Inbound.IMPORT_SERVER) { text ->
            importService.import(gson.fromJson(text, ImportServerMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.INSTALL_PANO_PLUGIN) { text ->
            val message = gson.fromJson(text, InstallPanoPluginMessage::class.java)

            // An agent's first start is claimed the moment its plugin install arrives rather than
            // after it: from here on the first-start timer can no longer start the server under a
            // jar that is still downloading, and the install makes that start once it has ended.
            // Folded into Pano's own `startAfter`, so the two are one start, never two.
            val claimedFirstStart = message.serverUuid?.let { pendingFirstStart.compareAndSet(it, null) } == true

            pluginInstallService.installPanoPlugin(message, startAfter = claimedFirstStart || message.startAfter == true)

            // Done or failed, the plugin question is settled: an agent's first start need not wait.
            // A no-op when it was claimed above; it only still fires for a first start that was
            // armed while the install ran, and a server the install already started ignores it.
            message.serverUuid?.let { firstStart(it) }
        }

        connection.on(NodeProtocol.Inbound.POWER) { text ->
            onPower(gson.fromJson(text, PowerMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.SEND_COMMAND) { text ->
            onSendCommand(gson.fromJson(text, SendCommandMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.CONSOLE_STREAM) { text ->
            onConsoleStream(gson.fromJson(text, ConsoleStreamMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.SET_METRICS_INTERVAL) { text ->
            val message = gson.fromJson(text, SetMetricsIntervalMessage::class.java)

            // Only for a server this node runs: a uuid it does not know would sit in the schedule
            // until its lease ran out, for nobody.
            message.serverUuid?.takeIf { registry.get(it) != null }?.let { uuid ->
                metricsSchedule.setInterval(uuid, message.intervalMs)
            }
        }

        connection.on(NodeProtocol.Inbound.SET_NODE_METRICS_INTERVAL) { text ->
            val message = gson.fromJson(text, SetNodeMetricsIntervalMessage::class.java)

            hostMetricsSchedule.setInterval(HOST_METRICS_KEY, message.intervalMs)
        }

        connection.on(NodeProtocol.Inbound.CONSOLE_HISTORY) { text ->
            val message = gson.fromJson(text, ConsoleHistoryMessage::class.java)

            connection.reply(message.eventId, onConsoleHistory(message))
        }

        connection.on(NodeProtocol.Inbound.CONSOLE_SEARCH) { text ->
            val message = gson.fromJson(text, ConsoleSearchMessage::class.java)

            connection.reply(message.eventId, onConsoleSearch(message))
        }

        connection.on(NodeProtocol.Inbound.UPDATE_STARTUP) { text ->
            startupService.update(gson.fromJson(text, UpdateStartupMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.DELETE_SERVER) { text ->
            val message = gson.fromJson(text, DeleteServerMessage::class.java)

            // Dropped before the delete, not after: a schedule that fires against a directory
            // being removed is the one case where a timer can do real harm.
            message.serverUuid?.let { forgetServer(it) }

            deleteService.delete(message)
        }

        connection.on(NodeProtocol.Inbound.NODE_UNINSTALL) { text ->
            uninstallService.uninstall(gson.fromJson(text, NodeUninstallMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.INSTALL_PLUGIN) { text ->
            pluginInstallService.install(gson.fromJson(text, InstallPluginMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.SYNC_SCHEDULES) { text ->
            scheduleRunner.sync(gson.fromJson(text, SyncSchedulesMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.PLUGIN_SCAN) { text ->
            val message = gson.fromJson(text, PluginScanMessage::class.java)

            connection.reply(message.eventId, pluginScanService.scan(message))
        }

        connection.on(NodeProtocol.Inbound.PLUGIN_TOGGLE) { text ->
            val message = gson.fromJson(text, PluginToggleMessage::class.java)

            connection.reply(message.eventId, pluginScanService.toggle(message))
        }

        connection.on(NodeProtocol.Inbound.SERVER_PLUGIN_STATE) { text ->
            onPluginState(gson.fromJson(text, ServerPluginStateMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.JAVA_CATALOG) { text ->
            val message = gson.fromJson(text, JavaCatalogMessage::class.java)

            connection.reply(message.eventId, javaService.catalog())
        }

        connection.on(NodeProtocol.Inbound.JAVA_INSTALL) { text ->
            javaService.install(gson.fromJson(text, JavaInstallMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.JAVA_REMOVE) { text ->
            javaService.remove(gson.fromJson(text, JavaRemoveMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.SELF_UPDATE) { text ->
            selfUpdateService.handle(gson.fromJson(text, SelfUpdateMessage::class.java))
        }

        NodeProtocol.Inbound.FILE_OPERATIONS.forEach { operation ->
            connection.on(operation) { text ->
                val message = gson.fromJson(text, FileRequestMessage::class.java)

                connection.reply(message.eventId, fileService.handle(operation, message))
            }
        }

        connection.on(NodeProtocol.Inbound.TRANSFER_PULL) { text ->
            transferService.pull(gson.fromJson(text, TransferPullMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.TRANSFER_PUSH) { text ->
            val message = gson.fromJson(text, TransferPushMessage::class.java)

            connection.reply(message.eventId, transferService.push(message))
        }

        connection.on(NodeProtocol.Inbound.BACKUP_CREATE) { text ->
            backupService.create(gson.fromJson(text, BackupCreateMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.BACKUP_LIST) { text ->
            val message = gson.fromJson(text, BackupListMessage::class.java)

            connection.reply(message.eventId, backupService.list(message))
        }

        connection.on(NodeProtocol.Inbound.BACKUP_RESTORE) { text ->
            backupService.restore(gson.fromJson(text, BackupRestoreMessage::class.java))
        }

        connection.on(NodeProtocol.Inbound.BACKUP_DELETE) { text ->
            val message = gson.fromJson(text, BackupDeleteMessage::class.java)

            connection.reply(message.eventId, backupService.delete(message))
        }
    }

    /** Drops every piece of per-server state the daemon keeps outside the registry. */
    private fun forgetServer(uuid: String) {
        scheduleRunner.forget(uuid)
        pluginConnected.remove(uuid)
        serverNet.remove(uuid)
        metricsSchedule.forget(uuid)
        lastPing.remove(uuid)
        diskUsage.invalidate(uuid)
    }

    /**
     * The first moment of an uninstall: refuse everything Pano sends from now on and stop running
     * schedules, so nothing starts, installs or backs up a server that is about to be deleted.
     */
    private fun beginRetiring() {
        connection.inboundGate = { false }

        if (scheduleTimer >= 0) {
            vertx.cancelTimer(scheduleTimer)

            scheduleTimer = -1
        }
    }

    /** An uninstall that failed hands the node back: work is accepted and schedules run again. */
    private fun cancelRetiring() {
        connection.inboundGate = null

        if (scheduleTimer < 0) {
            scheduleTimer = vertx.setPeriodic(ScheduleRunner.TICK_INTERVAL_MILLIS) { runSchedules() }
        }
    }

    /**
     * The uninstall reported DONE: exit, after the grace that lets that frame leave the socket
     * (the same one a self-update gives its own). [Main] finishes the wipe once the socket is shut.
     */
    private fun retire() {
        retired.set(true)

        vertx.setTimer(SELF_UPDATE_GRACE_MILLIS) {
            logger.notice(if (config.agent) "Removed from Pano; the agent is exiting." else "This node was removed from Pano; shutting down.")

            Main.requestShutdown()
        }
    }

    private fun onConnected() {
        // Streaming always restarts off: Pano decides who is watching, and a reconnect is exactly
        // the moment its idea of that and the node's can have drifted apart.
        registry.stopConsoleStreams()

        // For the same reason, and with the same bias: what this node was told about the plugins
        // belongs to the connection that told it. Pano re-states it after the hello, and until it
        // does the node pings, which is the answer that is merely redundant rather than missing.
        pluginConnected.clear()

        sendHello()
    }

    private fun sendHello() {
        // `version` and `managed` since protocol 3 (SM-63); the same list NODE_JAVA_RUNTIMES sends.
        val runtimes = javaService.runtimesJson()

        val servers = JsonArray()

        registry.all().forEach { server ->
            servers.add(
                JsonObject()
                    .put("uuid", server.uuid)
                    .put("state", server.state.name)
                    .put("pid", server.pid)
                    .put("since", server.since)
                    // So a Pano that restarted — or just added the column — learns how long each
                    // running server has been up without waiting for it to restart.
                    .put("startedAt", server.processStartedAt)
                    // An adopted server is announced RUNNING like any other, and these two are how
                    // Pano knows not to treat it as one it could type into (SM-51, §2.4.16).
                    .put("adopted", server.adopted)
                    .put("stdinAvailable", server.stdinAvailable)
                    // How the last run ended, for a server that is down: a daemon that booked an
                    // exit on start (SM-62) has nobody else to tell the code to.
                    .put("exitCode", if (server.state.isAlive) null else server.lastExitCode)
                    // Where the server lives, and whether it was adopted there rather than made
                    // under the data directory (protocol 5): the panel shows the path, and hides
                    // reinstall for a server that is somebody else's directory.
                    .put("inPlace", registry.isInPlace(server.uuid))
                    .put("directory", server.directory.absolutePath)
            )
        }

        connection.send(
            NodeProtocol.Outbound.NODE_HELLO,
            JsonObject()
                .put("version", NodeVersion.VERSION)
                // What a development build has instead of a version: two `local-build` daemons
                // are told apart by the bytes or not at all.
                .put("jarSha256", NodeVersion.jarSha256)
                .put("protocolVersion", NodeProtocol.VERSION)
                .put("os", HostPlatform.os)
                .put("arch", HostPlatform.arch)
                .put("cpuCores", HostMetrics.cpuCores())
                .put("memTotal", HostMetrics.memTotal())
                .put("diskTotal", HostMetrics.diskTotal(dataDir))
                .put("dataPath", dataDir.absolutePath)
                .put("runtime", runtime.id)
                .put("javaRuntimes", runtimes)
                .put("servers", servers)
                // The host's IANA zone id, which Pano shows a server's times in when asked to and
                // no plugin in that server has said otherwise (§2.4.25).
                .put("timeZone", java.time.ZoneId.systemDefault().id)
                // Whether a missing Java is downloaded by itself (SM-63): the panel says so next
                // to the Java card, and "will be downloaded" hints are only true when it is.
                .put("javaAutoDownload", javaAutoDownload)
                // The ports this node's servers may bind: Pano allocates new servers inside them
                // instead of walking up from 25565, which a container may not even publish.
                .put("portRange", NodePortRange.toHello(config.portRange()))
                .put("capabilities", JsonArray(NodeProtocol.Capabilities.ALL))
                // A Pano Agent (protocol 5) says so and names its one server: Pano adopts that
                // directory in place on the first hello that finds no server here yet.
                .put("agent", config.agent)
                .put("agentServer", config.agentServerOrNull())
                .apply {
                    // How the admin said the server runs (SM-76), while there is no server yet: Pano
                    // creates its row with that memory and those flags. Absent everywhere else.
                    agentLaunch()?.let { put("agentLaunch", AgentLaunchReader.toHello(it)) }
                }
        )

        logger.info("Announced ${registry.all().size} server(s) and ${runtimes.size()} Java runtime(s) to Pano.")

        // The adoption itself happened before this socket existed, so its state change went
        // nowhere. Pano gets it now, as an ordinary SERVER_STATE, which is what its panels,
        // its activity log and its reconciliation all already understand.
        registry.adopted().forEach { server ->
            onState(server, server.state, exitCode = null, pid = server.pid, since = server.since)
        }

        // Same for an exit booked from the exit file on start (SM-62): announced the way a watched
        // exit is, code included, so Pano books it — and a crash is logged and alerted — as usual.
        registry.takeExitsBookedWhileAway().forEach { server ->
            onState(server, server.state, exitCode = server.lastExitCode, pid = null, since = server.since)
        }
    }

    private fun onPower(message: PowerMessage) {
        val server = registry.get(message.serverUuid)

        if (server == null) {
            logger.warn("Ignoring POWER for unknown server ${message.serverUuid}.")

            return
        }

        when (message.action?.uppercase()) {
            "START" -> server.start(message.issuedBy)
            "STOP" -> server.stop(message.issuedBy)
            "RESTART" -> server.restart(message.issuedBy)
            "KILL" -> server.kill(message.issuedBy)
            else -> logger.warn("Ignoring unknown power action \"${message.action}\".")
        }
    }

    private fun onSendCommand(message: SendCommandMessage) {
        val server = registry.get(message.serverUuid) ?: return
        val command = message.command ?: return

        if (server.sendCommand(command, message.issuedBy)) {
            return
        }

        // NO_STDIN is its own sentence because it is the one refusal that is permanent until the
        // server is restarted, and Pano is supposed to have routed around it already.
        if (!server.stdinAvailable) {
            logger.warn("Refused a command for adopted server ${server.uuid}: NO_STDIN.")

            return
        }

        logger.warn("Could not deliver a command to server ${server.uuid}.")
    }

    /**
     * Answers `CONSOLE_HISTORY` with one page of this server's console.
     *
     * Deliberately not gated on streaming: the whole point is the console nobody was watching.
     *
     * The first page is the ring buffer, topped up from the log files when the ring is short of
     * it. Every page after that ([ConsoleHistoryMessage.skip] > 0) comes from the files alone --
     * the ring only ever holds the newest lines, which are exactly the ones an older page has
     * already been shown.
     */
    private fun onConsoleHistory(message: ConsoleHistoryMessage): JsonObject {
        val server = registry.get(message.serverUuid)
            ?: return JsonObject().put("ok", false).put("error", "UNKNOWN_SERVER")

        val limit = (message.limit ?: ConsoleBuffer.RING_BUFFER_SIZE).coerceIn(1, ServerLogTail.MAX_LIMIT)
        val skip = (message.skip ?: 0).coerceIn(0, ServerLogTail.MAX_SKIP)

        // A search (§2.4.20) reads the same window "Load older" could reach and returns only what
        // matches, with skip and limit counting matches. It goes to the files and not the ring:
        // everything the ring holds was written to latest.log too, and mixing the two would count
        // the same line twice. A server with no log files at all still gets its ring searched, so
        // Find is never simply blind.
        val query = ServerLogTail.searchNeedle(message.query)

        if (query != null) {
            val page = if (ServerLogTail.hasHistory(server.directory)) {
                fromDisk(server, limit, skip, query)
            } else {
                ServerLogTail.searchLines(server.console.snapshot(), limit, skip, query)
            }

            return consoleHistory(server, page.lines, page.hasMore)
        }

        if (skip > 0) {
            val page = fromDisk(server, limit, skip)

            return consoleHistory(server, page.lines, page.hasMore)
        }

        val ring = server.console.snapshot().takeLast(limit)

        // Both consoles are per-process, so a restarted node -- or a server started long before
        // anyone opened its console -- has nothing to replay although the server wrote every line
        // of it to logs/. Only when the ring is short, and only for the shortfall: a full ring is
        // already the answer and must not cost disk reads on every panel open.
        if (ring.size >= limit) {
            return consoleHistory(server, ring, ServerLogTail.hasHistory(server.directory))
        }

        val page = fromDisk(server, limit, 0)

        return consoleHistory(server, ConsoleHistoryMerge.merge(page.lines, ring, limit), page.hasMore)
    }

    /** One page of log-file history, or none at all: a console must open even off a bad disk. */
    private fun fromDisk(server: ServerProcess, limit: Int, skip: Int, query: String? = null): ServerLogTail.Page = try {
        ServerLogTail.read(server.directory, limit, skip, query)
    } catch (throwable: Throwable) {
        logger.warn("Could not read ${server.uuid} log history: ${throwable.message}")

        ServerLogTail.Page(emptyList(), false)
    }

    /**
     * Answers `CONSOLE_SEARCH` with one page of a search through every log file [message] names a
     * server for, newest file first (see [ServerLogSearch]).
     *
     * The server's own ring is only searched when it has no log file of any kind: everything the
     * ring holds was written to `latest.log` as well, and searching both would count a line twice.
     * A disk that fails under the search is `READ_FAILED` rather than an empty answer, because an
     * empty answer says "never happened", which is exactly what nobody should be told by mistake.
     */
    private fun onConsoleSearch(message: ConsoleSearchMessage): JsonObject {
        val server = registry.get(message.serverUuid) ?: return consoleSearchError("UNKNOWN_SERVER")

        val result = try {
            ServerLogSearch.search(
                serverDirectory = server.directory,
                query = message.query,
                cursor = message.cursor,
                limit = message.limit,
                budgetMs = message.budgetMs,
                ring = { server.console.snapshot() }
            )
        } catch (throwable: Throwable) {
            logger.warn("Could not search ${server.uuid} logs: ${throwable.message}")

            return consoleSearchError(ServerLogSearch.ERROR_READ_FAILED)
        }

        result.error?.let { return consoleSearchError(it) }

        val lines = toJson(result.matches.map { it.line })

        result.matches.forEachIndexed { index, match -> lines.getJsonObject(index).put("f", match.file) }

        return JsonObject()
            .put("ok", true)
            .put("serverUuid", server.uuid)
            .put("lines", lines)
            .put("cursor", result.cursor)
            .put("done", result.done)
            .put("scannedFiles", result.scannedFiles)
            .put("totalFiles", result.totalFiles)
            .put("scannedBytes", result.scannedBytes)
            .put("capped", result.capped)
    }

    /**
     * A search that could not be answered, shaped like the plugin's: finished, with no cursor, so a
     * panel that pages on cursors stops instead of asking the same question forever.
     */
    private fun consoleSearchError(code: String): JsonObject = JsonObject()
        .put("ok", false)
        .put("error", code)
        .put("done", true)
        .putNull("cursor")

    private fun consoleHistory(server: ServerProcess, lines: List<ConsoleLine>, hasMore: Boolean): JsonObject =
        JsonObject()
            .put("ok", true)
            .put("serverUuid", server.uuid)
            .put("lines", toJson(lines))
            .put("hasMore", hasMore)

    private fun onConsoleStream(message: ConsoleStreamMessage) {
        val uuid = message.serverUuid?.takeIf { it.isNotBlank() } ?: return

        // Remembered by uuid even when there is no server yet (an install still under way, or one
        // that failed), so the one registered later streams from its first line.
        val server = registry.setConsoleStreaming(uuid, message.enabled == true) ?: return

        if (message.enabled == true) {
            flush(server)
        }
    }

    private fun flushConsoles() {
        registry.all().forEach { server ->
            if (server.console.hasPending()) {
                flush(server)
            }
        }
    }

    /**
     * Sends everything currently queued for one server, batch by batch.
     *
     * The loop is what keeps a chatty server from building an ever-growing backlog: the per-second
     * ceiling is enforced inside the buffer, so this drains as much as the budget allows and stops
     * on its own when it runs out.
     */
    private fun flush(server: ServerProcess) {
        if (!connection.isConnected()) {
            return
        }

        while (true) {
            val batch = server.console.takeBatch() ?: return

            sendConsoleLines(server.uuid, batch.lines, batch.dropped)

            if (batch.lines.size < ConsoleBuffer.BATCH_SIZE) {
                return
            }
        }
    }

    /** One `SERVER_CONSOLE_LINES` frame, used by the flush and by the crash tail alike. */
    private fun sendConsoleLines(serverUuid: String, lines: List<ConsoleLine>, dropped: Long = 0) {
        connection.send(
            NodeProtocol.Outbound.SERVER_CONSOLE_LINES,
            JsonObject()
                .put("serverUuid", serverUuid)
                .put("lines", toJson(lines))
                .put("dropped", dropped)
        )
    }

    private fun toJson(lines: List<ConsoleLine>): JsonArray {
        val array = JsonArray()

        lines.forEach { line ->
            val json = JsonObject().put("t", line.t).put("l", line.l).put("m", line.m)

            // Colour spans (§2.4.21 A), only when the line has any: a plain line goes out exactly
            // as it always has, and an older Pano never sees a key it does not know.
            line.c?.takeIf { it.isNotEmpty() }?.let { spans -> json.put("c", spansToJson(spans)) }

            array.add(json)
        }

        return array
    }

    /** `[[start, end, color, flags], …]`, with a null colour for the default foreground. */
    private fun spansToJson(spans: List<ColorSpan>): JsonArray {
        val array = JsonArray()

        spans.forEach { span ->
            val entry = JsonArray().add(span.start).add(span.end)

            if (span.color == null) entry.addNull() else entry.add(span.color)

            array.add(entry.add(span.flags))
        }

        return array
    }

    /**
     * Runs the schedule tick off the event loop.
     *
     * A scheduled backup zips a whole server directory and a scheduled stop waits for the process
     * to exit, so doing either on the loop that owns the socket would freeze every console and the
     * heartbeat with it — the same reason inbound handlers are dispatched this way.
     */
    private fun runSchedules() {
        vertx.executeBlocking<Void>({
            try {
                scheduleRunner.tick()
            } catch (exception: Exception) {
                logger.error("Running schedules failed: ${exception.message}", exception)
            }

            null
        }, false)
    }

    private fun reportMetrics() {
        if (!connection.isConnected()) {
            return
        }

        val now = System.currentTimeMillis()

        // One small file read on Linux, null everywhere else; the first tick has no baseline and
        // reports null too, which the panel shows as a dash rather than a spike.
        val network = HostNetwork.read()

        connection.send(
            NodeProtocol.Outbound.NODE_METRICS,
            JsonObject()
                .put("t", now)
                .put("cpu", HostMetrics.cpuPercent())
                .put("memUsed", HostMetrics.memUsed())
                .put("memTotal", HostMetrics.memTotal())
                .put("diskUsed", HostMetrics.diskUsed(dataDir))
                .put("diskTotal", HostMetrics.diskTotal(dataDir))
                .put("netRxBps", hostRx.rate(network?.rx, now))
                .put("netTxBps", hostTx.rate(network?.tx, now))
        )
    }

    /**
     * The per-server half of the metrics, on a half-second tick (§2.4.23 A).
     *
     * [metricsSchedule] decides who is due: every server gets its full report every ten seconds as
     * before, and a server Pano asked to watch faster gets process reports in between. With nobody
     * watching fast this sends exactly what the ten-second tick used to.
     */
    private fun reportServerMetricsTick() {
        if (!connection.isConnected()) {
            return
        }

        if (!metricsRunning.compareAndSet(false, true)) {
            // At two ticks a second a slow round (a dozen pings of stopped servers) skips several
            // ticks in a row; once a minute is enough to say so.
            val now = System.currentTimeMillis()

            if (now - lastSkipWarning > SKIP_WARNING_INTERVAL_MILLIS) {
                lastSkipWarning = now

                logger.warn("The previous server metrics round is still running; skipping ticks.")
            }

            return
        }

        val docker = runtime is DockerRuntime

        // Off the timer's thread, because a server list ping is a TCP connection with a two
        // second budget and there can be a dozen of them: on the event loop that is every other
        // server's console and the socket's own heartbeat stalled behind a stopped server.
        vertx.executeBlocking<Void>({
            try {
                val now = System.currentTimeMillis()

                registry.all().forEach { server ->
                    when (metricsSchedule.decide(server.uuid, docker)) {
                        MetricsSchedule.Decision.FULL -> reportServerMetrics(server, now, full = true)
                        MetricsSchedule.Decision.PROCESS -> reportServerMetrics(server, now, full = false)
                        MetricsSchedule.Decision.NONE -> Unit
                    }
                }
            } catch (throwable: Throwable) {
                logger.warn("Reporting server metrics failed: ${throwable.message}")
            } finally {
                metricsRunning.set(false)
            }

            null
        }, false)
    }

    /**
     * One server's vital signs, plus what a server list ping can add to them (§2.4.17 B).
     *
     * The ping is the node's stand-in for the Pano plugin: a running server answers the same
     * status handshake the player's server list uses, so a managed server with no plugin in it
     * still reports how many players are on, who they are, its MOTD and its version. It is only
     * asked when there is nobody better to ask -- a connected plugin reports all of this from
     * inside, exactly and without a sample cap -- and only of a RUNNING server, because a process
     * that is still starting has not bound its port and a stopped one has nothing behind it.
     *
     * A stopped server is reported too, and reported differently: it has no process to measure,
     * but its files are still on this disk, so it sends the size of its directory and nothing
     * else (§2.4.18 A). Pano keeps that out of the live sample precisely because of what is
     * missing from it -- a server that is off must not be recorded as one that is on.
     */
    private fun reportServerMetrics(server: ServerProcess, now: Long, full: Boolean) {
        // Asked first and for every server, running or not: the first tick of a stopped server is
        // what schedules the walk that the tick after it reports.
        val diskBytes = diskUsage.bytesOf(server.uuid, server.directory)

        // One system call, no walk, so it is read fresh every tick: it is the denominator the
        // panel's disk gauge needs, and a directory can move to another disk between two ticks.
        val diskTotalBytes = ServerDiskUsage.totalSpace(server.directory)

        // Through the runtime, not the handle: under Docker the local process is an attached
        // client that uses no CPU and holds no world, so reading it would report zeros for
        // every containerised server.
        val sample = server.sample()

        if (sample == null) {
            // A stopped server's counters start over with the process; the first tick after the
            // next start must not be a rate across the whole time it was off.
            serverNet.remove(server.uuid)
            lastPing.remove(server.uuid)

            // Nothing to say until a walk has finished; an empty frame would only cost a round
            // trip every ten seconds for every stopped server on the host. Only on a full report:
            // a stopped server has no process to sample faster.
            if (full && diskBytes != null) {
                connection.send(
                    NodeProtocol.Outbound.SERVER_PROCESS_METRICS,
                    ServerMetricsFrames.diskOnly(server.uuid, now, diskBytes, diskTotalBytes)
                )
            }

            return
        }

        val meters = serverNet.computeIfAbsent(server.uuid) { ByteRate() to ByteRate() }

        val frame = ServerMetricsFrames.process(
            server.uuid,
            now,
            sample,
            diskBytes,
            diskTotalBytes,
            meters.first.rate(sample.netRxTotal, now),
            meters.second.rate(sample.netTxTotal, now)
        )

        if (server.state == ServerProcessState.RUNNING && server.uuid !in pluginConnected) {
            // Asked afresh only on a full report; a process report in between carries what the
            // last ping answered, so a fast feed does not flicker the player count to nothing.
            val status = if (full) {
                pingOf(server).also { answered ->
                    if (answered != null) lastPing[server.uuid] = answered else lastPing.remove(server.uuid)
                }
            } else {
                lastPing[server.uuid]
            }

            status?.let { describePing(frame, it) }
        } else {
            lastPing.remove(server.uuid)
        }

        connection.send(NodeProtocol.Outbound.SERVER_PROCESS_METRICS, frame)
    }

    /** Adds what a ping answered. A ping that failed adds nothing and is not an error. */
    private fun describePing(frame: JsonObject, status: ServerListPing.Status) {
        val sample = JsonArray()

        status.sample.forEach { player -> sample.add(player.name) }

        frame
            .put("playerCount", status.online)
            .put("maxPlayers", status.max)
            .put("playerSample", sample)
            .put("motd", status.motd)
            .put("versionName", status.version?.name)
    }

    /**
     * Pings one server on loopback, or null when there is no port to ask on.
     *
     * Always `127.0.0.1`, under every runtime: a container publishes its game port one-to-one onto
     * the host (`DockerCommands.createArgs`), so the number in `server.properties`, the number
     * Pano holds and the number to connect to are deliberately the same one.
     */
    private fun pingOf(server: ServerProcess): ServerListPing.Status? {
        val port = gamePortOf(server) ?: return null

        return ServerListPing.status(PING_HOST, port)
    }

    /**
     * The port this server is actually listening on.
     *
     * The spec first, because it is what the node launched the server with, and
     * `server.properties` after it: a server imported from a folder, or one an operator edited by
     * hand, can be listening somewhere the spec never mentioned. A proxy has no
     * `server.properties` at all, which is why a missing file is a null rather than a default.
     */
    private fun gamePortOf(server: ServerProcess): Int? {
        server.spec.port.takeIf { it in 1..65535 }?.let { return it }

        return ServerProperties.read(File(server.directory, "server.properties"))["server-port"]
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
    }

    /**
     * Records whether a server's Pano plugin is connected (`SERVER_PLUGIN_STATE`).
     *
     * Nothing else changes: the flag decides one thing, whether the next metrics tick pings this
     * server, and a message that names a server this node does not hold is simply remembered in
     * case it installs one under that uuid later.
     */
    private fun onPluginState(message: ServerPluginStateMessage) {
        val uuid = message.serverUuid?.takeIf { it.isNotBlank() } ?: return

        if (message.connected == true) {
            pluginConnected.add(uuid)
        } else {
            pluginConnected.remove(uuid)
        }
    }

    private fun requestSelfUpdateRestart() {
        selfUpdateRequested = true

        // Off the handler thread, so the DONE frame this update produced actually leaves the
        // socket before it is closed.
        vertx.setTimer(SELF_UPDATE_GRACE_MILLIS) {
            logger.notice(if (config.agent) "Restarting the agent to finish its update; the server keeps running." else "Restarting to finish the node update.")

            Main.requestShutdown()
        }
    }

    /**
     * The line that explains why [server] is in the state it is.
     *
     * Which end of the output to read depends on how it died, and the two are opposites. A running
     * server that falls over prints the cause and then unwinds, so the first error is the sentence
     * somebody needs. A server that never finished starting prints its progress and *then* fails,
     * so the answer is the last thing it said -- typically a `Caused by: java.net.BindException`
     * under a stack of frames that mean nothing on their own.
     */
    private fun reasonFor(server: ServerProcess, crashLines: List<ConsoleLine>): String? =
        if (server.startupFailed) startupFailureReason(crashLines) else crashReason(crashLines)

    companion object {
        const val METRICS_INTERVAL_MILLIS = 10_000L

        /** The one key [hostMetricsSchedule] is asked about; never a server uuid (those are UUIDs). */
        const val HOST_METRICS_KEY = "@host"
        /** How often a skipped server-metrics tick is worth a warning. */
        private const val SKIP_WARNING_INTERVAL_MILLIS = 60_000L

        /** Where a managed server is pinged; a node only ever asks servers on its own host. */
        const val PING_HOST = "127.0.0.1"

        /** Lines sent with a crash, so the reason is in Pano's buffer even with nobody watching. */
        const val CRASH_LINES = 50

        /** How much of a crash line is worth putting in a notification. */
        const val CRASH_REASON_LENGTH = 200

        /**
         * The line that most likely says why a server died.
         *
         * The *first* error rather than the last: a JVM prints the cause and then unwinds, so the
         * top of the failure is the sentence a person needs ("UnsupportedClassVersionError"), and
         * the bottom is a stack frame in somebody's plugin.
         */
        fun crashReason(lines: List<ConsoleLine>): String? = lines
            .firstOrNull { line ->
                // The node's own "exited with code N" summary is ERROR too, but it is the exit
                // code said twice, not a reason, and it must never beat the server's actual last
                // words to being chosen.
                !line.m.startsWith(EXIT_SUMMARY_PREFIX) &&
                    (line.l == ConsoleLevel.ERROR.name || CRASH_MARKERS.any { line.m.contains(it) })
            }
            ?.m
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(CRASH_REASON_LENGTH)

        /**
         * Words that mark a line as the failure even when the process wrote it to stdout.
         *
         * The JVM's own start-up refusals are on the list by name because HotSpot prints them to
         * stdout, in prose, without a colon after "Error": a bad `-Xmx` or an unknown flag is the
         * most ordinary way for a freshly edited startup setting to kill a server.
         */
        private val CRASH_MARKERS = listOf(
            "Exception",
            "Error:",
            "error:",
            "Error occurred during initialization",
            "Could not create the Java Virtual Machine",
            "Unrecognized option",
            "Unrecognized VM option",
            "Invalid maximum heap size",
            "Invalid initial heap size",
            "Could not reserve enough space",
            "Too small maximum heap"
        )

        /** How the node's own exit summary lines begin (see `ServerProcess`). */
        private const val EXIT_SUMMARY_PREFIX = "Server process exited with code"

        /**
         * Why a server that never finished starting gave up.
         *
         * The *last* such line, which is the opposite of [crashReason] and for the opposite
         * reason: a startup failure ends with the explanation rather than beginning with it, and
         * "Failed to bind to port" over a dozen frames of Netty is less use than the
         * `Caused by: java.net.BindException: Address already in use` underneath them.
         */
        fun startupFailureReason(lines: List<ConsoleLine>): String? = lines
            .lastOrNull { line ->
                line.l == ConsoleLevel.ERROR.name || STARTUP_FAILURE_MARKERS.any { line.m.contains(it) }
            }
            ?.m
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(CRASH_REASON_LENGTH)

        /** `Caused by` on top of [CRASH_MARKERS]: it is the line a bind failure ends on. */
        private val STARTUP_FAILURE_MARKERS = listOf("Caused by", "Exception", "Error:", "error:")

        private const val SELF_UPDATE_GRACE_MILLIS = 1_000L

        /** The longest an agent's first start waits for Pano's plugin (see `onAdoptedInPlace`). */
        const val FIRST_START_PLUGIN_WAIT_MILLIS = 20_000L

        /** Who an automatic start is credited to in the console, as `ServerRegistry` does. */
        private const val AUTO_START_ISSUER = "auto-start"

        private val gson = Gson()
    }
}
