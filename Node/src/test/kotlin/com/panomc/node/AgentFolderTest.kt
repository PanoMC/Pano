package com.panomc.node

import com.panomc.node.agent.AgentAddress
import com.panomc.node.agent.AgentFiles
import com.panomc.node.agent.AgentLaunch
import com.panomc.node.agent.AgentLaunchReader
import com.panomc.node.agent.AgentLauncher
import com.panomc.node.agent.AgentLayout
import com.panomc.node.agent.AgentRestartPolicy
import com.panomc.node.agent.AgentWorkerJar
import com.panomc.node.backup.BackupScope
import com.panomc.node.backup.BackupScopeKind
import com.panomc.node.config.NodeConfig
import com.panomc.node.config.NodeConfigStore
import com.panomc.node.console.ConsoleLevel
import com.panomc.node.files.BackupExcludeMatcher
import com.panomc.node.files.ServerFileDenylist
import com.panomc.node.files.TransferService
import com.panomc.node.files.ZipTool
import com.panomc.node.host.HostPlatform
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.host.ServiceInstaller
import com.panomc.node.host.UninstallEnvironment
import com.panomc.node.net.ImportServerMessage
import com.panomc.node.net.ImportServerSpec
import com.panomc.node.net.NodeUninstallMessage
import com.panomc.node.net.PlatformConnection
import com.panomc.node.server.ExternalServerIndex
import com.panomc.node.server.PortReservations
import com.panomc.node.server.PortResolver
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.server.ServerSpec
import com.panomc.node.task.ImportService
import com.panomc.node.task.InPlaceAdoption
import com.panomc.node.task.LoaderInstaller
import com.panomc.node.task.ServerInspection
import com.panomc.node.task.ServerJars
import com.panomc.node.task.TaskSink
import com.panomc.node.task.UninstallService
import com.panomc.node.util.NodeLogger
import com.panomc.node.util.RotatingLogFile
import com.panomc.node.util.Sha256
import com.sun.net.httpserver.HttpServer
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The Pano Agent in its folder (SM-74): the launcher's decisions, the worker's log, how the agent
 * keeps out of the server's way -- never the jar to launch, never in a backup, never restored over,
 * never deleted with more than its own `.pano-agent` -- and the service unit it writes.
 *
 * Three tests start real JVMs (the launcher, and in two of them its worker) and are the only ones
 * that do; none reaches beyond this machine: a worker with no pairing, or a retired one, exits
 * before it would connect, and the first run's questions stop before a worker starts.
 *
 * Also the first run as the launcher runs it (SM-76) -- when it asks, the retry after a refused
 * code, the defaults nobody is asked for -- and the worker's own copy of the jar that makes a
 * self-update work on Windows.
 */
class AgentFolderTest {
    @TempDir
    lateinit var hostDir: File

    private val quiet = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val vertx = Vertx.vertx()

    @AfterEach
    fun close() {
        vertx.close()
        scheduler.shutdownNow()
    }

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {}

        override fun onConsoleReady(server: ServerProcess) {}
    }

    /** A server folder as an admin has it: their server jar, a world, a plugin, and the agent's jar. */
    private fun serverFolder(): File {
        val folder = File(hostDir, "survival").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }

        File(folder, "paper-1.21.8.jar").writeBytes(ByteArray(4096))
        // Bigger than the server jar on purpose: "the largest jar" must still not pick it.
        File(folder, "pano-agent.jar").writeBytes(ByteArray(64 * 1024))
        File(folder, "server.properties").writeText("server-port=$port\n")
        File(folder, "world").mkdirs()
        File(folder, "world/level.dat").writeText("level")
        File(folder, "plugins").mkdirs()
        File(folder, "plugins/Essentials.jar").writeBytes(ByteArray(128))

        return folder
    }

    // ------------------------------------------------------------------------- the restart policy

    @Test
    fun `an update restarts the worker at once and a removal ends the agent cleanly`() {
        assertEquals(AgentRestartPolicy.Decision.Restart(0L, 0), AgentRestartPolicy.decide(75, 10, attempt = 3))

        val removed = AgentRestartPolicy.decide(78, 10, 0) as AgentRestartPolicy.Decision.Exit

        assertEquals(0, removed.exitCode)
        assertTrue(removed.message!!.contains("removed from Pano"), removed.message)
        assertTrue(removed.message!!.contains("pano-agent.jar can be deleted"), removed.message)

        assertEquals(AgentRestartPolicy.Decision.Exit(0), AgentRestartPolicy.decide(0, 10, 2))
    }

    @Test
    fun `a worker that cannot run as asked is not started again`() {
        listOf(2, NodeVersion.ALREADY_RUNNING_EXIT_CODE, NodeVersion.NOT_PAIRED_EXIT_CODE).forEach { code ->
            assertEquals(AgentRestartPolicy.Decision.Exit(code), AgentRestartPolicy.decide(code, 10, 0), "exit $code")
        }
    }

    @Test
    fun `a crashing worker is restarted after 1, 2, 5, 10 and then 30 seconds`() {
        var attempt = 0
        val delays = mutableListOf<Long>()

        repeat(7) {
            val decision = AgentRestartPolicy.decide(1, 1_000, attempt) as AgentRestartPolicy.Decision.Restart

            delays.add(decision.delayMillis)
            attempt = decision.nextAttempt
        }

        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L), delays)

        // Five minutes up and the next crash is the first again.
        assertEquals(
            AgentRestartPolicy.Decision.Restart(1_000L, 1),
            AgentRestartPolicy.decide(137, AgentRestartPolicy.STABLE_UPTIME_MILLIS, attempt = 6)
        )
    }

    // --------------------------------------------------------------------------- the worker's cli

    @Test
    fun `the worker gets the launcher's folders and the admin's own flags`() {
        val args = arrayOf("--agent", "--pano", "https://pano.example.com", "--code", "123456", "--data", "/x", "--name", "smp", "--server", "/y")

        assertEquals(
            listOf("--pano", "https://pano.example.com", "--code", "123456", "--name", "smp"),
            AgentLauncher.passThrough(args)
        )

        val layout = AgentLayout(File("/srv/smp"), File("/srv/smp/.pano-agent"))

        assertEquals(
            listOf(
                "/usr/bin/java", "-XX:+UseSerialGC", "-Xmx256m", "-jar", "/srv/smp/pano-agent.jar",
                "--agent-worker", "--data", "/srv/smp/.pano-agent", "--server", "/srv/smp", "--code", "1"
            ),
            AgentLauncher.workerCommand("/usr/bin/java", "/srv/smp/pano-agent.jar", "cp", layout, listOf("--code", "1"), null)
        )

        // The options can be replaced, and a launcher run from classes starts Main from them.
        val fromClasses = AgentLauncher.workerCommand("/usr/bin/java", null, "/cp", layout, emptyList(), " -Xmx512m  -Dx=y ")

        assertEquals(listOf("/usr/bin/java", "-Xmx512m", "-Dx=y", "-cp", "/cp", "com.panomc.node.Main"), fromClasses.take(6))
    }

    // ------------------------------------------------------------- the launcher, as a real process

    private fun javaBinary() = File(File(System.getProperty("java.home"), "bin"), HostPlatform.javaExecutable).absolutePath

    /** Starts the agent from classes in [folder]: stdin is `/dev/null`, or a pipe [input] is typed into. */
    private fun launch(folder: File, vararg args: String, input: String? = null): Pair<Int, String> {
        val process = ProcessBuilder(
            listOf(javaBinary(), "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"), "com.panomc.node.Main") +
                args
        )
            .directory(folder)
            .redirectErrorStream(true)
            .redirectInput(if (input == null) ProcessBuilder.Redirect.from(File("/dev/null")) else ProcessBuilder.Redirect.PIPE)
            .start()

        if (input != null) {
            process.outputStream.use { it.write(input.toByteArray()) }
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the agent must exit on its own")

        return process.exitValue() to output
    }

    @Test
    fun `the launcher loads no Vert-x, and a worker that is not linked ends the agent`() {
        assumeTrue(!HostPlatform.isWindows)

        val folder = serverFolder()

        val (exitCode, output) = launch(folder, "--agent")

        assertEquals(NodeVersion.NOT_PAIRED_EXIT_CODE, exitCode, output)

        // Only the launcher runs with -Xlog; the worker is started with its own small options.
        val loaded = output.lines().filter { it.contains("[class,load]") }

        assertTrue(loaded.isNotEmpty(), output.take(2000))
        assertTrue(loaded.none { it.contains(" io.vertx.") || it.contains(" io.netty.") }, loaded.filter { it.contains("io.") }.take(5).toString())

        assertTrue(output.contains("[Pano Agent] Pano Agent"), "the launcher introduces itself")
        assertTrue(output.contains("[Pano Agent] This Pano Agent is not linked to Pano yet"), "the worker says why, prefixed")

        // The worker's log went to the agent's folder, not into the console.
        val log = File(folder, ".pano-agent/agent.log")

        assertTrue(log.isFile)
        assertTrue(log.readText().contains("not linked to Pano yet"))
        assertFalse(output.contains("[INFO]"), "INFO lines stay in agent.log")
        assertFalse(output.contains("SLF4J"), "no library noise in the server console")
    }

    @Test
    fun `the first run's questions load no Vert-x either`() {
        assumeTrue(!HostPlatform.isWindows)

        val folder = serverFolder()

        File(folder, "start.sh").writeText("#!/bin/sh\njava -Xmx3G -XX:+UseG1GC -jar paper-1.21.8.jar nogui\n")

        val pano = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(AgentAddress.PROBE_PATH) { exchange ->
                val body = ("b".repeat(64) + "  pano-agent.jar\n").toByteArray()

                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

            start()
        }

        try {
            // Address, then Enter through the jar, memory, arguments and the summary, then q at the code.
            val (exitCode, output) = launch(folder, "--agent", input = "http://127.0.0.1:${pano.address.port}/panel\n\n\n\n\nq\n")

            assertEquals(0, exitCode, output)

            val loaded = output.lines().filter { it.contains("[class,load]") }

            assertTrue(loaded.any { it.contains(" com.panomc.node.agent.AgentSetup ") }, "the questions ran in the launcher")
            assertTrue(loaded.any { it.contains(" java.net.http.HttpClient ") }, "and checked the address")
            assertTrue(loaded.none { it.contains(" io.vertx.") || it.contains(" io.netty.") || it.contains(" com.google.gson.") }, loaded.filter { it.contains(" io.") || it.contains("gson") }.take(5).toString())

            val shown = output.lines().filterNot { it.contains("[class,load]") }.joinToString("\n")

            assertTrue(shown.contains("Found start.sh"), shown)
            assertTrue(shown.contains("Memory for the server [3G]:"), shown)
            assertTrue(shown.contains("  Pano address:    http://127.0.0.1:${pano.address.port}\n"), shown)
            assertTrue(shown.contains("Stopped; nothing was saved."), shown)
            assertFalse(File(folder, ".pano-agent/launch.json").exists())
        } finally {
            pano.stop(0)
        }
    }

    @Test
    fun `an agent Pano removed says so and exits cleanly`() {
        assumeTrue(!HostPlatform.isWindows)

        val folder = serverFolder()

        RetiredMarker.write(File(folder, ".pano-agent"), "https://pano.example.com", "survival")

        val (exitCode, output) = launch(folder, "--agent")

        assertEquals(0, exitCode, output)
        assertTrue(output.contains(AgentRestartPolicy.REMOVED_MESSAGE), output)
    }

    // ---------------------------------------------------------------------------- the worker's log

    @Test
    fun `the worker logs to its file and shows only what the admin needs`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val file = File(hostDir, "agent.log")

        val logger = NodeLogger("pano-agent", file = RotatingLogFile(file), terminal = PrintStream(out), terminalErrors = PrintStream(err))

        logger.info("announced 1 server")
        logger.warn("could not reach Pano")
        logger.error("pairing failed")
        logger.notice("Connected to Pano at https://pano.example.com.")

        val log = file.readText()

        listOf("announced 1 server", "could not reach Pano", "pairing failed", "Connected to Pano").forEach {
            assertTrue(log.contains(it), it)
        }

        assertEquals("[Pano Agent] Connected to Pano at https://pano.example.com.\n", out.toString().replace("\r\n", "\n"))
        assertEquals(
            "[Pano Agent] could not reach Pano\n[Pano Agent] pairing failed\n",
            err.toString().replace("\r\n", "\n")
        )
    }

    @Test
    fun `the agent's log rotates instead of growing forever`() {
        val file = File(hostDir, "agent.log")
        val log = RotatingLogFile(file, maxBytes = 100, keep = 2)

        repeat(40) { log.append("line number ${it.toString().padStart(4, '0')}") }

        assertTrue(file.length() <= 100)
        assertTrue(File(hostDir, "agent.log.1").isFile)
        assertTrue(File(hostDir, "agent.log.2").isFile)
        assertFalse(File(hostDir, "agent.log.3").exists())
        assertTrue(file.readText().contains("line number 0039"))
    }

    // ------------------------------------------------------------------------- the console tap

    @Test
    fun `the terminal gets the node's lines prefixed, and none it is told to keep out`() {
        val folder = serverFolder()
        val server = ServerProcess(
            "s1", folder, ServerSpec(uuid = "s1", jar = "paper-1.21.8.jar"),
            JavaRuntimeLocator(hostDir), ProcessRuntime(), scheduler, quiet, listener
        )

        val printed = mutableListOf<String>()

        server.consoleTap = { printed.add(it) }

        server.emit(ConsoleLevel.INFO, "Server process exited with code 0.")
        server.emit(ConsoleLevel.INFO, "[Pano:admin] > say hi")
        server.emit(ConsoleLevel.INFO, "[Pano:terminal] > list", tap = false)

        assertEquals(listOf("[Pano Agent] Server process exited with code 0.", "[Pano:admin] > say hi"), printed)
        assertEquals(3, server.console.snapshot().size, "Pano's console still gets every line")

        // A stopped server takes no command, from the terminal or anywhere, and echoes nothing.
        assertFalse(server.sendCommand("list", ServerProcess.TERMINAL_ISSUER))
        assertEquals(2, printed.size)
    }

    @Test
    fun `every server the registry holds or gets later is tapped`() {
        val registry = ServerRegistry(File(hostDir, "data"), JavaRuntimeLocator(hostDir), ProcessRuntime(), scheduler, quiet, listener)
            .apply { load() }

        val tap: (String) -> Unit = {}

        registry.consoleTap = tap

        val server = registry.register("s2", File(hostDir, "s2"), ServerSpec(uuid = "s2"))

        assertTrue(server.consoleTap === tap)
    }

    // ------------------------------------------------------------------------ adoption, own jar

    @Test
    fun `adoption never takes the agent's jar for the server's`() {
        val folder = serverFolder()

        assertEquals("paper-1.21.8.jar", ServerInspection.findServerJar(folder, agentJar = null))

        // A hosting panel that only starts server.jar: the agent is server.jar, the server is paper.
        File(folder, "pano-agent.jar").renameTo(File(folder, "server.jar"))

        assertEquals("paper-1.21.8.jar", ServerInspection.findServerJar(folder, agentJar = File(folder, "server.jar").canonicalFile))

        // Without the running jar to go by, server.jar would win outright, as it always has.
        assertEquals("server.jar", ServerInspection.findServerJar(folder, agentJar = null))
    }

    @Test
    fun `the agent's own data folder inside the server folder is allowed, and nothing else is`() {
        val folder = serverFolder().canonicalFile

        InPlaceAdoption.checkAllowed(folder, InPlaceAdoption.Host(File(folder, ".pano-agent"), windows = false, home = null))

        val refused = assertThrows(InPlaceAdoption.Refused::class.java) {
            InPlaceAdoption.checkAllowed(folder, InPlaceAdoption.Host(File(folder, "node-data"), windows = false, home = null))
        }

        assertEquals(InPlaceAdoption.PATH_NOT_ALLOWED, refused.code)
    }

    @Test
    fun `an agent is not stopped by the shells around it, only by a running server`() {
        val folder = serverFolder().canonicalFile
        val inFolder = { sequenceOf(4242L to folder.toPath()) }

        InPlaceAdoption.checkRunning(folder, inFolder) { false }

        val refused = assertThrows(InPlaceAdoption.Refused::class.java) {
            InPlaceAdoption.checkRunning(folder, inFolder) { true }
        }

        assertEquals(InPlaceAdoption.SERVER_RUNNING, refused.code)

        assertTrue(InPlaceAdoption.isJavaCommand("/usr/lib/jvm/java-21-openjdk/bin/java"))
        assertFalse(InPlaceAdoption.isJavaCommand("/usr/bin/bash"))
        assertFalse(InPlaceAdoption.isJavaCommand("/usr/bin/tail"))

        // The shell, screen or launcher this very process was started from is never the server.
        ProcessHandle.current().parent().ifPresent { parent ->
            assertFalse(InPlaceAdoption.isRunningServer(parent.pid()))
            assertFalse(InPlaceAdoption.isRunningServerForAgent(parent.pid()))
        }
    }

    @Test
    fun `an agent adopts its folder, skips its own jar, and starts it with the server`() {
        val folder = serverFolder().canonicalFile
        val data = File(folder, ".pano-agent")
        val registry = ServerRegistry(data, JavaRuntimeLocator(data), ProcessRuntime(), scheduler, quiet, listener).apply { load() }
        val config = NodeConfig()
        val connection = PlatformConnection(vertx, quiet, config)
        val reservations = PortReservations()
        val adopted = mutableListOf<ServerProcess>()
        var status: String? = null

        val sink = object : TaskSink {
            override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {}

            override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
                status = "DONE"
            }

            override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
                status = "FAILED: $error"
            }
        }

        ImportService(
            registry = registry,
            reporter = sink,
            connection = connection,
            transferService = TransferService(registry, config, quiet),
            loaderInstaller = LoaderInstaller(JavaRuntimeLocator(data), quiet),
            logger = quiet,
            dataDir = data,
            reservations = reservations,
            portResolver = PortResolver(registry, reservations, connection, quiet) { 40000..40100 },
            adoptionHost = InPlaceAdoption.Host(data, windows = false, home = null) { emptySequence() },
            agentServer = { folder.path },
            onAdopted = { adopted.add(it) }
        ).import(
            ImportServerMessage(
                serverUuid = "s1",
                taskId = "t1",
                mode = ImportService.MODE_IN_PLACE,
                folderPath = folder.path,
                // An older Pano says nothing about autoStart: an agent's server starts with it anyway.
                spec = ImportServerSpec(name = "survival", port = 0, acceptEula = false)
            )
        )

        assertEquals("DONE", status)
        assertEquals("paper-1.21.8.jar", registry.get("s1")?.spec?.jar)
        assertTrue(registry.get("s1")!!.spec.autoStart)
        assertEquals(listOf("s1"), adopted.map { it.uuid })
    }

    // ----------------------------------------------------------------- backups and the file manager

    @Test
    fun `the file manager never sees the agent's folder`() {
        assertTrue(ServerFileDenylist.isDenied(".pano-agent"))
        assertTrue(ServerFileDenylist.isDenied(".pano-agent/config.conf"))
        assertTrue(ServerFileDenylist.isDenied(".PANO-AGENT/backups/x.zip"))
        assertFalse(ServerFileDenylist.isDenied("pano-agent.jar"))
    }

    @Test
    fun `every backup leaves the agent out`() {
        val folder = serverFolder()

        File(folder, ".pano-agent/backups").mkdirs()
        File(folder, ".pano-agent/config.conf").writeText("secret")
        File(folder, ".pano-node").mkdirs()
        File(folder, ".pano-node/process.json").writeText("{}")

        val matcher = BackupExcludeMatcher(null, folder)

        assertTrue(matcher.matches(".pano-agent/config.conf"))
        assertTrue(matcher.matches("pano-agent.jar"))
        assertTrue(matcher.matches("Pano-Agent-1.2.jar"))
        assertFalse(matcher.matches("paper-1.21.8.jar"))
        assertFalse(matcher.matches("plugins/pano-agent.jar"), "only the folder's top level is the agent's")

        // With patterns of the operator's own, still.
        assertTrue(BackupExcludeMatcher(listOf("world_nether/"), folder).matches("pano-agent.jar"))

        // Every scope: the whole folder, and a list that names the agent on purpose.
        val all = BackupScope.resolve(folder, BackupScopeKind.ALL, null, matcher)

        assertFalse(all.roots.contains(".pano-agent"))
        assertFalse(all.roots.contains(".pano-node"))

        val custom = BackupScope.resolve(folder, BackupScopeKind.CUSTOM, listOf(".pano-agent", "world"), matcher)

        assertEquals(listOf("world"), custom.roots)

        val archive = File(hostDir, "backup.zip")

        ZipTool.archive(folder, listOf(""), archive) { matcher.matches(it) }

        val names = ZipFile(archive).use { zip -> zip.entries().toList().map { it.name } }

        assertTrue(names.contains("world/level.dat"), names.toString())
        assertTrue(names.contains("paper-1.21.8.jar"), names.toString())
        assertTrue(names.none { it.startsWith(".pano-agent") || it.startsWith(".pano-node") }, names.toString())
        assertFalse(names.contains("pano-agent.jar"), names.toString())
    }

    @Test
    fun `a restore never writes over the agent`() {
        val folder = serverFolder()
        val agentJar = File(folder, "pano-agent.jar")

        agentJar.writeText("the agent that is running")

        val archive = File(hostDir, "old.zip")

        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("pano-agent.jar" to "an old agent", ".pano-agent/config.conf" to "an old token", "world/level.dat" to "old level")
                .forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
        }

        ZipTool.extract(folder, archive, folder)

        assertEquals("the agent that is running", agentJar.readText())
        assertFalse(File(folder, ".pano-agent/config.conf").exists())
        assertEquals("old level", File(folder, "world/level.dat").readText())

        // The running jar under another name is the agent too.
        assertTrue(AgentFiles.isOwnJar(folder, "server.jar", runningJar = File(folder, "server.jar").canonicalFile))
        assertFalse(AgentFiles.isOwnJar(folder, "paper-1.21.8.jar", runningJar = File(folder, "server.jar").canonicalFile))
    }

    // --------------------------------------------------------------------------------- uninstall

    @Test
    fun `removing an agent deletes its own folder and nothing of the server's`() {
        val folder = serverFolder().canonicalFile
        val data = File(folder, ".pano-agent")

        File(data, "java/temurin-21/bin").mkdirs()
        File(data, "java/temurin-21/bin/java").writeBytes(ByteArray(1024))
        File(data, "backups/s1").mkdirs()
        File(data, "backups/s1/b1.zip").writeBytes(ByteArray(512))
        File(data, "config.conf").writeText("platform { url = \"x\" }")
        File(data, "agent.log").writeText("log")

        val registry = ServerRegistry(data, JavaRuntimeLocator(data), ProcessRuntime(), scheduler, quiet, listener).apply { load() }
        val spec = ServerSpec(uuid = "s1", name = "survival", jar = "paper-1.21.8.jar", inPlace = true)

        registry.writeSpec(folder, spec)
        registry.registerInPlace("s1", folder, spec)

        File(folder, ".pano-node").mkdirs()
        File(folder, ".pano-node/console.log").writeText("history")

        val frames = mutableListOf<Pair<String, JsonObject?>>()
        val environment = UninstallEnvironment(
            os = "linux", isRoot = false, inContainer = false, systemdUnit = null, selfServiceUnit = null,
            jarPath = File(folder, "pano-agent.jar").path, dataDir = data.path, user = "mc", agentFolder = folder.path
        )

        UninstallService(
            dataDir = data,
            registry = registry,
            reporter = object : TaskSink {
                override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {}

                override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
                    frames.add("DONE" to extra)
                }

                override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
                    frames.add("FAILED: $error" to extra)
                }
            },
            logger = quiet,
            environment = { environment },
            serviceInstaller = ServiceInstaller(data, quiet, AgentLayout(folder, data)),
            prepare = {},
            abort = {},
            forgetServer = {},
            onRetired = {},
            agentFolder = { folder.path },
            commands = UninstallService.CommandRunner { 0 }
        ).uninstall(NodeUninstallMessage("u1"))

        assertEquals("DONE", frames.last().first)

        // What Main does once the worker has stopped: everything but the marker, and the jar it runs from.
        RetiredMarker.clearAllBut(data, listOf(File(folder, "pano-agent.jar")))

        // The server is exactly the admin's again.
        assertEquals(4096, File(folder, "paper-1.21.8.jar").length())
        assertEquals("level", File(folder, "world/level.dat").readText())
        assertTrue(File(folder, "server.properties").isFile)
        assertTrue(File(folder, "plugins/Essentials.jar").isFile)
        assertTrue(File(folder, "pano-agent.jar").isFile, "the jar is the admin's to delete")

        // Only what Pano put there is gone: its settings, its runtime folder, and its own data.
        assertFalse(File(folder, ServerRegistry.SPEC_FILE).exists())
        assertFalse(File(folder, ".pano-node").exists())
        assertEquals(listOf(RetiredMarker.FILE_NAME), data.list()!!.toList())

        val steps = frames.last().second!!.getJsonArray("manualSteps").map { it as String }

        assertEquals("rm -rf ${data.path}", steps.first())
        assertTrue(steps.none { it == "rm -rf ${folder.path}" }, steps.toString())
    }

    @Test
    fun `a moved server folder is followed, a different one is not`() {
        val data = File(hostDir, "data").apply { mkdirs() }
        val old = File(hostDir, "old")
        val moved = File(hostDir, "moved").apply { mkdirs() }

        ExternalServerIndex.write(data, mapOf("s1" to old.path))

        assertTrue(ExternalServerIndex.followMove(data, "s1", moved))
        assertEquals(moved.path, ExternalServerIndex.read(data)!!["s1"])

        // The recorded folder still exists: that is another server, not a move.
        val other = File(hostDir, "other").apply { mkdirs() }

        assertFalse(ExternalServerIndex.followMove(data, "s1", other))
        assertFalse(ExternalServerIndex.followMove(data, "unknown", other))
        assertEquals(moved.path, ExternalServerIndex.read(data)!!["s1"])
    }

    // ------------------------------------------------------------------------------- the service

    @Test
    fun `the agent's service runs the launcher from the folder and restarts it only on a failure`() {
        val layout = AgentLayout(File("/home/mc/survival"), File("/home/mc/survival/.pano-agent"))

        val unit = ServiceInstaller.agentSystemdUnit(layout, "/home/mc/survival/pano-agent.jar", "/usr/bin/java", "mc")

        assertTrue(unit.contains("ExecStart=/usr/bin/java -jar /home/mc/survival/pano-agent.jar\n"), unit)
        assertTrue(unit.contains("WorkingDirectory=/home/mc/survival\n"), unit)
        assertTrue(unit.contains("User=mc\n"), unit)
        assertTrue(unit.contains("Restart=on-failure\n"), unit)
        assertTrue(unit.contains("KillMode=mixed\n"), unit)
        assertTrue(unit.contains("SuccessExitStatus=143\n"), unit)
        assertFalse(unit.contains("--agent-worker"), "the service runs the launcher, never the worker")

        // Renamed for a hosting panel, with its data elsewhere: the unit says what the name no longer does.
        val renamed = ServiceInstaller.agentSystemdUnit(
            AgentLayout(File("/srv/my server"), File("/var/lib/agent")), "/srv/my server/server.jar", "/usr/bin/java", "mc"
        )

        assertTrue(renamed.contains("ExecStart=/usr/bin/java -jar \"/srv/my server/server.jar\" --agent --data /var/lib/agent\n"), renamed)

        assumeTrue(HostPlatform.isLinux)

        val installer = ServiceInstaller(File(hostDir, ".pano-agent"), quiet, AgentLayout(hostDir, File(hostDir, ".pano-agent")))

        installer.install(File(hostDir, "pano-agent.jar").path, "/usr/bin/java")

        assertEquals("${AgentLayout.serviceName(hostDir)}.service", installer.unitFile().name)
        assertTrue(installer.unitFile().readText().contains("Description=Pano Agent for ${hostDir.path}"))
    }

    // ------------------------------------------------------------- the first run, launched (SM-76)

    /** A worker that is never started: it exits with [exit] the moment it is waited for. */
    private class FakeWorker(private val exit: Int) : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit

        override fun exitValue(): Int = exit

        override fun destroy() {}
    }

    /**
     * Somebody at the other end of the agent's stdin: each time a question is printed (a line that
     * ends with `:` or `[Y/n]`) the next of [answers] is typed, and once they run out, Ctrl+D --
     * the way the Python pty harness drives a live agent.
     */
    private class Typist(answers: List<String>) {
        private val pending = ArrayDeque(answers)
        private val bytes = LinkedBlockingQueue<Int>()
        private val line = ByteArrayOutputStream()
        private val transcript = ByteArrayOutputStream()

        val input: InputStream = object : InputStream() {
            override fun read(): Int = bytes.take().also { if (it < 0) bytes.put(it) }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0

                val first = bytes.take()

                if (first < 0) {
                    bytes.put(first)

                    return -1
                }

                buffer[offset] = first.toByte()

                var count = 1

                while (count < length) {
                    val next = bytes.peek() ?: break

                    if (next < 0) break

                    buffer[offset + count++] = bytes.poll().toByte()
                }

                return count
            }
        }

        val out = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                synchronized(this@Typist) {
                    transcript.write(b)

                    if (b != '\n'.code) {
                        line.write(b)

                        return
                    }

                    val text = line.toString(Charsets.UTF_8)

                    line.reset()

                    if (text.endsWith(":") || text.endsWith("[Y/n]")) {
                        val next = pending.removeFirstOrNull()

                        if (next == null) {
                            bytes.put(-1)
                        } else {
                            "$next\n".toByteArray().forEach { bytes.put(it.toInt() and 0xff) }
                        }
                    }
                }
            }
        }, true, "UTF-8")

        val output: String get() = synchronized(this) { transcript.toString(Charsets.UTF_8) }
    }

    private class Started(val command: List<String>, val environment: Map<String, String>)

    /** Runs a launcher for [folder] whose workers exit with [exits], one per start. */
    private fun launchWith(
        folder: File,
        args: Array<String>,
        stdin: AgentLauncher.Stdin,
        exits: List<Int>,
        typist: Typist = Typist(emptyList()),
        env: Map<String, String> = emptyMap(),
        jar: String? = null,
        onStart: (Int) -> Unit = {}
    ): Triple<Int?, List<Started>, String> {
        val remaining = ArrayDeque(exits)
        val started = mutableListOf<Started>()
        val layout = AgentLayout(folder.canonicalFile, File(folder.canonicalFile, ".pano-agent"))

        val launcher = AgentLauncher(
            layout = layout,
            passThrough = AgentLauncher.passThrough(args),
            jar = jar,
            version = "test",
            env = { env[it] },
            input = typist.input,
            out = typist.out,
            err = typist.out,
            options = NodeCli.parse(args) { env[it] },
            stdin = stdin,
            starter = AgentLauncher.WorkerStarter { command, environment, _ ->
                started.add(Started(command, environment))
                onStart(started.size)

                FakeWorker(remaining.removeFirstOrNull() ?: 0)
            },
            checkAddress = { AgentAddress.Check.Ok(it) },
            launcherMemoryMb = null,
            windows = false,
            shutdownHook = false
        )

        var result: Int? = null

        assertTimeoutPreemptively(Duration.ofSeconds(30)) { result = launcher.run() }

        return Triple(result, started, typist.output)
    }

    private val piped = AgentLauncher.Stdin(terminal = false, closed = false)

    private val devNull = AgentLauncher.Stdin(terminal = false, closed = true)

    private val atTerminal = AgentLauncher.Stdin(terminal = true, closed = false)

    @Test
    fun `when the first run asks, and when it does not`() {
        val mode = AgentLauncher.Companion::setupMode

        // Linked (or removed) already: a plain start just runs.
        assertEquals(AgentLauncher.SetupMode.NONE, mode(true, false, false, false, false, true))

        // Nothing given: every question, at a terminal or in a hosting panel's console.
        assertEquals(AgentLauncher.SetupMode.ASK, mode(false, false, false, false, false, true))
        assertEquals(AgentLauncher.SetupMode.ASK, mode(false, false, false, false, false, false))
        assertEquals(AgentLauncher.SetupMode.ASK, mode(false, true, false, false, false, false))

        // Address and code given: the launch questions at a terminal only.
        assertEquals(AgentLauncher.SetupMode.ASK, mode(false, true, true, false, false, true))
        assertEquals(AgentLauncher.SetupMode.DEFAULTS, mode(false, true, true, false, false, false))

        // Nobody to ask: the defaults with credentials, nothing (the worker's "not linked") without.
        assertEquals(AgentLauncher.SetupMode.DEFAULTS, mode(false, true, true, true, false, true))
        assertEquals(AgentLauncher.SetupMode.DEFAULTS, mode(false, true, true, false, true, false))
        assertEquals(AgentLauncher.SetupMode.NONE, mode(false, false, false, true, false, true))
        assertEquals(AgentLauncher.SetupMode.NONE, mode(false, true, false, false, true, false))

        // The environment counts exactly like the flags.
        val env = mapOf(NodeCli.ENV_URL to "https://p.example", NodeCli.ENV_PAIR_CODE to "c0de", NodeCli.ENV_NO_INPUT to "true")
        val options = NodeCli.parse(emptyArray()) { env[it] }

        assertEquals("https://p.example", options.platformUrl)
        assertEquals("c0de", options.pairingCode)
        assertTrue(options.noInput)
    }

    @Test
    fun `a folder is linked once its config holds a token, or Pano removed it`() {
        val data = File(hostDir, "data").apply { mkdirs() }

        assertFalse(AgentLauncher.isLinked(data))

        File(data, "config.conf").writeText(NodeConfigStore.render(NodeConfig(platformUrl = "https://p", agent = true)))

        assertFalse(AgentLauncher.isLinked(data), "a config without a token is not a link")

        File(data, "config.conf").writeText(NodeConfigStore.render(NodeConfig(platformUrl = "https://p", token = "t0k3n", encryptionKey = "k")))

        assertTrue(AgentLauncher.isLinked(data))

        File(data, "config.conf").writeText("platform.token = \"x\"\n")

        assertTrue(AgentLauncher.isLinked(data))

        File(data, "config.conf").delete()
        RetiredMarker.write(data, "https://p", "smp")

        assertTrue(AgentLauncher.isLinked(data))
    }

    @Test
    fun `a code Pano refused is asked for again while somebody is there`() {
        val folder = serverFolder()
        val data = File(folder.canonicalFile, ".pano-agent")

        val typist = Typist(listOf("https://pano.example.com", "", "3G", "", "", "first-code", "second-code"))

        val (exitCode, started, output) = launchWith(folder, arrayOf("--name", "smp"), piped, exits = listOf(77, 0), typist = typist) { start ->
            if (start == 1) {
                File(data, AgentLauncher.PAIRING_ERROR_FILE).writeText("Pano refused the pairing: INVALID_NODE_PAIRING_CODE")
            }
        }

        assertEquals(0, exitCode, output)
        assertEquals(2, started.size, output)

        assertEquals(listOf("--name", "smp", "--pano", "https://pano.example.com", "--code", "first-code"), started[0].command.takeLast(6))
        assertEquals(listOf("--name", "smp", "--pano", "https://pano.example.com", "--code", "second-code"), started[1].command.takeLast(6))

        assertTrue(output.contains("Pano did not accept that code (it may have expired"), output)
        assertFalse(File(data, AgentLauncher.PAIRING_ERROR_FILE).exists(), "read once, then gone")

        assertEquals(AgentLaunch("paper-1.21.8.jar", 3072, emptyList()), AgentLaunchReader.read(data))
    }

    @Test
    fun `a refused code with nobody answering ends the agent with 77`() {
        val folder = serverFolder()

        val typist = Typist(listOf("https://pano.example.com", "", "", "", "", "first-code", ""))

        val (exitCode, started, _) = launchWith(folder, emptyArray(), piped, exits = listOf(77), typist = typist)

        assertEquals(NodeVersion.NOT_PAIRED_EXIT_CODE, exitCode)
        assertEquals(1, started.size)
    }

    @Test
    fun `nobody to ask and nothing given starts the worker as before and asks nothing`() {
        val folder = serverFolder()

        val (exitCode, started, output) = launchWith(folder, arrayOf("--agent"), devNull, exits = listOf(77))

        assertEquals(NodeVersion.NOT_PAIRED_EXIT_CODE, exitCode)
        assertEquals(1, started.size)
        assertFalse(output.contains("Pano address"), output)
        assertFalse(File(folder, ".pano-agent/launch.json").exists())
        assertFalse(started[0].command.contains("--pano"), "nothing the admin did not give")
    }

    @Test
    fun `the environment's address and code count like the flags, and nobody to ask means the defaults`() {
        val folder = serverFolder()

        File(folder, "start.sh").writeText("java -Xmx6G -XX:+UseG1GC -jar paper-1.21.8.jar nogui\n")

        val env = mapOf(NodeCli.ENV_URL to "https://pano.example.com", NodeCli.ENV_PAIR_CODE to "c0de")

        // A pipe, not a terminal: a hosting panel started it with everything it needs.
        val (exitCode, started, output) = launchWith(folder, emptyArray(), piped, exits = listOf(0), env = env)

        assertEquals(0, exitCode, output)
        assertFalse(output.contains("Pano address"), output)
        assertFalse(output.contains("Pairing code"), output)
        assertTrue(
            output.contains(
                "[Pano Agent] Server jar paper-1.21.8.jar, memory 6G, Java arguments -XX:+UseG1GC (from start.sh). " +
                    "Change them later in the server's Startup settings in Pano."
            ),
            output
        )
        assertEquals(AgentLaunch("paper-1.21.8.jar", 6144, listOf("-XX:+UseG1GC")), AgentLaunchReader.read(File(folder, ".pano-agent")))

        // The worker gets them from the environment it inherits, exactly as before.
        assertFalse(started[0].command.contains("--pano"))
    }

    @Test
    fun `--no-input and PANO_AGENT_NO_INPUT never ask, even at a terminal`() {
        listOf(
            arrayOf("--no-input", "--pano", "https://pano.example.com", "--code", "c0de") to emptyMap(),
            arrayOf("--pano", "https://pano.example.com", "--code", "c0de") to mapOf(NodeCli.ENV_NO_INPUT to "true")
        ).forEachIndexed { index, (args, env) ->
            val folder = File(hostDir, "n$index").apply { mkdirs() }

            File(folder, "paper-1.21.8.jar").writeBytes(ByteArray(64))

            val (exitCode, started, output) = launchWith(folder, args, atTerminal, exits = listOf(0), env = env)

            assertEquals(0, exitCode, output)
            assertFalse(output.contains("Server jar ["), output)
            assertTrue(File(folder, ".pano-agent/launch.json").isFile)
            assertEquals(listOf("--pano", "https://pano.example.com", "--code", "c0de"), started[0].command.takeLast(4))
            assertFalse(started[0].command.contains("--no-input"), "a launcher flag, never the worker's")
        }

        // With nothing to link with, --no-input is the old "not linked" exit, not a question.
        val (exitCode, _, output) = launchWith(serverFolder(), arrayOf("--no-input"), atTerminal, exits = listOf(77))

        assertEquals(NodeVersion.NOT_PAIRED_EXIT_CODE, exitCode)
        assertFalse(output.contains("Pano address"), output)
    }

    @Test
    fun `a linked folder never asks`() {
        val folder = serverFolder()

        File(folder, ".pano-agent").mkdirs()
        File(folder, ".pano-agent/config.conf").writeText(NodeConfigStore.render(NodeConfig(platformUrl = "https://p", token = "t", encryptionKey = "k")))

        val (exitCode, started, output) = launchWith(folder, emptyArray(), atTerminal, exits = listOf(0))

        assertEquals(0, exitCode)
        assertEquals(1, started.size)
        assertFalse(output.contains("Pano address"), output)
    }

    @Test
    fun `the questions' address and code replace the ones given`() {
        assertEquals(
            listOf("--name", "smp", "--pano", "https://b", "--code", "2"),
            AgentLauncher.withCredentials(listOf("--pano", "https://a", "--name", "smp", "--code", "1", "--pairing-code", "0"), "https://b", "2")
        )
    }

    // ------------------------------------------------------------------- the worker's own jar

    @Test
    fun `the worker runs from its own copy, made on the first start and again when pano-agent jar changes`() {
        val folder = serverFolder()
        val data = File(folder, ".pano-agent")
        val launcherJar = File(folder, "pano-agent.jar").apply { writeText("agent v1") }
        val said = mutableListOf<String>()

        val jar = AgentWorkerJar(data, launcherJar, windows = false) { said.add(it) }

        assertEquals(File(data, "worker.jar"), jar.prepare())
        assertEquals("agent v1", jar.workerJar.readText())
        assertEquals(Sha256.of(launcherJar), File(data, AgentWorkerJar.SOURCE_SHA_FILE).readText().trim())

        // Nothing changed: nothing copied, nothing said.
        val copiedAt = jar.workerJar.lastModified()

        jar.workerJar.setLastModified(copiedAt - 60_000)
        jar.prepare()

        assertEquals(copiedAt - 60_000, jar.workerJar.lastModified())
        assertTrue(said.isEmpty(), said.toString())

        // The admin dropped a newer download in by hand: it is what the worker runs next.
        launcherJar.writeText("agent v2, downloaded by hand")

        jar.prepare()

        assertEquals("agent v2, downloaded by hand", jar.workerJar.readText())
        assertEquals(Sha256.of(launcherJar), File(data, AgentWorkerJar.SOURCE_SHA_FILE).readText().trim())
    }

    /** Stages [bytes] as update [version] the way the worker's SelfUpdateService does. */
    private fun stage(data: File, version: String, bytes: String, sha256: String? = null, file: File? = null): File {
        val updates = File(data, "updates").apply { mkdirs() }
        val staged = file ?: File(updates, "pano-node-$version.jar")

        staged.writeText(bytes)

        File(updates, "pending.json").writeText(
            JsonObject()
                .put("version", version)
                .put("file", staged.absolutePath)
                .put("sha256", sha256 ?: Sha256.of(staged))
                .put("target", File(data, "worker.jar").absolutePath)
                .encode()
        )

        return staged
    }

    @Test
    fun `the launcher applies a staged update itself, and keeps a locked launcher jar on Windows`() {
        val folder = serverFolder()
        val data = File(folder, ".pano-agent")
        val launcherJar = File(folder, "pano-agent.jar").apply { writeText("agent v1") }
        val said = mutableListOf<String>()

        val windows = AgentWorkerJar(data, launcherJar, windows = true) { said.add(it) }

        windows.prepare()

        // What a Windows worker leaves behind: its on-exit move failed, the record is still there.
        val staged = stage(data, "2.0", "agent v2")

        windows.prepare()

        assertEquals("agent v2", windows.workerJar.readText())
        assertFalse(staged.exists())
        assertFalse(File(data, "updates/pending.json").exists())
        assertEquals("agent v1", launcherJar.readText(), "Windows keeps the jar the launcher runs from")
        assertTrue(said.contains("Applied the agent update 2.0."), said.toString())

        // And the next start does not copy the old launcher jar back over the update.
        windows.prepare()

        assertEquals("agent v2", windows.workerJar.readText())

        // Linux and macOS: the launcher's jar follows, so the next full start runs the new launcher.
        val posix = AgentWorkerJar(data, launcherJar, windows = false) { said.add(it) }

        posix.prepare()

        assertEquals("agent v2", launcherJar.readText())
        assertEquals(Sha256.of(launcherJar), File(data, AgentWorkerJar.SOURCE_SHA_FILE).readText().trim())
        assertTrue(said.contains("Updated pano-agent.jar to the agent's new version."), said.toString())
        assertFalse(File(folder, ".pano-agent.jar.tmp").exists())

        posix.prepare()

        assertEquals("agent v2", posix.workerJar.readText())
    }

    @Test
    fun `a staged update that does not match its checksum is thrown away`() {
        val folder = serverFolder()
        val data = File(folder, ".pano-agent")
        val launcherJar = File(folder, "pano-agent.jar").apply { writeText("agent v1") }
        val said = mutableListOf<String>()

        val jar = AgentWorkerJar(data, launcherJar, windows = true) { said.add(it) }

        jar.prepare()

        val staged = stage(data, "3.0", "agent v3, tampered with", sha256 = "0".repeat(64))

        assertFalse(jar.applyPending())
        assertFalse(staged.exists())
        assertFalse(File(data, "updates/pending.json").exists())
        assertEquals("agent v1", jar.workerJar.readText())
        assertEquals(listOf("The staged update 3.0 did not match its checksum; deleted it. Pano offers it again."), said)

        // A record naming a file outside the agent's folder moves nothing.
        val outside = File(hostDir, "elsewhere.jar")

        stage(data, "4.0", "not ours", file = outside)

        assertFalse(jar.applyPending())
        assertTrue(outside.isFile)
        assertEquals("agent v1", jar.workerJar.readText())

        assertEquals("C:\\agent\\x.jar", AgentWorkerJar.jsonField("{\"file\":\"C:\\\\agent\\\\x.jar\",\"v\":\"1\"}", "file"))
        assertNull(AgentWorkerJar.jsonField("{}", "file"))
    }

    @Test
    fun `the launcher starts the worker from its copy, tells it the admin's jar, and clears the copy on removal`() {
        val folder = serverFolder()
        val launcherJar = File(folder, "pano-agent.jar").apply { writeText("agent v1") }

        val (exitCode, started, _) = launchWith(folder, arrayOf("--agent"), devNull, exits = listOf(78), jar = launcherJar.absolutePath)

        assertEquals(0, exitCode)

        val command = started.single().command

        assertEquals(File(folder.canonicalFile, ".pano-agent/worker.jar").path, command[command.indexOf("-jar") + 1])
        assertEquals(launcherJar.absolutePath, started.single().environment[AgentFiles.ENV_LAUNCHER_JAR])

        // Removed from Pano (78): the copy goes too.
        assertFalse(File(folder, ".pano-agent/worker.jar").exists())
        assertFalse(File(folder, ".pano-agent/${AgentWorkerJar.SOURCE_SHA_FILE}").exists())
    }

    @Test
    fun `the worker's copy is never in a backup, the file manager or the jar list`() {
        val folder = serverFolder()

        File(folder, ".pano-agent").mkdirs()
        File(folder, ".pano-agent/worker.jar").writeBytes(ByteArray(128 * 1024))

        assertTrue(ServerFileDenylist.isDenied(".pano-agent/worker.jar"))
        assertTrue(BackupExcludeMatcher(null, folder).matches(".pano-agent/worker.jar"))
        assertFalse(BackupScope.resolve(folder, BackupScopeKind.ALL, null, BackupExcludeMatcher(null, folder)).roots.contains(".pano-agent"))
        assertEquals(listOf("paper-1.21.8.jar"), ServerJars.candidates(folder, agentJar = null))
    }

    // ----------------------------------------------------------- adoption with the chosen jar

    @Test
    fun `an agent adopts the jar its first run named, and detects one when that is gone`() {
        val folder = serverFolder().canonicalFile
        val data = File(folder, ".pano-agent")

        // Smaller than the paper jar, so detection alone would never pick it.
        File(folder, "velocity-3.4.jar").writeBytes(ByteArray(1024))

        AgentLaunch.write(data, AgentLaunch("velocity-3.4.jar", 4096, listOf("-XX:+UseG1GC")))

        assertEquals("velocity-3.4.jar", adoptInPlace(folder, data, "s1"))

        val other = File(hostDir, "other").apply { mkdirs() }.canonicalFile
        val otherData = File(other, ".pano-agent")

        File(other, "paper-1.21.8.jar").writeBytes(ByteArray(4096))
        AgentLaunch.write(otherData, AgentLaunch("gone.jar", 4096, emptyList()))

        assertEquals("paper-1.21.8.jar", adoptInPlace(other, otherData, "s2"))
    }

    /** Adopts [folder] in place as an agent would, and returns the jar its server runs. */
    private fun adoptInPlace(folder: File, data: File, uuid: String): String? {
        val registry = ServerRegistry(data, JavaRuntimeLocator(data), ProcessRuntime(), scheduler, quiet, listener).apply { load() }
        val config = NodeConfig()
        val connection = PlatformConnection(vertx, quiet, config)
        val reservations = PortReservations()
        var status: String? = null

        val sink = object : TaskSink {
            override fun running(taskId: String, serverUuid: String?, kind: String, percent: Int, message: String?) {}

            override fun done(taskId: String, serverUuid: String?, kind: String, message: String?, extra: JsonObject?) {
                status = "DONE"
            }

            override fun failed(taskId: String, serverUuid: String?, kind: String, error: String, extra: JsonObject?) {
                status = "FAILED: $error"
            }
        }

        ImportService(
            registry = registry,
            reporter = sink,
            connection = connection,
            transferService = TransferService(registry, config, quiet),
            loaderInstaller = LoaderInstaller(JavaRuntimeLocator(data), quiet),
            logger = quiet,
            dataDir = data,
            reservations = reservations,
            portResolver = PortResolver(registry, reservations, connection, quiet) { 40000..40100 },
            adoptionHost = InPlaceAdoption.Host(data, windows = false, home = null) { emptySequence() },
            agentServer = { folder.path }
        ).import(
            ImportServerMessage(
                serverUuid = uuid,
                taskId = "t-$uuid",
                mode = ImportService.MODE_IN_PLACE,
                folderPath = folder.path,
                spec = ImportServerSpec(name = folder.name, port = 0, acceptEula = false)
            )
        )

        assertEquals("DONE", status)

        return registry.get(uuid)?.spec?.jar
    }
}
