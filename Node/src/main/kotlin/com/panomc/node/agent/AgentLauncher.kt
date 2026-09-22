package com.panomc.node.agent

import com.panomc.node.NodeCli
import com.panomc.node.NodeOptions
import com.panomc.node.NodeVersion
import com.panomc.node.RetiredMarker
import com.panomc.node.host.HostPlatform
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/**
 * The process an admin starts as `java -jar pano-agent.jar` in their server's folder (SM-74).
 *
 * It does almost nothing itself, on purpose: it starts the **worker** — the same jar, the ordinary
 * node daemon with `--agent-worker` — passes the terminal's lines to it, and starts it again when it
 * has to. The worker is the one that talks to Pano and runs the server, and the server is a third
 * process of its own, so a worker that updates itself or falls over takes neither the terminal nor
 * the Minecraft server with it.
 *
 * Plain JDK only. `Main` hands over to this class before a single Vert.x class is loaded, so the
 * process that sits in the admin's `screen` session or under their hosting panel costs a few
 * megabytes and never needs an update itself: the worker is where the code that changes lives.
 *
 * - The worker's output goes straight to the terminal (the server's console, and the agent's own
 *   `[Pano Agent]` lines); its input is a pipe the terminal's lines are copied into. End of input —
 *   a service started with nothing on stdin — only ends the copying, never anything else.
 * - When the worker exits, [AgentRestartPolicy] decides what happens next.
 * - Ctrl+C or SIGTERM: the worker is asked to stop (it stops the server gracefully first) and is
 *   given two minutes to do it, then this process exits.
 *
 * Its first run is a short dialogue (SM-76, [AgentSetup]): while the folder is not linked yet and
 * somebody can answer, it asks for the Pano address, how the server runs and the pairing code,
 * writes the answers to `.pano-agent/launch.json` and starts the worker with them -- and when Pano
 * refuses the code, asks for a new one instead of exiting. [setupMode] says when it asks.
 *
 * The worker runs from `.pano-agent/worker.jar`, a copy of this jar ([AgentWorkerJar]), so a
 * self-update can replace the worker's jar on every OS, Windows included.
 */
class AgentLauncher(
    private val layout: AgentLayout,
    /** What the admin passed that the worker should see too: `--pano`, `--code`, `--name`, … */
    private val passThrough: List<String>,
    /** The jar the worker is started from; null when running from a classes directory (tests). */
    private val jar: String?,
    private val version: String,
    private val env: (String) -> String? = System::getenv,
    input: InputStream = System.`in`,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    /** The command line and environment as parsed: the pairing credentials and `--no-input`. */
    private val options: NodeOptions = NodeCli.parse(emptyArray(), env),
    /** What stdin is: a terminal, a pipe somebody may type into, or nothing at all. */
    private val stdin: Stdin = Stdin.detect(),
    /** Starts a worker; replaced in tests, which have no worker to start. */
    private val starter: WorkerStarter = WorkerStarter.PROCESS,
    private val checkAddress: (String) -> AgentAddress.Check = { AgentAddress.check(it) },
    /** This launcher's own `-Xmx`, the default for the server's memory. */
    private val launcherMemoryMb: Int? = ownHeapMb(),
    private val windows: Boolean = HostPlatform.isWindows,
    /** Off in tests, which run the launcher inside their own JVM. */
    private val shutdownHook: Boolean = true
) {
    /** What stdin is, as far as asking questions goes. */
    data class Stdin(
        /** A person at a terminal: answers are typed after the question. */
        val terminal: Boolean,
        /** At its end before anything was read (`/dev/null`): nobody can answer. */
        val closed: Boolean
    ) {
        companion object {
            fun detect() = Stdin(terminal = AgentTerminal.isTerminal(), closed = AgentTerminal.stdinIsNull())
        }
    }

    /** How a worker process is started. */
    fun interface WorkerStarter {
        fun start(command: List<String>, environment: Map<String, String>, directory: File): Process

        companion object {
            val PROCESS = WorkerStarter { command, environment, directory ->
                ProcessBuilder(command)
                    .directory(directory)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .apply { environment().putAll(environment) }
                    .start()
            }
        }
    }

    /** What the first run does, see [setupMode]. */
    enum class SetupMode {
        /** Nothing: the folder is linked already, or nobody can answer and nothing was given. */
        NONE,

        /** Ask what was not given, and how the server runs. */
        ASK,

        /** Pairing credentials given and nobody to ask: the defaults, written as they are. */
        DEFAULTS
    }

    private val lock = Any()

    @Volatile
    private var worker: Process? = null

    @Volatile
    private var stopping = false

    private val terminal = AgentTerminal(input)

    private val unicode = AgentTerminal.canPrintUnicode(out)

    private val workerJar: AgentWorkerJar? =
        jar?.let { AgentWorkerJar(layout.dataDir, File(it), windows) { line -> say(line) } }

    /**
     * Runs the agent until it should end, and returns the code to exit with — or null when the JVM
     * is already shutting down (Ctrl+C, SIGTERM), in which case the shutdown hook finishes the job.
     */
    fun run(): Int? {
        if (shutdownHook) {
            Runtime.getRuntime().addShutdownHook(Thread({ onShutdown() }, "pano-agent-shutdown"))
        }

        say("Pano Agent $version for ${layout.serverDir.path}")

        val hasUrl = options.platformUrl != null
        val hasCode = options.pairingCode != null || options.bootstrapToken != null

        val mode = setupMode(
            linked = isLinked(layout.dataDir),
            hasUrl = hasUrl,
            hasCode = hasCode,
            noInput = options.noInput,
            stdinClosed = stdin.closed,
            terminal = stdin.terminal
        )

        val setup = AgentSetup(
            serverDir = layout.serverDir,
            readLine = terminal::readLine,
            out = out,
            terminal = stdin.terminal,
            agentJar = jar?.let { canonical(File(it)) },
            launcherMemoryMb = launcherMemoryMb,
            checkAddress = checkAddress,
            unicode = unicode
        )

        // Before the reader starts, so a line typed while this JVM was starting is an answer.
        terminal.asking = mode == SetupMode.ASK

        terminal.start { line -> forward(line) }

        // The address and code the worker pairs with when the questions supplied them; null leaves
        // the admin's own flags and environment to the worker, as before SM-76.
        var credentials: Pair<String, String>? = null

        when (mode) {
            SetupMode.NONE -> {}

            SetupMode.DEFAULTS -> useDefaults(setup)

            SetupMode.ASK -> {
                val outcome = try {
                    setup.run(options.platformUrl, options.pairingCode)
                } finally {
                    terminal.asking = false
                }

                when (outcome) {
                    is AgentSetup.Outcome.Link -> {
                        AgentLaunch.write(layout.dataDir, outcome.answers.launch)

                        credentials = outcome.answers.panoUrl!! to outcome.answers.code!!
                    }

                    is AgentSetup.Outcome.Quit -> return outcome.exitCode

                    AgentSetup.Outcome.NoInput -> if (hasUrl && hasCode) useDefaults(setup)
                }
            }
        }

        var attempt = 0

        while (true) {
            val startedAt = System.currentTimeMillis()

            val process = synchronized(lock) {
                if (stopping) {
                    return null
                }

                try {
                    start(credentials)
                } catch (exception: Exception) {
                    err.println("$PREFIX Could not start the agent: ${exception.message}")

                    return 1
                }.also { worker = it }
            }

            val exitCode = waitFor(process)

            synchronized(lock) {
                worker = null

                if (stopping) {
                    return null
                }
            }

            // A code Pano did not take, typed by somebody who is still there: ask for a new one
            // instead of ending the agent (SM-76). Anywhere else 77 ends it, as it always has.
            val asked = credentials

            if (exitCode == NodeVersion.NOT_PAIRED_EXIT_CODE && asked != null) {
                terminal.asking = true

                val code = try {
                    setup.askCodeAgain(AgentSetup.codeRejected(takePairingError(), unicode))
                } finally {
                    terminal.asking = false
                }

                if (code == null) {
                    return NodeVersion.NOT_PAIRED_EXIT_CODE
                }

                credentials = asked.first to code

                continue
            }

            if (exitCode == NodeVersion.RETIRED_EXIT_CODE) {
                // The worker left the jar it ran from behind; nothing runs it any more.
                workerJar?.clear()
            }

            when (val decision = AgentRestartPolicy.decide(exitCode, System.currentTimeMillis() - startedAt, attempt)) {
                is AgentRestartPolicy.Decision.Exit -> {
                    decision.message?.let { say(it) }

                    return decision.exitCode
                }

                is AgentRestartPolicy.Decision.Restart -> {
                    attempt = decision.nextAttempt

                    if (decision.delayMillis > 0) {
                        say("The agent stopped unexpectedly (exit $exitCode); starting it again in ${decision.delayMillis / 1000}s.")

                        try {
                            Thread.sleep(decision.delayMillis)
                        } catch (_: InterruptedException) {
                            return null
                        }
                    }
                }
            }
        }
    }

    /**
     * Pairing credentials and nobody to ask: what the questions would have offered is used as it
     * is, and said in one line. An earlier run's answers, when there are any, are kept.
     */
    private fun useDefaults(setup: AgentSetup) {
        if (AgentLaunch.file(layout.dataDir).isFile) {
            return
        }

        val answers = setup.defaults()

        try {
            AgentLaunch.write(layout.dataDir, answers.launch)
        } catch (exception: Exception) {
            err.println("$PREFIX Could not write ${AgentLaunch.file(layout.dataDir).path}: ${exception.message}")

            return
        }

        val from = setup.scriptName()?.let { " (from $it)" } ?: ""
        val args = if (answers.jvmArgs.isEmpty()) "none" else JvmArgs.join(answers.jvmArgs)

        say(
            "Server jar ${answers.jar ?: "(none found)"}, memory ${JvmArgs.formatMemory(answers.memoryMb)}, " +
                "Java arguments $args$from. Change them later in the server's Startup settings in Pano."
        )
    }

    private fun start(credentials: Pair<String, String>?): Process {
        // Written by a worker that could not pair; one from an earlier worker means nothing now.
        File(layout.dataDir, PAIRING_ERROR_FILE).delete()

        val command = workerCommand(
            java = currentJava(),
            jar = workerJar?.prepare()?.path ?: jar,
            classPath = System.getProperty("java.class.path").orEmpty(),
            layout = layout,
            passThrough = credentials?.let { (url, code) -> withCredentials(passThrough, url, code) } ?: passThrough,
            javaOptions = env(ENV_WORKER_JAVA_OPTS)
        )

        val environment = jar?.let { mapOf(AgentFiles.ENV_LAUNCHER_JAR to File(it).absolutePath) } ?: emptyMap()

        return starter.start(command, environment, layout.serverDir)
    }

    /** What the worker wrote about why it could not pair, read once; null when it wrote nothing. */
    private fun takePairingError(): String? {
        val file = File(layout.dataDir, PAIRING_ERROR_FILE)

        return try {
            file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.take(MAX_REASON_LENGTH)?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            file.delete()
        }
    }

    private fun waitFor(process: Process): Int {
        while (true) {
            try {
                return process.waitFor()
            } catch (_: InterruptedException) {
                // Nothing interrupts this thread on purpose; keep waiting for the worker.
            }
        }
    }

    /**
     * Ctrl+C or SIGTERM. The worker usually got the same signal already (it shares the terminal's
     * process group); [Process.destroy] makes sure of it, and the worker's own hook stops the server
     * gracefully before it exits.
     */
    private fun onShutdown() {
        val current = synchronized(lock) {
            stopping = true

            worker
        } ?: return

        current.destroy()

        try {
            if (!current.waitFor(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                err.println("$PREFIX The agent did not stop within $SHUTDOWN_WAIT_SECONDS seconds; leaving it.")
            }
        } catch (_: InterruptedException) {
        }
    }

    /** One line typed into the terminal, for whichever worker is running; lost when none is. */
    private fun forward(line: String) {
        val target = worker ?: return

        try {
            val stdin = target.outputStream

            synchronized(stdin) {
                stdin.write("$line\n".toByteArray(Charsets.ISO_8859_1))
                stdin.flush()
            }
        } catch (_: IOException) {
            // The worker is on its way out; the line is lost with it.
        }
    }

    private fun say(message: String) {
        out.println("$PREFIX $message")
        out.flush()
    }

    companion object {
        const val PREFIX = "[Pano Agent]"

        /** Replaces [DEFAULT_WORKER_JAVA_OPTS], split on whitespace. */
        const val ENV_WORKER_JAVA_OPTS = "PANO_AGENT_WORKER_JAVA_OPTS"

        /**
         * A small heap and the serial collector: the worker holds sockets and a console buffer, never
         * a world, and the machine's memory belongs to the Minecraft server next to it.
         */
        val DEFAULT_WORKER_JAVA_OPTS = listOf("-XX:+UseSerialGC", "-Xmx256m")

        /** How long a worker is given to stop its server and exit on Ctrl+C or SIGTERM. */
        const val SHUTDOWN_WAIT_SECONDS = 120L

        /**
         * Where a worker that could not pair writes why, for its launcher to say so and ask for a
         * new code (SM-76). Deleted before every start and once it has been read.
         */
        const val PAIRING_ERROR_FILE = "pairing-error.txt"

        private const val MAX_REASON_LENGTH = 300

        private const val MAIN_CLASS = "com.panomc.node.Main"

        /** The pairing flags [withCredentials] replaces, each with its value. */
        private val CREDENTIAL_FLAGS = setOf("--pano", "--platform", "--code", "--pair", "--pairing-code")

        /** A `token` key with a value in `config.conf`, however HOCON was written. */
        private val TOKEN_LINE = Regex("(?m)^\\s*(?:platform\\.)?token\\s*[=:]\\s*\"?[^\"\\s]")

        /**
         * What the first run does (SM-76):
         *
         * - nothing once the folder is [linked] (or was removed from Pano): a plain start just runs;
         * - with `--no-input` / `PANO_AGENT_NO_INPUT`, or a stdin at its end (a service, `</dev/null`):
         *   the defaults when the address and code were given, and otherwise nothing -- the worker
         *   says how to link the folder and exits 77, as before;
         * - with the address and the code given (`--pano`/`--code`, or `PANO_URL`/`PANO_PAIR_CODE`):
         *   the launch questions at a terminal, the defaults anywhere else;
         * - otherwise every question that was not answered by a flag, line by line when stdin is not
         *   a terminal (a hosting panel's console is answered in the panel).
         */
        fun setupMode(
            linked: Boolean,
            hasUrl: Boolean,
            hasCode: Boolean,
            noInput: Boolean,
            stdinClosed: Boolean,
            terminal: Boolean
        ): SetupMode = when {
            linked -> SetupMode.NONE
            noInput || stdinClosed -> if (hasUrl && hasCode) SetupMode.DEFAULTS else SetupMode.NONE
            hasUrl && hasCode -> if (terminal) SetupMode.ASK else SetupMode.DEFAULTS
            else -> SetupMode.ASK
        }

        /**
         * Whether [dataDir] holds a pairing -- a `config.conf` with a token -- or the marker of an
         * agent Pano removed. Read as text: the launcher loads no config library for one key.
         */
        fun isLinked(dataDir: File): Boolean {
            if (File(dataDir, RetiredMarker.FILE_NAME).isFile) {
                return true
            }

            val text = try {
                File(dataDir, AgentLayout.CONFIG_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            } ?: return false

            return TOKEN_LINE.containsMatchIn(text)
        }

        /** [passThrough] with the address and code the questions supplied in place of any given. */
        fun withCredentials(passThrough: List<String>, url: String, code: String): List<String> {
            val result = mutableListOf<String>()
            var index = 0

            while (index < passThrough.size) {
                val arg = passThrough[index]

                if (arg in CREDENTIAL_FLAGS) {
                    index += 2

                    continue
                }

                result.add(arg)
                index++
            }

            return result + listOf("--pano", url, "--code", code)
        }

        /** The `-Xmx` this JVM was started with, in megabytes; null when it was not given one. */
        fun ownHeapMb(): Int? = try {
            JvmArgs.heapMbOf(ManagementFactory.getRuntimeMXBean().inputArguments)
        } catch (_: Throwable) {
            null
        }

        /**
         * The worker's command line: the same java, the same jar (or classpath), `--agent-worker` with
         * the folders this launcher resolved, and then whatever the admin passed on.
         */
        fun workerCommand(
            java: String,
            jar: String?,
            classPath: String,
            layout: AgentLayout,
            passThrough: List<String>,
            javaOptions: String?
        ): List<String> {
            val options = javaOptions?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")) ?: DEFAULT_WORKER_JAVA_OPTS

            val launch = if (jar != null) listOf("-jar", jar) else listOf("-cp", classPath, MAIN_CLASS)

            return listOf(java) + options + launch +
                listOf(NodeCli.AGENT_WORKER_FLAG, "--data", layout.dataDir.path, "--server", layout.serverDir.path) +
                passThrough
        }

        /**
         * [args] without the flags the launcher decides for the worker itself (see
         * [NodeCli.LAUNCHER_OWNED_FLAGS]); everything else keeps its value and its order.
         */
        fun passThrough(args: Array<String>): List<String> {
            val result = mutableListOf<String>()
            var index = 0

            while (index < args.size) {
                val arg = args[index]
                val takesValue = arg in NodeCli.VALUE_FLAGS

                if (arg !in NodeCli.LAUNCHER_OWNED_FLAGS) {
                    result.add(arg)

                    if (takesValue && index + 1 < args.size) {
                        result.add(args[index + 1])
                    }
                }

                index += if (takesValue) 2 else 1
            }

            return result
        }

        private fun canonical(file: File): File = try {
            file.canonicalFile
        } catch (_: Exception) {
            file.absoluteFile
        }

        /** The java this launcher runs on, so the worker gets the very same one. */
        private fun currentJava(): String =
            ProcessHandle.current().info().command().orElse(null)
                ?: File(File(System.getProperty("java.home"), "bin"), HostPlatform.javaExecutable).path
    }
}
