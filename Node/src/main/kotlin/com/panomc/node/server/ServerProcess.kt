package com.panomc.node.server

import com.panomc.node.console.ConsoleBuffer
import com.panomc.node.console.ConsoleFileTailer
import com.panomc.node.console.ConsoleLevel
import com.panomc.node.console.ConsoleLineParser
import com.panomc.node.console.ConsoleLogWriter
import com.panomc.node.console.ServerLogFollower
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.host.ProcessMetrics
import com.panomc.node.java.JavaDownloads
import com.panomc.node.java.JavaNeed
import com.panomc.node.util.NodeLogger
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the daemon wants to hear about one supervised server. */
interface ServerProcessListener {
    /** Every state change, which is exactly what `SERVER_STATE` carries. */
    fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long)

    /** A hint that this server has a full batch waiting, so the flush need not wait for the tick. */
    fun onConsoleReady(server: ServerProcess)
}

/**
 * One managed Minecraft server and the process behind it.
 *
 * The supervisor is deliberately boring: a JVM is launched with an argument list (never a shell,
 * so a jvm arg or a server name can never become a command), its pipes are read by two threads
 * that only ever enqueue, and every state change is announced. Everything that could block --
 * waiting thirty seconds for a stop, reading a pipe, waiting for an exit -- happens on threads
 * belonging to this class, because the only other thread available is the platform connection's
 * event loop and stalling that would take every other server's console down with it.
 *
 * A server counts as RUNNING when its log prints the line Minecraft writes once it has finished
 * loading, and after a minute regardless: a modded server can take that long, and a node that
 * never left STARTING would leave the panel unable to offer a stop button.
 */
class ServerProcess(
    val uuid: String,
    val directory: File,
    initialSpec: ServerSpec,
    private val javaLocator: JavaRuntimeLocator,
    private val runtime: ServerRuntime,
    private val scheduler: ScheduledExecutorService,
    private val logger: NodeLogger,
    private val listener: ServerProcessListener,
    /**
     * Downloads a Java this host lacks when a start needs it (SM-63, §2.4.28). Null in tests and
     * wherever nothing may be downloaded, which is the behaviour from before: refuse the start.
     */
    private val javaDownloads: JavaDownloads? = null
) {
    val console = ConsoleBuffer()

    /**
     * Gets every console line as it happens, for a Pano Agent's worker to print into the terminal
     * the agent runs in (SM-74): the server's own output raw, and the node's lines with an agent
     * prefix. Null everywhere else. Set only after the daemon has re-attached, so what a server
     * printed before this worker started is never replayed into the terminal.
     */
    @Volatile
    var consoleTap: ((String) -> Unit)? = null

    /**
     * Why the server is in its current state, when the node knows better than the console does.
     *
     * Only set for a start the node itself refused -- no usable Java, and none could be
     * downloaded -- and carried on the SERVER_STATE that announces it (`reason`, `reasonCode`,
     * `javaMajor`), so the panel can offer "Download Java N and start" instead of a log to read.
     * Cleared by the next start.
     */
    data class StateReason(val message: String, val code: String? = null, val javaMajor: Int? = null)

    @Volatile
    var stateReason: StateReason? = null
        private set

    /**
     * The Java home the running process was started from (SM-63), which is what makes a runtime
     * "in use" and therefore not removable. Only for the process runtime: a Docker server runs the
     * Java inside its image and depends on nothing on the host.
     */
    @Volatile
    private var launchedJavaHome: String? = null

    /**
     * Set by a stop or kill that arrives while a start is still waiting for a Java download, which
     * is the one stretch of a start the lifecycle thread is busy for minutes and a queued stop
     * would otherwise have to sit out.
     */
    @Volatile
    private var startCancelled = false

    /**
     * The coloured copy of this console on disk (§2.4.21 B), which is what history is read back
     * from once a server has run under this node. Fed from the same three places the ring is:
     * the process's output, the node's own lines, and an adopted server's log.
     */
    private val consoleLog = ConsoleLogWriter(directory, logger)

    val metrics = ProcessMetrics()

    /** The runtime this server is run by, so the daemon can sample it the right way. */
    val serverRuntime: ServerRuntime get() = runtime

    @Volatile
    var spec: ServerSpec = initialSpec.sanitised()
        private set

    @Volatile
    var state: ServerProcessState = ServerProcessState.STOPPED
        private set

    @Volatile
    var lastExitCode: Int? = null
        private set

    /**
     * Set when this daemon booked an exit that happened while no daemon was running (SM-62): the
     * state change it made went nowhere, since no socket existed yet, so the hello follows up with
     * an ordinary SERVER_STATE carrying the code, exactly like a watched exit.
     */
    @Volatile
    var exitBookedWhileAway: Boolean = false

    @Volatile
    var since: Long = System.currentTimeMillis()
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdin: BufferedWriter? = null

    /**
     * The process this daemon inherited from the one before it, when there is one (SM-51).
     *
     * Mutually exclusive with [process]: either this daemon started the server and holds its
     * pipes, or it found it already running and holds nothing but a handle.
     */
    @Volatile
    private var adoptedProcess: AdoptedProcess? = null

    /** The log tail that stands in for the pipes of an adopted process. */
    @Volatile
    private var follower: ServerLogFollower? = null

    /**
     * The server running under the launcher, when this daemon holds one (SM-62, §2.4.27).
     *
     * Mutually exclusive with [process] and [adoptedProcess]: the process runtime starts every
     * server this way where it can, and a daemon that finds one already running takes it back into
     * this same field with nothing lost.
     */
    @Volatile
    private var detached: DetachedServer? = null

    /**
     * What `process.json` says right now. Rewritten as the output offset moves, so the next daemon
     * resumes the console at the right byte; written and deleted only under [recordLock], so a late
     * offset write can never bring back the record of a server whose exit was already booked.
     */
    @Volatile
    private var record: ProcessRecord? = null

    private val recordLock = Any()

    /** Epoch nanos of the last output line seen: where a Docker re-attach's `--since` starts. */
    @Volatile
    private var lastLineNanos: Long = 0L

    @Volatile
    private var persistTimer: ScheduledFuture<*>? = null

    /**
     * Whether this server's process was inherited rather than started here.
     *
     * Reported to Pano on every state change and in the hello, because it is the difference
     * between a console the panel can type into and one it cannot, and the panel says so.
     */
    @Volatile
    var adopted: Boolean = false
        private set

    /**
     * Whether a console command can still be written to this server.
     *
     * False for exactly one reason: the stdin of an adopted process belongs to a daemon that no
     * longer exists. Pano routes around it through the Minecraft plugin where the server has the
     * `commands` capability, and refuses the command outright where it does not.
     */
    @Volatile
    var stdinAvailable: Boolean = true
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var readyTimer: ScheduledFuture<*>? = null

    @Volatile
    private var restartTimer: ScheduledFuture<*>? = null

    @Volatile
    private var crashAttempts = 0

    @Volatile
    private var startedAt = 0L

    /**
     * When the process that is running now was started, epoch ms, or null when there is none.
     *
     * The spawn instant for a process this daemon started and the recorded one for an adopted
     * process, which is what the panel's uptime is counted from (SM-57's Uptime tile): a managed
     * server with no Pano plugin has no other start time anywhere.
     */
    val processStartedAt: Long?
        get() = startedAt.takeIf { it > 0L && state.isAlive }

    // Whether this run ever got as far as RUNNING. A server that exits before it does has not
    // stopped, whatever its status code claims, and that is the difference between the panel
    // saying "stopped" and the panel saying why nobody can join.
    @Volatile
    private var reachedRunning = false

    /**
     * Whether the last exit was a start that never finished.
     *
     * Read by the daemon to pick the line that explains it: a startup that died says why at the
     * *end* of its output, under a `Caused by`, whereas a server that fell over while running
     * says it at the top of the unwind.
     */
    @Volatile
    var startupFailed = false
        private set

    private val consoleSignalled = AtomicBoolean(false)

    // One thread per server, so a stop that waits its full forty seconds delays only that server.
    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pano-node-server-$uuid").apply { isDaemon = true }
    }

    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()
        ?: detached?.takeIf { it.isAlive() }?.let { it.javaPid ?: it.launcherPid }
        ?: adoptedProcess?.takeIf { it.isAlive() }?.pid

    /**
     * The Java home this server's live process runs from, or null when it is not running or that
     * cannot be known. Falls back to the process's own executable for a server adopted from a
     * record older than SM-63.
     */
    val javaHome: String?
        get() {
            if (!state.isAlive || runtime.id != ProcessRuntime.ID) {
                return null
            }

            launchedJavaHome?.let { return it }

            val command = pid?.let { ProcessHandle.of(it).orElse(null) }?.info()?.command()?.orElse(null)
                ?: return null

            val executable = File(command)

            if (!executable.name.startsWith("java")) {
                return null
            }

            return try {
                executable.canonicalFile.parentFile?.parentFile?.path
            } catch (_: Exception) {
                null
            }
        }

    fun updateSpec(updated: ServerSpec) {
        spec = updated.sanitised()
    }

    /**
     * Takes over a server that was already running when this daemon started (SM-51, §2.4.16).
     *
     * Everything the supervisor normally learns by watching a process start is taken from the
     * record instead: it is RUNNING, it has been since [ProcessRecord.startedAt], and it never has
     * to pass through STARTING because it finished booting under the previous daemon. What it does
     * not get is pipes, so the console comes from the log file and stdin is gone until somebody
     * restarts the server from the panel -- which is the one thing this state is honest about
     * rather than quietly failing at.
     */
    fun adopt(process: AdoptedProcess, record: ProcessRecord) {
        adoptedProcess = process

        adopted = true
        stdinAvailable = false

        stopRequested = false
        reachedRunning = true
        startupFailed = false
        startedAt = record.startedAt
        launchedJavaHome = record.javaHome

        metrics.reset()

        emit(
            ConsoleLevel.INFO,
            "Pano adopted this server's process (pid ${process.pid ?: record.pid}) after a node restart. " +
                "Console input is unavailable until the server is restarted from the panel."
        )

        // Since the process actually started, not since this daemon noticed it: the panel shows
        // an uptime, and a server that has been up for a week must not read as a minute old.
        setState(ServerProcessState.RUNNING, exitCode = null, at = record.startedAt)

        startFollower()

        // The exit may reach this from the JDK's own reaper thread, so it is handed to the
        // lifecycle executor like every other transition -- and swallowed once that executor is
        // gone, which is what a daemon on its way out looks like from here.
        process.onExit {
            try {
                lifecycle.execute { onAdoptedExit() }
            } catch (_: Exception) {
            }
        }
    }

    /** Starts the server unless it is already doing something. Returns immediately. */
    fun start(issuedBy: String? = null) {
        startCancelled = false

        lifecycle.execute {
            try {
                doStart(issuedBy)
            } catch (exception: Exception) {
                logger.error("Failed to start server $uuid: ${exception.message}", exception)

                emit(ConsoleLevel.ERROR, "Pano could not start this server: ${exception.message}")

                setState(ServerProcessState.CRASHED, exitCode = null)
            }
        }
    }

    /** Asks the server to stop, escalating until it is actually gone. Returns immediately. */
    fun stop(issuedBy: String? = null) {
        startCancelled = true

        lifecycle.execute { doStop(issuedBy, force = false) }
    }

    /** Ends the process now, with no chance to save. */
    fun kill(issuedBy: String? = null) {
        startCancelled = true

        lifecycle.execute { doStop(issuedBy, force = true) }
    }

    fun restart(issuedBy: String? = null) {
        startCancelled = false

        lifecycle.execute {
            doStop(issuedBy, force = false)
            // The stop returns the moment the process is gone, but the exit is booked by another
            // thread — it drains the last output, then moves the state to STOPPED. Starting before
            // that lands found the server still STOPPING, and the start was silently refused: a
            // restart that only stopped. Wait for the booking, then start as a separate step, so
            // an adopted server's exit (queued on this same executor) is handled first too.
            awaitExitBooked()
            lifecycle.execute { doStart(issuedBy) }
        }
    }

    /** Waits, bounded, until the exit of the process this daemon was watching has been recorded. */
    private fun awaitExitBooked() {
        val deadline = System.currentTimeMillis() + PUMP_DRAIN_MILLIS + EXIT_BOOKING_GRACE_MILLIS

        while ((process != null || detached != null) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(EXIT_BOOKING_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()

                return
            }
        }
    }

    /** Stops without announcing a restart, used when the daemon itself is going away. */
    fun shutdown() {
        cancelTimers()

        stopFollower()

        lifecycle.execute { doStop(null, force = false) }

        lifecycle.shutdown()

        try {
            lifecycle.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Lets go of the server without touching it, which is how a daemon exits by default (SM-51).
     *
     * The ownership record is deliberately left on disk and the process is deliberately left
     * alone: a node that is restarting, updating itself or being restarted by an operator has no
     * business closing down a server full of players, and the record is what the next daemon
     * reads to take it back. Nothing here writes to stdin either -- the pipe dies with this JVM,
     * and a `stop` on the way out would be exactly the behaviour this replaces.
     */
    fun detach() {
        cancelTimers()

        stopFollower()

        // The launcher keeps the FIFO open and keeps appending to console.out; all this daemon
        // leaves behind is the byte it had read up to, which is exactly where the next one starts.
        detached?.let { server ->
            server.tailer.stop()
            server.closeWriter()
        }

        cancelPersistTimer()

        persistRecord()

        lifecycle.shutdown()
    }

    /**
     * Takes back a server that was started to be re-attached to (SM-62, §2.4.27).
     *
     * Unlike [adopt], nothing is lost: input, output and the exit all come back, the server is
     * reported as an ordinary one (`adopted = false`, `stdinAvailable = true`), and a crash is
     * booked and restarted exactly as if this daemon had started it. A server that exited while no
     * daemon was running is booked now, from what it left behind.
     */
    fun reattach(reattachment: Reattachment, found: ProcessRecord) {
        when (reattachment) {
            is Reattachment.Detached -> resumeDetached(reattachment, found)
            is Reattachment.Container -> resumeContainer(reattachment, found)
            is Reattachment.Exited -> bookExitWhileAway(reattachment, found)
        }
    }

    private fun resumeFlags(found: ProcessRecord) {
        launchedJavaHome = found.javaHome
        adopted = false
        stdinAvailable = true

        stopRequested = false
        reachedRunning = true
        startupFailed = false
        startedAt = found.startedAt

        metrics.reset()
    }

    private fun resumeDetached(reattachment: Reattachment.Detached, found: ProcessRecord) {
        val server = DetachedServer(
            reattachment.files,
            reattachment.launcher,
            reattachment.java,
            newTailer(reattachment.files, reattachment.outOffset)
        )

        detached = server
        record = found

        resumeFlags(found)

        // Whatever it printed while no daemon was reading, first — then the line saying so.
        server.tailer.poll()

        emit(ConsoleLevel.INFO, "Pano re-attached to this server (pid ${server.javaPid}) after a node restart.")

        // Since the process actually started, not since this daemon noticed it: the panel's uptime.
        setState(ServerProcessState.RUNNING, exitCode = null, at = found.startedAt)

        server.tailer.start("pano-node-out-$uuid")

        startPersistTimer()

        Thread({ awaitDetachedExit(server) }, "pano-node-wait-$uuid").apply { isDaemon = true }.start()
    }

    private fun resumeContainer(reattachment: Reattachment.Container, found: ProcessRecord) {
        val attach = reattachment.attach

        process = attach
        stdin = BufferedWriter(OutputStreamWriter(attach.outputStream, Charsets.UTF_8))
        record = found
        lastLineNanos = reattachment.sinceNanos

        resumeFlags(found)

        emit(ConsoleLevel.INFO, "Pano re-attached to this server's container after a node restart.")

        setState(ServerProcessState.RUNNING, exitCode = null, at = found.startedAt)

        pump(reattachment.logs.inputStream, ConsoleLevel.INFO, "out", reattachment.sinceNanos)
        pump(reattachment.logs.errorStream, ConsoleLevel.ERROR, "err", reattachment.sinceNanos)

        startPersistTimer()

        Thread({
            awaitExit(attach)

            reattachment.logs.destroy()
        }, "pano-node-wait-$uuid").apply { isDaemon = true }.start()
    }

    private fun bookExitWhileAway(reattachment: Reattachment.Exited, found: ProcessRecord) {
        startedAt = found.startedAt
        reachedRunning = true
        stopRequested = false

        reattachment.replay.forEach { onOutputLine(it, ConsoleLevel.INFO) }

        emit(ConsoleLevel.INFO, "Pano found this server stopped after a node restart; it exited while the node was down.")

        ExitFile.clear(DetachedFiles(directory).exit)

        record = found

        bookExit(reattachment.exitCode)

        exitBookedWhileAway = true
    }

    /**
     * Writes one line to the server's stdin and echoes it into the console.
     *
     * The echo is what makes a shared console honest: every viewer sees who typed what, in order,
     * even though the server itself has no idea a panel exists.
     */
    fun sendCommand(command: String, issuedBy: String?): Boolean {
        // An adopted process has no stdin to write to, and pretending otherwise would echo a
        // command into the console that the server never received.
        if (!stdinAvailable) {
            return false
        }

        val cleaned = clean(command) ?: return false

        if (!writeLine(cleaned)) {
            return false
        }

        // The terminal already shows what was typed there; printing the echo back into it would
        // show every command twice. Pano's console and its history still get it.
        emit(ConsoleLevel.INFO, "[Pano:${issuedBy ?: "system"}] > $cleaned", tap = issuedBy != TERMINAL_ISSUER)

        return true
    }

    /**
     * Writes a line the node made up itself, with no echo.
     *
     * The graceful stop goes through here rather than [sendCommand]. A power action has already
     * announced itself as `[Pano:<user>] > stop` by the time the daemon writes that same word to
     * stdin, and echoing it again printed a second `> stop` credited to `system` -- which read as
     * though something other than the operator's click had asked for it.
     */
    private fun writeInternal(command: String): Boolean {
        val cleaned = clean(command) ?: return false

        return writeLine(cleaned)
    }

    /** A console line with its newlines taken out, or null when nothing is left of it. */
    private fun clean(command: String): String? =
        command.replace("\r", "").replace("\n", " ").trim().takeIf { it.isNotEmpty() }

    private fun writeLine(cleaned: String): Boolean {
        detached?.let { server ->
            val written = server.writeLine(cleaned)

            if (!written) {
                logger.warn("Could not write to server $uuid stdin (FIFO).")
            }

            return written
        }

        val writer = stdin ?: return false

        return try {
            synchronized(writer) {
                writer.write(cleaned)
                writer.write("\n")
                writer.flush()
            }

            true
        } catch (exception: Exception) {
            logger.warn("Could not write to server $uuid stdin: ${exception.message}")

            false
        }
    }

    /**
     * This server's CPU and memory right now, or null when it is not running.
     *
     * Asked of the runtime rather than of the process handle, because those are the same thing
     * only for [ProcessRuntime].
     */
    fun sample(): ServerRuntime.Sample? {
        adoptedProcess?.let { adoptedNow ->
            return if (adoptedNow.isAlive()) adoptedNow.sample(metrics) else null
        }

        // The JVM itself, never the launcher: the shell uses no CPU and holds no world.
        detached?.let { server ->
            val java = server.java?.takeIf { it.isAlive } ?: return null

            return ServerRuntime.Sample(metrics.cpuPercent(java), ProcessMetrics.residentBytes(java.pid()))
        }

        val running = process?.takeIf { it.isAlive } ?: return null

        return runtime.sample(uuid, running, metrics)
    }

    /**
     * Adds a line produced by the node itself, so it interleaves with the server's own output.
     * [tap] false keeps it out of [consoleTap] only.
     */
    fun emit(level: ConsoleLevel, message: String, tap: Boolean = true) {
        val now = System.currentTimeMillis()
        val styled = ConsoleLineParser.styled(message)

        console.add(ConsoleLineParser.toLine(message, now, level, styled))

        if (tap) {
            consoleTap?.invoke(if (message.startsWith(PANO_PREFIX)) message else "$AGENT_LINE_PREFIX $message")
        }

        // The command echo `[Pano:user] > …` is one of these, and belongs in the history as much
        // as the server's answer to it does.
        consoleLog.append(now, styled.fileForm)

        signalConsole()
    }

    private fun doStart(issuedBy: String?) {
        cancelRestartTimer()

        if (ServerStateMachine.onStartRequested(state) == null) {
            return
        }

        val jar = File(directory, spec.jar)

        if (!jar.isFile) {
            throw IllegalStateException("Server jar ${spec.jar} is missing.")
        }

        // Read before anything is chosen, because it is the only statement of what this jar needs
        // that cannot be wrong: a proxy's version number says nothing about its class files, and
        // starting a Java 25 build on Java 21 is a crash loop rather than a failure.
        val required = JarJavaRequirement.of(jar)

        val automatic = spec.javaMajor <= ServerSpec.AUTO_JAVA_MAJOR

        stateReason = null

        // The major this server should run on (SM-63): pinned, or the ladder's minimum, raised to
        // the jar's floor. When exactly that is missing and downloads are on, it is fetched first,
        // as its own JAVA_INSTALL task, with the server showing STARTING meanwhile.
        val needed = JavaNeed.neededMajor(if (automatic) null else spec.javaMajor, spec.version, required)

        var downloadError: String? = null

        val downloads = javaDownloads

        val decision = JavaNeed.decide(
            needed,
            javaLocator.discover().map { it.major },
            downloads?.enabled == true
        )

        if (decision == JavaNeed.Decision.DOWNLOAD && downloads != null) {
            setState(ServerProcessState.STARTING, exitCode = null)

            emit(ConsoleLevel.INFO, "Java $needed is not installed on this host; Pano is downloading it before the start.")

            downloadError = downloads.installForStart(uuid, needed)

            if (downloadError != null) {
                emit(ConsoleLevel.WARN, "Pano could not download Java $needed: $downloadError")
            }

            if (startCancelled) {
                emit(ConsoleLevel.INFO, "The start was cancelled while Java $needed was downloading.")

                setState(ServerProcessState.STOPPED, exitCode = null)

                return
            }
        }

        val choice = if (automatic) javaLocator.chooseFor(spec.version, required) else null

        // With downloads on, the major that was just fetched is the one to run: the ladder alone
        // would still prefer, say, an installed 26 for a server that asks for 25 only when 25 is
        // missing, and it no longer is.
        val javaRuntime = javaLocator.exact(needed)?.takeIf { decision == JavaNeed.Decision.DOWNLOAD }
            ?: choice?.runtime
            ?: if (automatic) null else javaLocator.resolve(maxOf(spec.javaMajor, required ?: 0))

        if (javaRuntime == null || (required != null && javaRuntime.major < required)) {
            refuseForJava(required, automatic, needed, downloads?.enabled == true, downloadError)

            return
        }

        logger.info(
            "Starting server $uuid (${spec.name}) with Java ${javaRuntime.major} from ${javaRuntime.path}" +
                (choice?.let { " -- ${it.reason}." } ?: ".") +
                (if (runtime.id != "PROCESS") " Runtime: ${runtime.id}." else "")
        )

        stopRequested = false
        reachedRunning = false
        startupFailed = false

        launchedJavaHome = if (runtime.id == ProcessRuntime.ID) javaRuntime.path else null

        // A start is how an adopted server gets its pipes back: whatever was inherited is gone by
        // now (a start is only accepted from STOPPED or CRASHED), and this run is an ordinary one.
        releaseAdoption()

        val request = ServerRuntime.LaunchRequest(
            uuid = uuid,
            directory = directory,
            spec = spec,
            javaPath = javaLocator.launcher(javaRuntime).absolutePath,
            javaMajor = javaRuntime.major,
            jarName = jar.name,
            onProgress = { percent, message -> emit(ConsoleLevel.INFO, "Pano: $message ($percent%)") }
        )

        // Through the launcher wherever the runtime can (SM-62): the server then survives this
        // daemon with its console intact. Where it cannot, the old way, which is no worse than before.
        val detachedLaunch = try {
            runtime.launchDetached(request)
        } catch (exception: Exception) {
            logger.warn("Could not start server $uuid detached, starting it attached: ${exception.message}")

            null
        }

        if (detachedLaunch != null) {
            startDetached(detachedLaunch, jar.name, issuedBy)

            return
        }

        val started = runtime.launch(request)

        process = started
        stdin = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        startedAt = startInstantOf(started) ?: System.currentTimeMillis()

        // Written before anything else can go wrong, because this file is what makes the process
        // findable again if this daemon is killed a second from now.
        writeRecord(started, jar.name)

        metrics.reset()

        // Announced even when a Java download already put the server in STARTING: that frame had
        // no pid, and this one is the first that can carry it.
        setState(ServerProcessState.STARTING, exitCode = null, force = true)

        issuedBy?.let { emit(ConsoleLevel.INFO, "[Pano:$it] > start") }

        pump(started.inputStream, ConsoleLevel.INFO, "out")
        pump(started.errorStream, ConsoleLevel.ERROR, "err")

        readyTimer = scheduler.schedule({ markReady() }, READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        Thread({ awaitExit(started) }, "pano-node-wait-$uuid").apply { isDaemon = true }.start()
    }

    /** The rest of a start once the launcher is running (SM-62). */
    private fun startDetached(launch: DetachedLaunch, jarName: String, issuedBy: String?) {
        val launcher = launch.launcher.toHandle()
        val serverHandle = launch.java ?: launcher

        val server = DetachedServer(launch.files, launcher, launch.java, newTailer(launch.files, 0L))

        detached = server

        startedAt = try {
            ProcessAdoption.startedAtOf(serverHandle)
        } catch (_: Exception) {
            null
        } ?: System.currentTimeMillis()

        // Written before anything else can go wrong: this file is what the next daemon re-attaches by.
        writeRecord(
            ProcessRecord(
                pid = serverHandle.pid(),
                startedAt = startedAt,
                command = jarName,
                runtime = runtime.id,
                io = ProcessRecord.IO_DETACHED,
                launcherPid = launcher.pid(),
                javaPid = launch.java?.pid(),
                outOffset = 0L,
                javaHome = launchedJavaHome
            )
        )

        metrics.reset()

        setState(ServerProcessState.STARTING, exitCode = null, force = true)

        issuedBy?.let { emit(ConsoleLevel.INFO, "[Pano:$it] > start") }

        server.tailer.start("pano-node-out-$uuid")

        startPersistTimer()

        readyTimer = scheduler.schedule({ markReady() }, READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        Thread({ awaitDetachedExit(server) }, "pano-node-wait-$uuid").apply { isDaemon = true }.start()
    }

    /** Waits for a launcher-run server to be gone, then books its exit like any other. */
    private fun awaitDetachedExit(server: DetachedServer) {
        val exitCode = server.awaitExit()

        if (detached !== server) {
            return
        }

        // The last lines first, so the crash reason is in the buffer before the state changes.
        server.tailer.stop()

        try {
            server.tailer.drain()
        } catch (_: Exception) {
        }

        server.closeWriter()

        cancelReadyTimer()

        detached = null

        ExitFile.clear(server.files.exit)

        bookExit(exitCode)
    }

    /** A tailer that feeds [onOutputLine] exactly what a pipe reader used to. */
    private fun newTailer(files: DetachedFiles, offset: Long) =
        ConsoleFileTailer(files.output, files.rotatedOutput, offset) { lines ->
            lines.forEach { onOutputLine(it, ConsoleLevel.INFO) }
        }

    /**
     * Ends a start that no installed runtime can serve, without pretending it was a crash to retry.
     *
     * STOPPED with a reason (SM-63, §2.4.28), not CRASHED: a missing Java is not something that
     * fixes itself in thirty seconds, nothing schedules a restart from here, and a crash would page
     * an operator about a server that never ran. The reason travels on the SERVER_STATE with
     * `reasonCode: JAVA_MISSING` and the major, which is what lets the panel offer "Download Java N
     * and start"; the console line says the same thing with what this host actually has.
     */
    private fun refuseForJava(
        required: Int?,
        automatic: Boolean,
        needed: Int,
        autoDownload: Boolean,
        downloadError: String?
    ) {
        val installed = javaLocator.discover().map { it.major }.distinct().sorted()

        val detail = when {
            required != null ->
                "Java $required or newer is required by ${spec.jar}; installed: $installed"

            automatic ->
                "No Java runtime on this host can run ${spec.software} ${spec.version} " +
                    "(it needs ${MinecraftJavaVersions.minimumFor(spec.version)} or newer); installed: $installed"

            else -> "No Java ${spec.javaMajor} runtime found on this host; installed: $installed"
        }

        val reason = JavaNeed.missingReason(needed, autoDownload, downloadError)

        logger.warn("Refusing to start server $uuid: $reason. $detail.")

        emit(ConsoleLevel.ERROR, detail)
        emit(ConsoleLevel.ERROR, reason)

        stateReason = StateReason(reason, REASON_JAVA_MISSING, needed)

        // Forced, because a start refused straight from STOPPED is still news: Pano asked for a
        // start and has to hear, with the reason, that it did not happen.
        setState(ServerProcessState.STOPPED, exitCode = null, force = true)
    }

    private fun doStop(issuedBy: String?, force: Boolean) {
        cancelRestartTimer()

        adoptedProcess?.let { adoptedNow ->
            stopAdopted(adoptedNow, issuedBy, force)

            return
        }

        detached?.let { server ->
            stopDetached(server, issuedBy, force)

            return
        }

        val running = process

        if (running == null || !running.isAlive) {
            return
        }

        stopRequested = true

        setState(ServerProcessState.STOPPING, exitCode = null)

        issuedBy?.let { emit(ConsoleLevel.INFO, "[Pano:$it] > ${if (force) "kill" else spec.stopCommand}") }

        if (force) {
            runtime.kill(uuid, running)

            running.waitFor(FORCE_WAIT_SECONDS, TimeUnit.SECONDS)

            return
        }

        // The console command first: it is the only shutdown a Minecraft server treats as clean,
        // saving its worlds on the way out. Escalation comes after, and what it means is the
        // runtime's business -- a signal to a JVM, a `docker stop` to a container.
        writeInternal(spec.stopCommand)

        if (running.waitFor(GRACEFUL_WAIT_SECONDS, TimeUnit.SECONDS)) {
            return
        }

        logger.warn("Server $uuid ignored \"${spec.stopCommand}\", terminating.")

        if (runtime.terminate(uuid, running, TERMINATE_WAIT_SECONDS)) {
            return
        }

        logger.warn("Server $uuid ignored termination, killing.")

        runtime.kill(uuid, running)

        running.waitFor(FORCE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Stops a launcher-run server: the console `stop` first, then SIGTERM, then SIGKILL — the same
     * escalation as for a piped process, with the JVM's pid as the target. The exit itself is
     * booked by the thread watching the launcher, as it is for a piped process.
     */
    private fun stopDetached(server: DetachedServer, issuedBy: String?, force: Boolean) {
        if (!server.isAlive()) {
            return
        }

        stopRequested = true

        setState(ServerProcessState.STOPPING, exitCode = null)

        issuedBy?.let { emit(ConsoleLevel.INFO, "[Pano:$it] > ${if (force) "kill" else spec.stopCommand}") }

        if (force) {
            server.kill()

            server.awaitJavaExit(FORCE_WAIT_SECONDS)

            return
        }

        writeInternal(spec.stopCommand)

        if (server.awaitJavaExit(GRACEFUL_WAIT_SECONDS)) {
            return
        }

        logger.warn("Server $uuid ignored \"${spec.stopCommand}\", terminating.")

        server.terminate()

        if (server.awaitJavaExit(TERMINATE_WAIT_SECONDS)) {
            return
        }

        logger.warn("Server $uuid ignored termination, killing.")

        server.kill()

        server.awaitJavaExit(FORCE_WAIT_SECONDS)
    }

    /**
     * Ends an adopted process, which has no console to be asked politely through (SM-51).
     *
     * SIGTERM is the polite request here, and it is a real one: Paper, Velocity and BungeeCord all
     * install a shutdown hook that saves and closes down exactly as a console `stop` would. The
     * escalation after the same graceful wait is the same as everywhere else.
     */
    private fun stopAdopted(adoptedNow: AdoptedProcess, issuedBy: String?, force: Boolean) {
        if (!adoptedNow.isAlive()) {
            return
        }

        stopRequested = true

        setState(ServerProcessState.STOPPING, exitCode = null)

        issuedBy?.let { emit(ConsoleLevel.INFO, "[Pano:$it] > ${if (force) "kill" else "stop"}") }

        if (force) {
            adoptedNow.kill()

            awaitGone(adoptedNow, FORCE_WAIT_SECONDS)
        } else if (!adoptedNow.terminate(GRACEFUL_WAIT_SECONDS)) {
            logger.warn("Adopted server $uuid ignored termination, killing.")

            adoptedNow.kill()

            awaitGone(adoptedNow, FORCE_WAIT_SECONDS)
        }

        // Reported from here rather than left to the exit callback, because a restart runs the
        // stop and the start back to back on this very thread: a state change queued behind it
        // would land after the start had already been refused for a server that was "stopping".
        onAdoptedExit()
    }

    /** Waits for an adopted process to actually be gone, by asking rather than by waiting on it. */
    private fun awaitGone(adoptedNow: AdoptedProcess, timeoutSeconds: Long) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000

        while (System.currentTimeMillis() < deadline) {
            if (!adoptedNow.isAlive()) {
                return
            }

            try {
                Thread.sleep(GONE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()

                return
            }
        }
    }

    /**
     * Where an adopted process lands when it goes away.
     *
     * No exit code exists: this daemon never held the handle that carries one, so the honest
     * answer is null rather than a zero that would read as a clean shutdown. That is also why
     * nothing is auto-restarted from here -- an exit nobody asked Pano for may equally have been
     * an operator typing `stop` in a terminal somewhere, and bringing the server back up against
     * their wishes is worse than leaving it down.
     */
    private fun onAdoptedExit() {
        val adoptedNow = adoptedProcess ?: return

        if (adoptedNow.isAlive()) {
            return
        }

        // One last read before the state change, so the lines the server wrote on its way out are
        // in the buffer when the daemon goes looking for a reason.
        follower?.drain()

        releaseAdoption()

        lastExitCode = null
        startupFailed = false

        val next = ServerStateMachine.onAdoptedExit(stopRequested)

        if (next == ServerProcessState.CRASHED) {
            emit(ConsoleLevel.ERROR, "Server process exited; Pano was not holding it and has no exit code.")
        } else {
            emit(ConsoleLevel.INFO, "Server process exited.")
        }

        setState(next, exitCode = null)

        deleteRecord()
    }

    /** Forgets an adopted process and gives the next start its pipes back. */
    private fun releaseAdoption() {
        stopFollower()

        adoptedProcess = null
        adopted = false
        stdinAvailable = true
    }

    private fun startFollower() {
        val started = ServerLogFollower(directory) { lines ->
            lines.forEach { line ->
                console.add(line)

                // An adopted server's output reaches the node only through its log file, which
                // carries no colour; it goes into the history as the plain text it is.
                consoleLog.append(line.t, line.m)

                consoleTap?.invoke(line.m)
            }

            if (console.isBatchReady()) {
                signalConsole()
            }
        }

        follower = started

        // Whether there is still an adopted process at all, rather than whether it is alive: for
        // a container the liveness check is a `docker inspect`, and asking twice a second for
        // every adopted server would cost more than the tailing does. The exit path drops the
        // process and stops the follower in the same breath.
        started.start("pano-node-follow-$uuid") { adoptedProcess != null }
    }

    private fun stopFollower() {
        follower?.stop()
        follower = null
    }

    /** The record that makes this run findable after a restart. Never fails a start. */
    private fun writeRecord(started: Process, jarName: String) {
        val docker = runtime.id == DockerRuntime.ID

        writeRecord(
            ProcessRecord(
                pid = started.pid(),
                startedAt = startedAt,
                command = jarName,
                runtime = runtime.id,
                container = if (docker) DockerCommands.containerName(uuid) else null,
                // A container outlives the daemon by itself and can be attached to again, so its
                // record is a re-attach record (SM-62); a piped JVM is the old, limited adoption.
                io = if (docker) ProcessRecord.IO_DETACHED else null,
                logsSince = if (docker) System.currentTimeMillis() * NANOS_PER_MILLI else null,
                javaHome = launchedJavaHome
            )
        )
    }

    private fun writeRecord(written: ProcessRecord) {
        synchronized(recordLock) {
            record = written

            if (!ProcessRecordStore.write(directory, written)) {
                logger.warn("Could not record the process of server $uuid.")
            }
        }
    }

    /**
     * Rewrites `process.json` with where the console has been read up to (SM-62): the output offset
     * of a launcher-run server, the last line's time for a container. Nothing when nothing moved,
     * and never after the record was deleted.
     */
    private fun persistRecord() {
        synchronized(recordLock) {
            val current = record ?: return

            val updated = current.copy(
                outOffset = detached?.tailer?.committedOffset ?: current.outOffset,
                logsSince = if (current.runtime == DockerRuntime.ID) {
                    lastLineNanos.takeIf { it > 0L } ?: current.logsSince
                } else {
                    current.logsSince
                }
            )

            if (updated != current && ProcessRecordStore.write(directory, updated)) {
                record = updated
            }
        }
    }

    private fun deleteRecord() {
        synchronized(recordLock) {
            record = null

            ProcessRecordStore.delete(directory)
        }
    }

    private fun startPersistTimer() {
        cancelPersistTimer()

        persistTimer = scheduler.scheduleWithFixedDelay({
            try {
                persistRecord()
            } catch (_: Exception) {
            }
        }, RECORD_PERSIST_SECONDS, RECORD_PERSIST_SECONDS, TimeUnit.SECONDS)
    }

    private fun cancelPersistTimer() {
        persistTimer?.cancel(false)
        persistTimer = null
    }

    /**
     * When the OS says this process started, which is what the pid-reuse guard compares against.
     *
     * Taken from the handle rather than from the clock so that the number written down and the
     * number read back on the next start come from the same source.
     */
    private fun startInstantOf(started: Process): Long? = try {
        ProcessAdoption.startedAtOf(started.toHandle())
    } catch (_: Exception) {
        null
    }

    private fun awaitExit(started: Process) {
        val exitCode = try {
            started.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()

            return
        }

        if (process !== started) {
            return
        }

        // Let the pipe readers deliver whatever the process printed on its way out, so the crash
        // lines are in the buffer before the state change is reported.
        for (pump in pumps.toList()) {
            try {
                pump.join(PUMP_DRAIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            pumps.remove(pump)
        }

        cancelReadyTimer()

        try {
            stdin?.close()
        } catch (_: Exception) {
        }

        stdin = null
        process = null

        bookExit(exitCode)
    }

    /**
     * Books an exit this daemon watched — or, for a launcher-run server, one that happened while no
     * daemon was running — into the state machine: STOPPED or CRASHED, the console line that
     * explains it, and the crash restart. [exitCode] is null when nothing recorded one.
     */
    private fun bookExit(exitCode: Int?) {
        lastExitCode = exitCode

        cancelPersistTimer()

        // The one place the ownership record is removed: a record that outlives the daemon is the
        // whole point of writing it, and an exit that has been seen is the end of it.
        deleteRecord()

        val requested = stopRequested
        val uptime = System.currentTimeMillis() - startedAt
        val ranLongEnough = uptime > STABLE_RUN_MILLIS
        val next = ServerStateMachine.onExit(exitCode ?: UNKNOWN_EXIT_CODE, requested, reachedRunning, uptime)

        // Zero, unasked for, and it never came up: the classic shape of a server that could not
        // bind its port. Recorded so the daemon knows to look for the reason at the end of the
        // output rather than at the top.
        startupFailed = next == ServerProcessState.CRASHED && exitCode == 0 && !requested

        when {
            // WARN and not ERROR on purpose: this line is the node's own summary and lands last in
            // the buffer, so an ERROR here would beat the server's actual explanation to being
            // picked as the reason.
            startupFailed -> emit(
                ConsoleLevel.WARN,
                "Server process exited with code $exitCode after ${uptime / 1000}s without finishing startup."
            )

            next == ServerProcessState.CRASHED && exitCode == null ->
                emit(ConsoleLevel.ERROR, "Server process exited; nothing recorded its exit code.")

            next == ServerProcessState.CRASHED -> emit(ConsoleLevel.ERROR, "Server process exited with code $exitCode.")

            exitCode == null -> emit(ConsoleLevel.INFO, "Server process exited.")

            else -> emit(ConsoleLevel.INFO, "Server process exited with code $exitCode.")
        }

        setState(next, exitCode)

        if (next != ServerProcessState.CRASHED || !spec.crashRestart) {
            return
        }

        // A server that stayed up for a while and then died is a fresh incident, not the next
        // failure in a crash loop, so the backoff starts over for it.
        crashAttempts = if (ranLongEnough) 1 else crashAttempts + 1

        val delay = ServerStateMachine.restartDelayMillis(crashAttempts)

        logger.warn("Server $uuid crashed (exit $exitCode); restarting in ${delay / 1000}s.")

        emit(ConsoleLevel.WARN, "Pano will restart this server in ${delay / 1000} seconds.")

        restartTimer = scheduler.schedule({ start(null) }, delay, TimeUnit.MILLISECONDS)
    }

    // Kept so awaitExit can wait for the last lines: the process can be reaped before the pipe
    // readers have delivered the stderr that explains a crash, and the crash report would then
    // carry no reason.
    private val pumps = java.util.concurrent.CopyOnWriteArrayList<Thread>()

    /**
     * Reads one output stream line by line into [onOutputLine].
     *
     * [sinceNanos] is set for `docker logs --timestamps` after a re-attach: each line carries
     * Docker's timestamp, which is stripped, and the ones at or before [sinceNanos] were already
     * seen by the daemon before this one.
     */
    private fun pump(stream: InputStream, fallback: ConsoleLevel, name: String, sinceNanos: Long? = null) {
        val thread = Thread({
            try {
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { raw ->
                        if (sinceNanos == null) {
                            onOutputLine(raw, fallback)

                            return@forEach
                        }

                        val (at, text) = DockerCommands.splitTimestamp(raw)

                        if (at != null && at <= sinceNanos) {
                            return@forEach
                        }

                        onOutputLine(text, fallback, at)
                    }
                }
            } catch (_: Exception) {
                // The pipe closes when the process exits; that is the normal end of this thread.
            }
        }, "pano-node-$name-$uuid").apply { isDaemon = true }
        pumps.add(thread)
        thread.start()
    }

    /**
     * One line of the server's own output, from wherever it came — a pipe, `console.out`, `docker
     * logs` — into everything that consumes it: the ring and live frames, the coloured history
     * file, ready detection. [seenAtNanos] is Docker's own timestamp when there is one.
     */
    private fun onOutputLine(raw: String, fallback: ConsoleLevel, seenAtNanos: Long? = null) {
        val now = System.currentTimeMillis()

        lastLineNanos = seenAtNanos ?: (now * NANOS_PER_MILLI)

        // Parsed once for both: the ring gets the plain text and its spans, the file gets the same
        // text with the colour codes that made the spans.
        val styled = ConsoleLineParser.styled(raw)

        console.add(ConsoleLineParser.toLine(raw, now, fallback, styled))

        consoleLog.append(now, styled.fileForm)

        consoleTap?.invoke(raw)

        if (state == ServerProcessState.STARTING && isReadyLine(raw)) {
            markReady()
        }

        if (console.isBatchReady()) {
            signalConsole()
        }
    }

    private fun markReady() {
        cancelReadyTimer()

        if (ServerStateMachine.onReady(state) == null) {
            return
        }

        reachedRunning = true

        setState(ServerProcessState.RUNNING, exitCode = null)
    }

    /** [at] is when the state became true, which for an adopted process is before this daemon. */
    private fun setState(
        next: ServerProcessState,
        exitCode: Int?,
        at: Long = System.currentTimeMillis(),
        force: Boolean = false
    ) {
        if (state == next && !force) {
            return
        }

        if (state == next) {
            // Re-announced, not changed: the time it became true stays what it was.
            listener.onState(this, next, exitCode, pid, since)

            return
        }

        state = next
        since = at

        listener.onState(this, next, exitCode, pid, since)
    }

    private fun signalConsole() {
        if (consoleSignalled.compareAndSet(false, true)) {
            try {
                listener.onConsoleReady(this)
            } finally {
                consoleSignalled.set(false)
            }
        }
    }

    private fun cancelReadyTimer() {
        readyTimer?.cancel(false)
        readyTimer = null
    }

    private fun cancelRestartTimer() {
        restartTimer?.cancel(false)
        restartTimer = null
    }

    private fun cancelTimers() {
        cancelReadyTimer()
        cancelRestartTimer()
    }

    companion object {
        /** `reasonCode` of a start refused because no usable Java was installed or downloadable. */
        const val REASON_JAVA_MISSING = "JAVA_MISSING"

        /**
         * Who a console command typed into a Pano Agent's terminal is credited to (SM-74): the echo
         * in Pano's console and history reads `[Pano:terminal] > list`.
         */
        const val TERMINAL_ISSUER = "terminal"

        /** How the node's own console lines that already name Pano begin (`[Pano:admin] > stop`). */
        private const val PANO_PREFIX = "[Pano"

        /** What the node's other console lines are prefixed with in an agent's terminal. */
        private const val AGENT_LINE_PREFIX = "[Pano Agent]"

        /** Stands in for an exit code nothing recorded: not zero, so it never reads as a clean stop. */
        const val UNKNOWN_EXIT_CODE = -1

        /** How often the console offset is written to `process.json` while a server runs. */
        const val RECORD_PERSIST_SECONDS = 5L

        private const val NANOS_PER_MILLI = 1_000_000L

        private const val PUMP_DRAIN_MILLIS = 1_500L
        private const val EXIT_BOOKING_GRACE_MILLIS = 5_000L
        private const val EXIT_BOOKING_POLL_MILLIS = 50L

        /** How often an adopted process is asked whether a stop has taken effect yet. */
        private const val GONE_POLL_MILLIS = 200L

        /** What a Minecraft server prints once it has finished loading. */
        const val READY_MARKER = "Done ("

        /**
         * The lines that mean "up": the game servers and Velocity print `Done (…)!`, BungeeCord and
         * Waterfall print `Listening on /0.0.0.0:25577` and nothing else -- a proxy waited on the
         * first marker alone sat in STARTING until the timeout let it through.
         */
        val READY_MARKERS = listOf(READY_MARKER, "Listening on ")

        fun isReadyLine(line: String): Boolean = READY_MARKERS.any { line.contains(it) }

        /** How long a server may take to print that line before it is called RUNNING anyway. */
        const val READY_TIMEOUT_SECONDS = 60L

        const val GRACEFUL_WAIT_SECONDS = 30L
        const val TERMINATE_WAIT_SECONDS = 10L
        const val FORCE_WAIT_SECONDS = 10L
        const val SHUTDOWN_WAIT_SECONDS = 60L

        /** Uptime past which a crash is treated as a new incident rather than a restart loop. */
        const val STABLE_RUN_MILLIS = 60_000L

        /**
         * The launch command line, as a list.
         *
         * Visible for testing because this is where a mistake is expensive: a stray shell, a
         * quoted argument or a missing heap flag all show up here and nowhere else.
         */
        /**
         * Environment variables a server must not inherit from this daemon.
         *
         * A JVM applies `JAVA_TOOL_OPTIONS` to itself and then passes it on to everything it
         * launches, so whatever started the node reaches the Minecraft server too: the operator
         * sees a "Picked up JAVA_TOOL_OPTIONS" banner in their console and the server runs with
         * flags meant for Pano. Pano strips these before starting the node; stripping them again
         * here is what protects a server on a node an operator started by hand. `CLASSPATH` is the
         * same kind of leak, invisible for a `-jar` launch right up until it is not.
         *
         * Locale is deliberately absent. `LANG` and `LC_ALL` decide how a server renders its own
         * log lines, and a console that suddenly speaks C locale is a regression, not a clean-up.
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

        fun buildCommand(javaPath: String, jarName: String, spec: ServerSpec): List<String> {
            val command = mutableListOf(javaPath)

            // The setting is the whole process; the heap gets it minus the JVM's own share (JvmHeap).
            val heapMb = JvmHeap.heapMb(spec.memoryMb)

            command.add("-Xms${heapMb}M")
            command.add("-Xmx${heapMb}M")
            command.addAll(spec.jvmArgs.filter { it.isNotBlank() })
            command.add("-jar")
            command.add(jarName)

            // A proxy has no GUI to suppress and rejects unknown arguments on some builds.
            if (!spec.isProxy) {
                command.add("nogui")
            }

            return command
        }
    }
}
