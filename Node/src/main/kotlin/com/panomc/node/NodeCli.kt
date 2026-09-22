package com.panomc.node

import com.panomc.node.config.NodePortRange
import java.io.File

/**
 * What the daemon was asked to do, however it was asked.
 *
 * Flags and environment variables are equivalent on purpose: a systemd unit and a hand-typed
 * command line both want flags, while a container gets its whole configuration from the
 * environment and cannot be given a command line at all without rebuilding the image. A flag wins
 * over the matching variable, because the flag is the more specific instruction.
 */
data class NodeOptions(
    val platformUrl: String?,
    val pairingCode: String?,
    val bootstrapToken: String?,
    val dataDir: File,
    val name: String?,
    val runtime: String,
    val service: ServiceAction?,
    val help: Boolean,
    /**
     * `PANO_NODE_JAVA_AUTO_DOWNLOAD`, when set: overrides `node.java-auto-download` for this run
     * without being written back into config.conf (SM-63). Null means "use the config".
     */
    val javaAutoDownload: Boolean? = null,
    /**
     * `PANO_NODE_TOOL_AUTO_DOWNLOAD`, when set: overrides `node.tool-auto-download` the same way
     * (SM-67). Null means "use the config".
     */
    val toolAutoDownload: Boolean? = null,
    /**
     * `--agent` / `PANO_AGENT`: run as a Pano Agent, dedicated to the one existing server in
     * [agentServer]. Not the only way in: a jar called `pano-agent*.jar`, or a folder that already
     * holds `.pano-agent/config.conf`, is an agent without saying so (see `AgentLayout`).
     */
    val agent: Boolean = false,
    /**
     * `--server <path>` / `PANO_AGENT_SERVER`: that server's directory, resolved against the
     * working directory when relative. Absent, the agent's server is the working directory.
     */
    val agentServer: String? = null,
    /** Whether `--data` or `PANO_NODE_DATA` named [dataDir], rather than it being the default. */
    val dataDirGiven: Boolean = false,
    /**
     * `--agent-worker`: this process is the half of a Pano Agent that does the work, started by the
     * agent's launcher (`AgentLauncher`). Never typed by a person.
     */
    val agentWorker: Boolean = false,
    /**
     * `--no-input` / `PANO_AGENT_NO_INPUT`: a Pano Agent never asks its first-run questions, whatever
     * stdin is (SM-76). Scripts and services that pass `--pano`/`--code` get the defaults.
     */
    val noInput: Boolean = false,
    /**
     * `--port-range` / `PANO_NODE_PORT_RANGE` (`start-end`): the ports this node's servers may
     * bind. Written into config.conf's `node.port-range` on start, like `--name`, and announced to
     * Pano in the hello. Null means "keep the config's".
     */
    val portRange: IntRange? = null
) {
    /** What `--service` was asked to do, if anything. */
    enum class ServiceAction {
        INSTALL,
        UNINSTALL
    }
}

/**
 * The daemon's argument parser.
 *
 * Pure so the whole surface can be tested without starting anything: it takes the arguments and a
 * lookup for the environment, and returns what to do or throws with a message meant for a human.
 */
object NodeCli {
    const val ENV_URL = "PANO_URL"
    const val ENV_PAIR_CODE = "PANO_PAIR_CODE"
    const val ENV_BOOTSTRAP_TOKEN = "PANO_BOOTSTRAP_TOKEN"
    const val ENV_DATA = "PANO_NODE_DATA"
    const val ENV_NAME = "PANO_NODE_NAME"
    const val ENV_RUNTIME = "PANO_NODE_RUNTIME"
    const val ENV_JAVA_AUTO_DOWNLOAD = "PANO_NODE_JAVA_AUTO_DOWNLOAD"
    const val ENV_TOOL_AUTO_DOWNLOAD = "PANO_NODE_TOOL_AUTO_DOWNLOAD"
    const val ENV_AGENT = "PANO_AGENT"
    const val ENV_AGENT_SERVER = "PANO_AGENT_SERVER"
    const val ENV_NO_INPUT = "PANO_AGENT_NO_INPUT"
    const val ENV_PORT_RANGE = "PANO_NODE_PORT_RANGE"

    const val PORT_RANGE_FLAG = "--port-range"

    /** Never ask a Pano Agent's first-run questions. */
    const val NO_INPUT_FLAG = "--no-input"

    /** The hidden flag a Pano Agent's launcher starts its worker with. */
    const val AGENT_WORKER_FLAG = "--agent-worker"

    /** Every flag that takes a value, so a command line can be walked without parsing it again. */
    val VALUE_FLAGS = setOf(
        "--pano", "--platform", "--code", "--pair", "--pairing-code", "--bootstrap-token", "--data", "--name",
        "--runtime", "--service", "--server", "--agent-server", PORT_RANGE_FLAG
    )

    /**
     * The flags a launcher decides for its worker itself (`--agent-worker --data <dir> --server <dir>`)
     * and therefore does not pass on from its own command line.
     */
    val LAUNCHER_OWNED_FLAGS = setOf("--agent", AGENT_WORKER_FLAG, "--data", "--server", "--agent-server", "--help", "-h", NO_INPUT_FLAG)

    const val DEFAULT_DATA_DIR = "node-data"

    /** How servers run unless the operator asked for something else. */
    const val DEFAULT_RUNTIME = "PROCESS"

    /** Each server in its own container, see `DockerRuntime`. */
    const val DOCKER_RUNTIME = "DOCKER"

    val RUNTIMES = setOf(DEFAULT_RUNTIME, DOCKER_RUNTIME)

    val USAGE = """
        pano-node -- installs and supervises Minecraft servers for a Pano platform.

        Usage:
          pano-node --pano <url> --code <6-digit pairing code>   pair with a remote Pano
          pano-node --pano <url> --bootstrap-token <token>       pair as a Pano-spawned local node
          pano-node                                              run with the stored pairing

        Options:
          --pano <url>              Pano base URL, e.g. https://panel.example.com
          --code <code>             six-digit pairing code from Panel -> Servers -> Nodes
          --bootstrap-token <token> one-time token Pano mints for its own local node
          --data <dir>              data directory (default: ./$DEFAULT_DATA_DIR)
          --name <name>             node name shown in the panel
          --runtime <PROCESS|DOCKER> how servers are run (DOCKER needs docker on PATH)
          --port-range <start-end>  ports servers may bind, e.g. 25660-25669 (default 25565-25600);
                                    must match what a container publishes
          --service install         write a service unit for this host and exit
          --service uninstall       remove the service unit and exit
          --help                    print this text

        Environment equivalents: $ENV_URL, $ENV_PAIR_CODE, $ENV_BOOTSTRAP_TOKEN, $ENV_DATA,
        $ENV_NAME, $ENV_RUNTIME, $ENV_PORT_RANGE. A flag wins over the matching variable.
        $ENV_JAVA_AUTO_DOWNLOAD=true|false overrides node.java-auto-download in config.conf.
        $ENV_TOOL_AUTO_DOWNLOAD=true|false overrides node.tool-auto-download in config.conf.

        Pano Agent -- the same jar, saved as pano-agent.jar in an existing server's folder and run
        there instead of the server jar. It starts the server, shows its console and passes what
        you type to it, and lets Pano manage it (Java 17 or newer):
          cd /path/to/your/server
          java -jar pano-agent.jar                               first run: asks for the Pano
                                                                 address, the server's jar, memory,
                                                                 Java arguments and the code
          java -jar pano-agent.jar --pano <url> --code <code>    the same in one line
          java -jar pano-agent.jar                               every start after that
          java -jar pano-agent.jar --service install             write a service unit for it
        The code comes from Panel -> Servers -> Add server -> Pano Agent. The first run reads a
        start.sh/run.sh/start.bat in the folder for its defaults; --no-input (or
        $ENV_NO_INPUT=true) never asks and uses them as they are. Typing stop stops only the
        server; Ctrl+C (or the service manager) stops the server and then the agent. Its files live
        in .pano-agent/ next to the server's own.
    """.trimIndent()

    fun parse(args: Array<String>, env: (String) -> String? = System::getenv): NodeOptions {
        var platformUrl: String? = null
        var pairingCode: String? = null
        var bootstrapToken: String? = null
        var dataDir: String? = null
        var name: String? = null
        var runtime: String? = null
        var service: NodeOptions.ServiceAction? = null
        var help = false
        var agent = false
        var agentServer: String? = null
        var agentWorker = false
        var noInput = false
        var portRange: String? = null

        var index = 0

        fun next(flag: String): String {
            index++

            if (index >= args.size) {
                throw IllegalArgumentException("Missing value for $flag.")
            }

            return args[index]
        }

        while (index < args.size) {
            when (val arg = args[index]) {
                "--pano", "--platform" -> platformUrl = next(arg)
                "--code", "--pair", "--pairing-code" -> pairingCode = next(arg)
                "--bootstrap-token" -> bootstrapToken = next(arg)
                "--data" -> dataDir = next(arg)
                "--name" -> name = next(arg)
                "--runtime" -> runtime = next(arg)
                "--service" -> service = parseServiceAction(next(arg))
                "--agent" -> agent = true
                AGENT_WORKER_FLAG -> agentWorker = true
                NO_INPUT_FLAG -> noInput = true
                "--server", "--agent-server" -> agentServer = next(arg)
                PORT_RANGE_FLAG -> portRange = next(arg)
                "--help", "-h" -> help = true
                else -> throw IllegalArgumentException("Unknown option \"$arg\". Run with --help.")
            }

            index++
        }

        val resolvedRuntime = (runtime ?: env(ENV_RUNTIME) ?: DEFAULT_RUNTIME).uppercase()

        if (resolvedRuntime !in RUNTIMES) {
            throw IllegalArgumentException(
                "Unsupported runtime \"$resolvedRuntime\". Available: ${RUNTIMES.joinToString(", ")}."
            )
        }

        val resolvedAgentServer = (agentServer ?: env(ENV_AGENT_SERVER))?.trim()?.ifBlank { null }

        // A server path means agent mode: there is nothing else an ordinary node would do with one.
        val resolvedAgent = agent || agentWorker ||
            env(ENV_AGENT)?.let { parseBoolean(ENV_AGENT, it) } == true ||
            resolvedAgentServer != null

        val givenDataDir = dataDir ?: env(ENV_DATA)?.ifBlank { null }

        return NodeOptions(
            agent = resolvedAgent,
            agentServer = resolvedAgentServer,
            dataDirGiven = givenDataDir != null,
            agentWorker = agentWorker,
            noInput = noInput || env(ENV_NO_INPUT)?.let { parseBoolean(ENV_NO_INPUT, it) } == true,
            platformUrl = (platformUrl ?: env(ENV_URL))?.let { unquote(it) }?.trimEnd('/')?.ifBlank { null },
            pairingCode = (pairingCode ?: env(ENV_PAIR_CODE))?.let { unquote(it) }?.ifBlank { null },
            bootstrapToken = (bootstrapToken ?: env(ENV_BOOTSTRAP_TOKEN))?.trim()?.ifBlank { null },
            dataDir = File(givenDataDir ?: DEFAULT_DATA_DIR)
                .absoluteFile
                .toPath()
                .normalize()
                .toFile(),
            name = (name ?: env(ENV_NAME))?.trim()?.ifBlank { null },
            runtime = resolvedRuntime,
            service = service,
            help = help,
            javaAutoDownload = env(ENV_JAVA_AUTO_DOWNLOAD)?.let { parseBoolean(ENV_JAVA_AUTO_DOWNLOAD, it) },
            toolAutoDownload = env(ENV_TOOL_AUTO_DOWNLOAD)?.let { parseBoolean(ENV_TOOL_AUTO_DOWNLOAD, it) },
            // The variable is only read when the flag is absent, so a flag also covers a broken one.
            portRange = portRange?.let { parsePortRange(PORT_RANGE_FLAG, it) }
                ?: env(ENV_PORT_RANGE)?.takeIf { it.isNotBlank() }?.let { parsePortRange(ENV_PORT_RANGE, it) }
        )
    }

    /**
     * [value] trimmed and without one pair of matching quotes around it. The panel's Pano Agent
     * command is `--pano 'https://…'`, which a POSIX shell and PowerShell unquote, and Windows
     * `cmd.exe` passes on quotes and all (SM-74).
     */
    fun unquote(value: String): String {
        val trimmed = value.trim()

        if (trimmed.length >= 2 && (trimmed.first() == '\'' || trimmed.first() == '"') && trimmed.last() == trimmed.first()) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }

        return trimmed
    }

    /** `true`/`false` in the spellings people put in a compose file; blank means unset. */
    private fun parseBoolean(name: String, value: String): Boolean? = when (value.trim().lowercase()) {
        "" -> null
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw IllegalArgumentException("$name takes true or false, got \"$value\".")
    }

    /** `start-end`, both in 1..65535 and in order; anything else is an error, never a guess. */
    private fun parsePortRange(name: String, value: String): IntRange = NodePortRange.parse(value)
        ?: throw IllegalArgumentException(
            "$name takes a port range written start-end, e.g. 25660-25669, got \"$value\"."
        )

    private fun parseServiceAction(value: String) = when (value.lowercase()) {
        "install" -> NodeOptions.ServiceAction.INSTALL
        "uninstall", "remove" -> NodeOptions.ServiceAction.UNINSTALL
        else -> throw IllegalArgumentException("--service takes \"install\" or \"uninstall\", got \"$value\".")
    }
}
