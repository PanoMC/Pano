package com.panomc.node

import com.panomc.node.agent.AgentAddress
import com.panomc.node.agent.AgentLaunch
import com.panomc.node.agent.AgentLaunchReader
import com.panomc.node.agent.AgentLayout
import com.panomc.node.agent.AgentSetup
import com.panomc.node.agent.JvmArgs
import com.panomc.node.agent.StartScript
import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.files.TransferService
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.host.UninstallSteps
import com.panomc.node.net.ImportServerMessage
import com.panomc.node.net.ImportServerSpec
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.task.ImportService
import com.panomc.node.task.InPlaceAdoption
import com.panomc.node.task.LoaderInstaller
import com.panomc.node.task.TaskSink
import com.panomc.node.util.NodeLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Executors

/**
 * A Pano Agent: a daemon dedicated to one existing server folder (A2), run from inside that folder
 * (SM-74). How it is recognised, where it keeps its files, how it is remembered in config.conf,
 * that it runs that one folder and refuses everything else, and that removing it never touches the
 * server's own files, a user account or a node sharing its host. And its first run's questions
 * (SM-76): the dialogue itself, the start script it prefills from, and the address check.
 */
class AgentModeTest {
    @TempDir
    lateinit var dataDir: File

    @TempDir
    lateinit var hostDir: File

    private val noEnv: (String) -> String? = { null }

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val vertx = Vertx.vertx()

    @AfterEach
    fun closeVertx() {
        vertx.close()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private class LastFrame : TaskSink {
        var status: String? = null
        var text: String? = null
        var extra: JsonObject? = null

        override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {}

        override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
            status = "DONE"
            text = message
            this.extra = extra
        }

        override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
            status = "FAILED"
            text = error
            this.extra = extra
        }
    }

    // ----------------------------------------------------------------------------- command line

    @Test
    fun `--agent and --server select agent mode`() {
        val options = NodeCli.parse(arrayOf("--pano", "http://p", "--code", "123456", "--agent", "--server", "/srv/smp"), noEnv)

        assertTrue(options.agent)
        assertEquals("/srv/smp", options.agentServer)
    }

    @Test
    fun `a server path alone means agent mode, and the environment works too`() {
        assertTrue(NodeCli.parse(arrayOf("--server", "/srv/smp"), noEnv).agent)

        val env = mapOf(NodeCli.ENV_AGENT to "true", NodeCli.ENV_AGENT_SERVER to "/srv/other")
        val options = NodeCli.parse(emptyArray(), env::get)

        assertTrue(options.agent)
        assertEquals("/srv/other", options.agentServer)

        assertFalse(NodeCli.parse(emptyArray(), noEnv).agent)
    }

    @Test
    fun `a relative server path is taken from the working directory`() {
        val options = NodeCli.parse(arrayOf("--agent", "--server", "smp"), noEnv)

        val layout = AgentLayout.resolve(options, null, hostDir)!!

        assertEquals(File(hostDir, "smp").canonicalFile, layout.serverDir)
        assertEquals(File(File(hostDir, "smp").canonicalFile, ".pano-agent"), layout.dataDir)
    }

    // --------------------------------------------------------------------------- the folder model

    @Test
    fun `a jar called pano-agent is an agent run from the working directory`() {
        val options = NodeCli.parse(emptyArray(), noEnv)

        val layout = AgentLayout.resolve(options, "/srv/smp/pano-agent.jar", hostDir)!!

        assertEquals(hostDir.canonicalFile, layout.serverDir)
        assertEquals(File(hostDir.canonicalFile, AgentLayout.DATA_DIRECTORY), layout.dataDir)
        assertTrue(layout.defaultDataDir)

        assertTrue(AgentLayout.isAgent(options, "/x/PANO-AGENT-1.2.jar", hostDir))
        assertFalse(AgentLayout.isAgent(options, "/opt/pano-node/pano-node.jar", hostDir))
        assertNull(AgentLayout.resolve(options, null, hostDir))
    }

    @Test
    fun `a folder that already holds an agent's config is an agent whatever the jar is called`() {
        File(hostDir, ".pano-agent").mkdirs()
        File(hostDir, ".pano-agent/config.conf").writeText("platform.url=\"http://p\"\n")

        // A hosting panel that only starts server.jar: the agent was renamed, the folder remembers.
        val layout = AgentLayout.resolve(NodeCli.parse(emptyArray(), noEnv), "/srv/smp/server.jar", hostDir)

        assertEquals(hostDir.canonicalFile, layout?.serverDir)

        // A node started with a data directory of its own is never taken for one.
        val node = NodeCli.parse(arrayOf("--data", dataDir.absolutePath), noEnv)

        assertNull(AgentLayout.resolve(node, "/srv/smp/server.jar", hostDir))
    }

    @Test
    fun `--data moves the agent's data out of the folder`() {
        val options = NodeCli.parse(arrayOf("--agent", "--data", dataDir.absolutePath), noEnv)

        val layout = AgentLayout.resolve(options, null, hostDir)!!

        assertEquals(hostDir.canonicalFile, layout.serverDir)
        assertEquals(dataDir.absoluteFile, layout.dataDir)
        assertFalse(layout.defaultDataDir)
    }

    @Test
    fun `the worker flag is an agent and is never passed on`() {
        val options = NodeCli.parse(arrayOf(NodeCli.AGENT_WORKER_FLAG, "--data", dataDir.absolutePath, "--server", hostDir.absolutePath), noEnv)

        assertTrue(options.agentWorker)
        assertTrue(options.agent)
        assertTrue(options.dataDirGiven)
        assertFalse(NodeCli.parse(emptyArray(), noEnv).dataDirGiven)
        assertTrue(NodeCli.parse(emptyArray()) { if (it == NodeCli.ENV_DATA) "/d" else null }.dataDirGiven)
    }

    @Test
    fun `an agent's service is named after its folder`() {
        val name = AgentLayout.serviceName(File("/home/mc/survival"))

        assertTrue(Regex("^pano-agent-[0-9a-f]{12}$").matches(name), name)
        assertEquals(name, AgentLayout.serviceName(File("/home/mc/survival")))
        assertFalse(name == AgentLayout.serviceName(File("/home/mc/creative")))
    }

    @Test
    fun `the help text shows the folder form`() {
        assertTrue(NodeCli.USAGE.contains("java -jar pano-agent.jar --pano <url> --code <code>"), NodeCli.USAGE)
        assertTrue(NodeCli.USAGE.contains("java -jar pano-agent.jar --service install"), NodeCli.USAGE)
        assertFalse(NodeCli.USAGE.contains("--agent "), "the install-script agent flags are gone from the help")
    }

    // ------------------------------------------------------------------------------ config.conf

    @Test
    fun `agent mode survives a round trip through config conf`() {
        val config = NodeConfig(platformUrl = "http://p", agent = true, agentServer = "/srv/smp")

        val read = NodeConfigStore.parse(NodeConfigStore.render(config))

        assertTrue(read.agent)
        assertEquals("/srv/smp", read.agentServer)
        assertEquals("/srv/smp", read.agentServerOrNull())
    }

    @Test
    fun `an ordinary node's config conf does not mention agents`() {
        val rendered = NodeConfigStore.render(NodeConfig(platformUrl = "http://p"))

        assertFalse(com.typesafe.config.ConfigFactory.parseString(rendered).hasPath("node.agent"))
        assertFalse(com.typesafe.config.ConfigFactory.parseString(rendered).hasPath("node.agent-server"))
        assertFalse(NodeConfigStore.parse(rendered).agent)
        assertNull(NodeConfigStore.parse(rendered).agentServerOrNull())
    }

    // --------------------------------------------------------------------------- one server only

    private fun existingServer(): File {
        val directory = File(hostDir, "smp").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }

        File(directory, "paper-1.21.8.jar").writeBytes(ByteArray(64))
        File(directory, "server.properties").writeText("server-port=$port\n")
        File(directory, "world").mkdirs()

        return directory
    }

    private fun agentImport(dedicated: String): Pair<ServerRegistry, (ImportServerMessage) -> LastFrame> {
        val registry = ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)
            .apply { load() }
        val config = NodeConfig()
        val connection = PlatformConnection(vertx, logger, config)
        val reservations = PortReservations()

        val run = { message: ImportServerMessage ->
            val sink = LastFrame()

            ImportService(
                registry = registry,
                reporter = sink,
                connection = connection,
                transferService = TransferService(registry, config, logger),
                loaderInstaller = LoaderInstaller(JavaRuntimeLocator(dataDir), logger),
                logger = logger,
                dataDir = dataDir,
                reservations = reservations,
                portResolver = PortResolver(registry, reservations, connection, logger) { 40000..40100 },
                adoptionHost = InPlaceAdoption.Host(dataDir, windows = false, home = null) { emptySequence() },
                agentServer = { dedicated }
            ).import(message)

            sink
        }

        return registry to run
    }

    private fun message(uuid: String, mode: String, path: String?) = ImportServerMessage(
        serverUuid = uuid,
        taskId = "t-$uuid",
        mode = mode,
        folderPath = path,
        spec = ImportServerSpec(name = "x", port = 0, acceptEula = false)
    )

    @Test
    fun `an agent adopts its own folder and nothing else`() {
        val directory = existingServer()
        val other = File(hostDir, "other").apply { mkdirs() }.also { File(it, "server.jar").writeBytes(ByteArray(8)) }

        val (registry, run) = agentImport(directory.absolutePath)

        // Another folder, and a copy import of any kind, are refused before anything is touched.
        val elsewhere = run(message("a", ImportService.MODE_IN_PLACE, other.absolutePath))

        assertEquals("FAILED", elsewhere.status)
        assertEquals(ImportService.AGENT_SINGLE_SERVER, elsewhere.extra?.getString("errorCode"))

        val copy = run(message("b", ImportService.MODE_FOLDER, directory.absolutePath))

        assertEquals(ImportService.AGENT_SINGLE_SERVER, copy.extra?.getString("errorCode"))
        assertFalse(File(dataDir, "servers/b").exists())

        // Its own folder is adopted in place.
        val own = run(message("c", ImportService.MODE_IN_PLACE, directory.absolutePath))

        assertEquals("DONE", own.status, own.text)
        assertTrue(registry.isInPlace("c"))

        // And once it has its server, a second one is refused even for the same folder.
        val again = run(message("d", ImportService.MODE_IN_PLACE, directory.absolutePath))

        assertEquals(ImportService.AGENT_SINGLE_SERVER, again.extra?.getString("errorCode"))
        assertNull(registry.get("d"))
    }

    // -------------------------------------------------------------------------------- uninstall

    private val folder = "/home/mc/survival"

    private fun agentEnvironment(
        os: String = "linux",
        isRoot: Boolean = false,
        systemdUnit: String? = null,
        selfServiceUnit: String? = null,
        inContainer: Boolean = false
    ) = UninstallEnvironment(
        os = os,
        isRoot = isRoot,
        inContainer = inContainer,
        systemdUnit = systemdUnit,
        selfServiceUnit = selfServiceUnit,
        jarPath = "$folder/pano-agent.jar",
        dataDir = "$folder/.pano-agent",
        user = "mc",
        agentFolder = folder
    )

    @Test
    fun `an agent's manual steps remove only its own folder and point at the jar`() {
        val steps = UninstallSteps.build(agentEnvironment())

        assertEquals(
            listOf(
                "rm -rf $folder/.pano-agent",
                "# pano-agent.jar can be deleted too: start the server with its own server jar again."
            ),
            steps
        )

        // Never the server folder itself, never the jar, never a user.
        assertTrue(steps.none { it == "rm -rf $folder" || it.contains("rm -rf $folder ") }, steps.toString())
        assertTrue(steps.none { it.startsWith("rm") && it.contains("pano-agent.jar") }, steps.toString())
        assertTrue(steps.none { it.contains("userdel") }, steps.toString())
        assertNull(agentEnvironment().installDir, "an agent's jar folder is the server's, never an install folder")
    }

    @Test
    fun `an agent's service unit is disabled by its own name`() {
        val name = AgentLayout.serviceName(File(folder))

        val steps = UninstallSteps.build(agentEnvironment(systemdUnit = "/etc/systemd/system/$name.service"))

        assertEquals("sudo systemctl disable --now $name", steps[0])
        assertEquals("sudo rm -f /etc/systemd/system/$name.service", steps[1])
        assertTrue(steps.none { it.contains("pano-node") }, steps.toString())
        assertTrue(steps.none { it.contains("/etc/pano") }, steps.toString())

        val handled = UninstallSteps.build(agentEnvironment(isRoot = true, systemdUnit = "/etc/systemd/system/$name.service"), serviceHandled = true)

        assertTrue(handled.none { it.contains("systemctl") }, handled.toString())
    }

    @Test
    fun `an agent in a hosting panel's container is not the container`() {
        val steps = UninstallSteps.build(agentEnvironment(inContainer = true))

        assertTrue(steps.none { it.contains("docker") || it.contains("Coolify") }, steps.toString())
        assertEquals("rm -rf $folder/.pano-agent", steps[0])
    }

    @Test
    fun `a node's environment is unaffected by the agent's`() {
        val node = agentEnvironment(isRoot = true).copy(agentFolder = null, jarPath = "/opt/pano-node/pano-node.jar")

        assertEquals("pano-node", node.serviceName)
        assertNull(node.agentService)
        assertEquals(UninstallEnvironment.SYSTEMD_ENV_DIR, node.systemdEnvPath)
        assertEquals("/opt/pano-node", node.installDir)
    }

    // ------------------------------------------------------------ the first run's questions (SM-76)

    private val aikarScript = "#!/bin/sh\n" +
        "# Aikar's flags\n" +
        "java -Xms4G -Xmx4G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
        "-Daikars.new.flags=true -jar paper-1.21.8.jar --nogui\n"

    private val aikarFlags = listOf("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-Daikars.new.flags=true")

    /** A server folder as the first run finds it: the server's jar, the agent's, and [script] as start.sh. */
    private fun firstRunFolder(script: String? = null): File {
        val folder = File(hostDir, "survival").apply { mkdirs() }

        File(folder, "paper-1.21.8.jar").writeBytes(ByteArray(4096))
        File(folder, "pano-agent.jar").writeBytes(ByteArray(64 * 1024))

        script?.let { File(folder, "start.sh").writeText(it) }

        return folder.canonicalFile
    }

    private class FirstRun(val setup: AgentSetup, private val buffer: ByteArrayOutputStream, val checked: List<String>) {
        val output: String get() = buffer.toString(Charsets.UTF_8)
    }

    /** The questions for [folder], answered with [answers] one line at a time; the end of them is Ctrl+D. */
    private fun firstRun(
        folder: File,
        vararg answers: String,
        terminal: Boolean = false,
        launcherMemoryMb: Int? = null,
        check: ((String) -> AgentAddress.Check)? = null
    ): FirstRun {
        val queue = ArrayDeque(answers.toList())
        val buffer = ByteArrayOutputStream()
        val checked = mutableListOf<String>()

        val setup = AgentSetup(
            serverDir = folder,
            readLine = { queue.removeFirstOrNull() },
            out = PrintStream(buffer, true, "UTF-8"),
            terminal = terminal,
            agentJar = File(folder, "pano-agent.jar").canonicalFile,
            launcherMemoryMb = launcherMemoryMb,
            checkAddress = { url ->
                checked.add(url)

                check?.invoke(url) ?: AgentAddress.Check.Ok(url)
            }
        )

        return FirstRun(setup, buffer, checked)
    }

    @Test
    fun `the first run asks everything, and Enter keeps every default`() {
        val folder = firstRunFolder(aikarScript)

        val run = firstRun(folder, "example.com/panel/", "", "", "", "", " 'abcd2345efgh6789' ")

        val link = run.setup.run() as AgentSetup.Outcome.Link

        assertEquals(
            AgentSetup.Answers("https://example.com", "abcd2345efgh6789", "paper-1.21.8.jar", 4096, aikarFlags),
            link.answers
        )
        assertEquals(listOf("https://example.com"), run.checked)

        assertEquals(
            listOf(
                "Pano Agent — linking ${folder.path} to Pano. Press Enter to keep a [default].",
                "Found start.sh — using its settings as defaults.",
                "Pano address (your website, e.g. https://example.com):",
                "Checking https://example.com ...",
                "Server jar [paper-1.21.8.jar]:",
                "Memory for the server [4G]:",
                "Extra Java arguments [${aikarFlags.joinToString(" ")}]:",
                "",
                "  Pano address:    https://example.com",
                "  Server folder:   ${folder.path}",
                "  Server jar:      paper-1.21.8.jar",
                "  Memory:          4G",
                "  Java arguments:  ${aikarFlags.joinToString(" ")}",
                "Link this folder to Pano? [Y/n]",
                "Pairing code (Pano panel → Add Server → Link with the Pano Agent):",
                ""
            ),
            run.output.lines()
        )

        // What the launcher writes, and what the worker reads back.
        val data = File(folder, ".pano-agent")

        AgentLaunch.write(data, link.answers.launch)

        assertEquals(AgentLaunch("paper-1.21.8.jar", 4096, aikarFlags), AgentLaunchReader.read(data))
    }

    @Test
    fun `at a terminal the answer goes on the question's line, and the address and code may be given`() {
        val folder = firstRunFolder()

        val run = firstRun(folder, "", "", "", "", terminal = true)

        val link = run.setup.run(givenUrl = "https://p.example", givenCode = "c0de") as AgentSetup.Outcome.Link

        assertEquals(AgentSetup.Answers("https://p.example", "c0de", "paper-1.21.8.jar", 2048, emptyList()), link.answers)
        assertTrue(run.checked.isEmpty(), "a given address is not asked for, so not checked here either")
        assertTrue(run.output.contains("Server jar [paper-1.21.8.jar]: Memory for the server [2G]: Extra Java arguments [none]: \n"), run.output)
        assertFalse(run.output.contains("Pano address ("), run.output)
        assertFalse(run.output.contains("Pairing code"), run.output)
    }

    @Test
    fun `the address is normalised`() {
        assertEquals("https://example.com", AgentAddress.normalise("example.com"))
        assertEquals("https://example.com", AgentAddress.normalise("  https://Example.COM/panel/ "))
        assertEquals("https://example.com", AgentAddress.normalise("https://example.com/panel/panel/"))
        assertEquals("http://127.0.0.1:8088", AgentAddress.normalise("'http://127.0.0.1:8088/'"))
        assertEquals("https://example.com/pano", AgentAddress.normalise("\"https://example.com/pano/panel?x=1\""))
        assertEquals("https://example.com:8443", AgentAddress.normalise("example.com:8443/"))
        assertNull(AgentAddress.normalise(""))
        assertNull(AgentAddress.normalise("ftp://example.com"))
        assertNull(AgentAddress.normalise("not an address"))
    }

    /** A web server on an ephemeral port answering [routes] (path to status and body), 404 HTML elsewhere. */
    private fun stub(routes: Map<String, Pair<Int, String>>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (status, body) = routes[exchange.requestURI.path] ?: (404 to "<html>Not found</html>")
                val bytes = body.toByteArray()

                routes["redirect:" + exchange.requestURI.path]?.let { (code, location) ->
                    exchange.responseHeaders.add("Location", location)
                    exchange.sendResponseHeaders(code, -1)
                    exchange.close()

                    return@createContext
                }

                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}"

    private val checksum = "a".repeat(64) + "  pano-agent.jar\n"

    @Test
    fun `the address check tells a Pano from an old one, from anything else, and from nothing`() {
        val timeout = Duration.ofSeconds(5)
        val pano = stub(mapOf(AgentAddress.PROBE_PATH to (200 to checksum)))
        val panoWithoutJar = stub(mapOf(AgentAddress.PROBE_PATH to (404 to "{\"result\":\"error\",\"error\":\"NOT_EXISTS\"}")))
        val oldPano = stub(mapOf(AgentAddress.NODE_PROBE_PATH to (200 to checksum)))
        val website = stub(emptyMap())
        val moved = stub(mapOf("redirect:${AgentAddress.PROBE_PATH}" to (301 to pano.url() + AgentAddress.PROBE_PATH)))
        val closed = ServerSocket(0).use { it.localPort }

        try {
            assertEquals(AgentAddress.Check.Ok(pano.url()), AgentAddress.check(pano.url(), timeout))
            assertEquals(AgentAddress.Check.Ok(panoWithoutJar.url()), AgentAddress.check(panoWithoutJar.url(), timeout))
            assertEquals(AgentAddress.Check.TooOld, AgentAddress.check(oldPano.url(), timeout))
            assertEquals(AgentAddress.Check.NotPano(404), AgentAddress.check(website.url(), timeout))
            assertEquals(AgentAddress.Check.Ok(pano.url()), AgentAddress.check(moved.url(), timeout), "a redirect is followed and kept")

            val unreachable = AgentAddress.check("http://127.0.0.1:$closed", timeout)

            assertTrue(unreachable is AgentAddress.Check.Unreachable, unreachable.toString())
            assertEquals(
                "Could not reach https://127.0.0.1:$closed (connection refused). If this Pano has no HTTPS, type the address with http:// in front.",
                AgentAddress.message("https://127.0.0.1:$closed", unreachable, schemeTyped = false)
            )
        } finally {
            listOf(pano, panoWithoutJar, oldPano, website, moved).forEach { it.stop(0) }
        }
    }

    @Test
    fun `a failing check asks for the address again, with what was typed as the default`() {
        val folder = firstRunFolder()
        val pano = stub(mapOf(AgentAddress.PROBE_PATH to (200 to checksum)))
        val website = stub(emptyMap())

        try {
            val typo = "${website.url()}/panel/"

            val run = firstRun(folder, "", typo, "", pano.url(), "", "", "", "", "code") { url ->
                AgentAddress.check(url, Duration.ofSeconds(5))
            }

            val link = run.setup.run() as AgentSetup.Outcome.Link

            assertEquals(pano.url(), link.answers.panoUrl)
            assertEquals(listOf(website.url(), website.url(), pano.url()), run.checked, "Enter re-checks what was typed")

            val lines = run.output.lines()

            assertEquals(
                listOf(
                    "Pano address (your website, e.g. https://example.com):",
                    "Pano address (your website, e.g. https://example.com):",
                    "Checking ${website.url()} ...",
                    "${website.url()} answered, but it is not a Pano (HTTP 404). Type your Pano website's address.",
                    "Pano address (your website, e.g. https://example.com) [$typo]:",
                    "Checking ${website.url()} ...",
                    "${website.url()} answered, but it is not a Pano (HTTP 404). Type your Pano website's address.",
                    "Pano address (your website, e.g. https://example.com) [$typo]:",
                    "Checking ${pano.url()} ..."
                ),
                lines.subList(1, 10)
            )
        } finally {
            pano.stop(0)
            website.stop(0)
        }
    }

    @Test
    fun `the server jar is picked by number or by name, and never the agent`() {
        val folder = firstRunFolder()

        File(folder, "velocity-3.4.jar").writeBytes(ByteArray(1024))
        File(folder, "forge-1.20.1-installer.jar").writeBytes(ByteArray(8192))

        val run = firstRun(folder, "https://p.example", "pano-agent.jar", "nope.jar", "7", "3", "", "", "", "code")

        val link = run.setup.run() as AgentSetup.Outcome.Link

        assertEquals("velocity-3.4.jar", link.answers.jar)

        val lines = run.output.lines()

        assertEquals(
            listOf(
                "  1) forge-1.20.1-installer.jar",
                "  2) paper-1.21.8.jar",
                "  3) velocity-3.4.jar",
                "Server jar [paper-1.21.8.jar]:",
                "That is the Pano Agent itself; pick your server's jar.",
                "Server jar [paper-1.21.8.jar]:",
                "There is no nope.jar in this folder. Type its number or its name.",
                "Server jar [paper-1.21.8.jar]:",
                "There is no 7 in this folder. Type its number or its name.",
                "Server jar [paper-1.21.8.jar]:",
                "Memory for the server [2G]:"
            ),
            lines.subList(lines.indexOf("  1) forge-1.20.1-installer.jar"), lines.indexOf("Memory for the server [2G]:") + 1)
        )

        val byName = firstRun(folder, "https://p.example", "VELOCITY-3.4.JAR", "", "", "", "code").setup.run() as AgentSetup.Outcome.Link

        assertEquals("velocity-3.4.jar", byName.answers.jar)
    }

    @Test
    fun `memory is read in any of the usual spellings`() {
        mapOf(
            "4G" to 4096, "4g" to 4096, "4096M" to 4096, "4096m" to 4096, "4096" to 4096, "1.5G" to 1536,
            "4GB" to 4096, "-Xmx6G" to 6144, " 8 G " to 8192, "1T" to 1024 * 1024
        ).forEach { (typed, mb) -> assertEquals(mb, JvmArgs.memoryAnswerMb(typed), typed) }

        listOf("lots", "4X", "", "G", "-4G").forEach { assertNull(JvmArgs.memoryAnswerMb(it), it) }

        // -Xmx as the JVM reads it, for start scripts and the launcher's own.
        assertEquals(4096, JvmArgs.heapMb("4G"))
        assertEquals(4096, JvmArgs.heapMb("4096m"))
        assertEquals(512, JvmArgs.heapMb("524288k"))
        assertEquals(1024, JvmArgs.heapMb("1073741824"))
        assertNull(JvmArgs.heapMb("4 G"))
        assertEquals(3072, JvmArgs.heapMbOf(listOf("-Xmx1G", "-Xms1G", "-Xmx3G")))

        assertEquals("4G", JvmArgs.formatMemory(4096))
        assertEquals("3584M", JvmArgs.formatMemory(3584))

        val run = firstRun(firstRunFolder(), "https://p.example", "", "256M", "lots", "2048G", "6g", "", "", "code")

        assertEquals(6144, (run.setup.run() as AgentSetup.Outcome.Link).answers.memoryMb)
        assertTrue(run.output.contains("A server needs at least 512M.\n"), run.output)
        assertTrue(run.output.contains("Type an amount like 4G or 4096M.\n"), run.output)
        assertTrue(run.output.contains("That is more than Pano allows (1024G).\n"), run.output)
    }

    @Test
    fun `Java arguments are split like a shell, and the memory and jar flags are left out`() {
        assertEquals(
            listOf("-Da=x y", "-Db=\"q\"", "-Dc=C:\\temp\\x", "-Dd=it's"),
            JvmArgs.split("-Da='x y'  \"-Db=\\\"q\\\"\" -Dc=C:\\temp\\x -Dd=it\\'s")
        )
        assertEquals(listOf("C:\\Program Files\\Java\\bin\\java.exe", "-jar", "x.jar"), JvmArgs.split("\"C:\\Program Files\\Java\\bin\\java.exe\" -jar x.jar", windows = true))
        assertNull(JvmArgs.split("-Dx=\"open"))
        assertEquals(emptyList<String>(), JvmArgs.split("   "))

        val args = listOf("-Da=x y", "-Db", "-Dc=\"q\"")

        assertEquals(args, JvmArgs.split(JvmArgs.join(args)))

        assertEquals(
            listOf("-XX:+UseG1GC", "--add-opens", "java.base/java.lang=ALL-UNNAMED") to
                listOf("-Xms1G", "-Xmx2G", "-jar", "paper.jar", "nogui"),
            JvmArgs.withoutLaunchFlags(
                listOf("-Xms1G", "-XX:+UseG1GC", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Xmx2G", "-jar", "paper.jar", "nogui")
            )
        )

        val typed = "-Xms1G -XX:+UseG1GC \"-Dmotd=Hello world\" -Xmx2G -jar paper.jar nogui"
        val run = firstRun(firstRunFolder(), "https://p.example", "", "", typed, "", "code")

        assertEquals(listOf("-XX:+UseG1GC", "-Dmotd=Hello world"), (run.setup.run() as AgentSetup.Outcome.Link).answers.jvmArgs)
        assertTrue(run.output.contains("Left out -Xms1G -Xmx2G -jar paper.jar nogui: the memory and the jar are asked separately.\n"), run.output)

        // "-" clears a start script's arguments, "none" too; Enter keeps them.
        listOf("-", "none").forEach { clear ->
            val cleared = firstRun(firstRunFolder(aikarScript), "https://p.example", "", "", clear, "", "code").setup.run()

            assertEquals(emptyList<String>(), (cleared as AgentSetup.Outcome.Link).answers.jvmArgs, clear)
        }
    }

    @Test
    fun `a start script is read for the defaults, and ignored when it is odd`() {
        assertEquals(
            StartScript.Found("", "paper-1.21.8.jar", 4096, aikarFlags),
            StartScript.parse(aikarScript, windows = false)
        )

        // cmd.exe: a %JAVA% variable, backslashes that are paths, and the pause after it.
        val bat = "@echo off\r\nset JAVA=C:\\Program Files\\Java\\jdk-21\\bin\\java.exe\r\n" +
            "\"%JAVA%\" -Xms1G -Xmx6G -XX:+UseG1GC -Dfile.encoding=UTF-8 -jar server.jar nogui\r\npause\r\n"

        assertEquals(StartScript.Found("", "server.jar", 6144, listOf("-XX:+UseG1GC", "-Dfile.encoding=UTF-8")), StartScript.parse(bat, windows = true))
        assertEquals(3072, StartScript.parse("%JAVA_HOME%\\bin\\java.exe -Xmx3G -jar forge.jar", windows = true)?.memoryMb)

        // Quoted arguments, a jar name with a space, the arguments passed on.
        assertEquals(
            StartScript.Found("", "my server.jar", 2048, listOf("-Dlog4j.configurationFile=my log4j.xml", "-Dfile.encoding=UTF-8")),
            StartScript.parse(
                "#!/bin/bash\nexec java -Xmx2G \"-Dlog4j.configurationFile=my log4j.xml\" -Dfile.encoding=UTF-8 -jar \"my server.jar\" nogui \"$@\"\n",
                windows = false
            )
        )

        // No -jar at all (Forge's argument files): nothing to go on.
        assertNull(StartScript.parse("java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.2.0/unix_args.txt \"$@\"\n", windows = false))

        // Around the command, continuations, comments and variables.
        assertEquals(
            StartScript.Found("", "paper.jar", 3072, emptyList()),
            StartScript.parse("cd /srv/mc && java -Xmx3G -jar ./paper.jar nogui > log.txt 2>&1\n", windows = false)
        )
        assertEquals(
            StartScript.Found("", "paper.jar", 5120, listOf("-XX:+UseG1GC")),
            StartScript.parse("java -Xmx5G \\\n    -XX:+UseG1GC \\\n    -jar paper.jar\n", windows = false)
        )
        assertEquals(2048, StartScript.parse("# java -Xmx1G -jar old.jar\njava -Xmx2G -jar paper.jar\n", windows = false)?.memoryMb)
        assertEquals(emptyList<String>(), StartScript.parse("\"\$JAVA\" \$JAVA_OPTS -Xmx2G -jar paper.jar", windows = false)?.jvmArgs)
        assertNull(StartScript.parse("java -Xmx4G -jar \$JAR", windows = false), "a jar that is a variable is no jar")
        assertNull(StartScript.parse("java -Xmx4G \"-Dx=1 -jar paper.jar", windows = false), "an open quote")
        assertNull(StartScript.parse("-jar paper.jar java", windows = false), "-jar before java")

        // The first script that reads, and its name for the "Found" line.
        val folder = firstRunFolder()

        File(folder, "start.sh").writeText("#!/bin/sh\necho starting\n")
        File(folder, "run.sh").writeText("java -Xmx3G -jar paper-1.21.8.jar\n")

        assertEquals(StartScript.Found("run.sh", "paper-1.21.8.jar", 3072, emptyList()), StartScript.find(folder))
    }

    @Test
    fun `no at the summary starts over, with the answers as the defaults`() {
        val folder = firstRunFolder()

        val run = firstRun(folder, "https://a.example", "", "3G", "-XX:+UseG1GC", "n", "", "", "", "", "y", "code")

        val link = run.setup.run() as AgentSetup.Outcome.Link

        assertEquals(AgentSetup.Answers("https://a.example", "code", "paper-1.21.8.jar", 3072, listOf("-XX:+UseG1GC")), link.answers)
        assertEquals(listOf("https://a.example", "https://a.example"), run.checked)
        assertTrue(run.output.contains("Pano address (your website, e.g. https://example.com) [https://a.example]:\n"), run.output)
        assertTrue(run.output.contains("Memory for the server [3G]:\n"), run.output)
        assertTrue(run.output.contains("Extra Java arguments [-XX:+UseG1GC]:\n"), run.output)
    }

    @Test
    fun `q or the end of input stops, and input that never came is nobody to ask`() {
        val folder = firstRunFolder()

        val quit = firstRun(folder, "q")

        assertEquals(AgentSetup.Outcome.Quit(0), quit.setup.run())
        assertTrue(quit.output.contains("Stopped; nothing was saved."), quit.output)

        // Ctrl+D at a terminal, and after the first answer anywhere, is the admin stopping.
        assertEquals(AgentSetup.Outcome.Quit(0), firstRun(folder, terminal = true).setup.run())
        assertEquals(AgentSetup.Outcome.Quit(0), firstRun(folder, "https://a.example", "").setup.run())

        // A pipe that ends before anything was typed: nobody is there.
        assertEquals(AgentSetup.Outcome.NoInput, firstRun(folder).setup.run())

        // Nothing to run: said, and nothing asked again.
        val empty = File(hostDir, "empty").apply { mkdirs() }

        File(empty, "pano-agent.jar").writeBytes(ByteArray(8))

        val none = firstRun(empty.canonicalFile, "https://a.example")

        assertEquals(AgentSetup.Outcome.Quit(AgentSetup.NO_SERVER_JAR_EXIT_CODE), none.setup.run())
        assertTrue(none.output.contains("There is no server jar in"), none.output)
    }

    @Test
    fun `the launcher's own -Xmx is the memory default, before the script's`() {
        val folder = firstRunFolder(aikarScript)

        assertEquals(8192, firstRun(folder, launcherMemoryMb = 8192).setup.defaults().memoryMb)
        assertEquals(4096, firstRun(folder, launcherMemoryMb = 256).setup.defaults().memoryMb, "a launcher-sized heap is not the server's")
        assertEquals(4096, firstRun(folder).setup.defaults().memoryMb)
        assertEquals(JvmArgs.DEFAULT_MEMORY_MB, firstRun(firstRunFolder().also { File(it, "start.sh").delete() }).setup.defaults().memoryMb)
    }

    @Test
    fun `a refused code is asked for again, and Enter gives up`() {
        val folder = firstRunFolder()

        assertEquals("n3wc0de", firstRun(folder, " \"n3wc0de\" ").setup.askCodeAgain("x"))
        assertNull(firstRun(folder, "").setup.askCodeAgain("x"))
        assertNull(firstRun(folder).setup.askCodeAgain("x"))

        assertEquals(
            "Pano did not accept that code (it may have expired — codes last a minute). Copy the current one from the panel.",
            AgentSetup.codeRejected("Pano refused the pairing: INVALID_NODE_PAIRING_CODE")
        )
        assertEquals(
            "Pano did not accept that code (it may have expired - codes last a minute). Copy the current one from the panel.",
            AgentSetup.codeRejected(null, unicode = false)
        )
        assertEquals(
            "Pano did not accept that code (Connection refused: pano.example/1.2.3.4:443). Copy the current one from the panel.",
            AgentSetup.codeRejected("Connection refused: pano.example/1.2.3.4:443")
        )
    }

    @Test
    fun `launch json keeps only what could be meant`() {
        val read = AgentLaunchReader.parse(
            "{\"jar\": \"../evil.jar\", \"memoryMb\": 100, \"jvmArgs\": [\"  -XX:+UseG1GC \", \"\", 5, \"" + "x".repeat(300) + "\"]}"
        )!!

        assertNull(read.jar)
        assertEquals(JvmArgs.MIN_MEMORY_MB, read.memoryMb)
        assertEquals(listOf("-XX:+UseG1GC", "5", "x".repeat(256)), read.jvmArgs)
        assertEquals(64, AgentLaunchReader.parse("{\"jvmArgs\": [" + (1..70).joinToString(",") { "\"-D$it\"" } + "]}")!!.jvmArgs.size)
        assertNull(AgentLaunchReader.parse("[1]"))

        val launch = AgentLaunch("paper \"1\".jar", 4096, listOf("-Dq=\"a\\b\""))

        assertEquals(launch, AgentLaunchReader.parse(launch.toJson()))
        assertEquals(
            JsonObject().put("jar", "paper \"1\".jar").put("memoryMb", 4096).put("jvmArgs", io.vertx.core.json.JsonArray(listOf("-Dq=\"a\\b\""))),
            AgentLaunchReader.toHello(launch)
        )
    }
}
