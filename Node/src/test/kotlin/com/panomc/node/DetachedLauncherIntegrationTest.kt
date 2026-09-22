package com.panomc.node

import com.panomc.node.console.ConsoleFileTailer
import com.panomc.node.host.JavaRuntimeLocator
import com.panomc.node.server.DetachedFiles
import com.panomc.node.server.DetachedServer
import com.panomc.node.server.ExitFile
import com.panomc.node.server.Fifo
import com.panomc.node.server.LauncherScript
import com.panomc.node.server.ProcessAdoption
import com.panomc.node.server.ProcessRecord
import com.panomc.node.server.ProcessRecordStore
import com.panomc.node.server.ProcessRuntime
import com.panomc.node.server.ServerProcess
import com.panomc.node.server.ServerProcessListener
import com.panomc.node.server.ServerProcessState
import com.panomc.node.server.ServerRegistry
import com.panomc.node.util.NodeLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The launcher for real (SM-62, §2.4.27): `/bin/sh`, a real FIFO, and a tiny `sh` "server" that
 * answers its stdin. One daemon's worth of handles starts it and lets go; the registry then finds
 * it from `process.json` alone, exactly as a restarted daemon would, and takes over input, output
 * and the exit.
 *
 * Skipped where there is no POSIX shell or no mkfifo (Windows).
 */
class DetachedLauncherIntegrationTest {
    @TempDir
    lateinit var dataDir: File

    private val logger = NodeLogger("test", PrintStream(ByteArrayOutputStream()))

    private val scheduler = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }

    private val states = CopyOnWriteArrayList<ServerProcessState>()

    private val listener = object : ServerProcessListener {
        override fun onState(server: ServerProcess, state: ServerProcessState, exitCode: Int?, pid: Long?, since: Long) {
            states.add(state)
        }

        override fun onConsoleReady(server: ServerProcess) {}
    }

    private val started = mutableListOf<ProcessHandle>()

    private var registry: ServerRegistry? = null

    @BeforeEach
    fun requirePosix() {
        assumeTrue(ProcessRuntime.SHELL.canExecute(), "no /bin/sh")
        assumeTrue(Fifo.create(File(dataDir, "probe")), "no mkfifo")
    }

    @AfterEach
    fun cleanUp() {
        registry?.shutdown()

        started.forEach { handle ->
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        }

        scheduler.shutdownNow()
    }

    @Test
    fun `survives the daemon letting go, and a new one takes over input, output and the exit`() {
        val (directory, files) = serverDirectory("live")
        val launch = launch(files)

        // The first daemon: talks to it, then lets go with one line written but not yet read.
        val seen = mutableListOf<String>()
        val first = DetachedServer(files, launch.launcher, launch.java, ConsoleFileTailer(files.output, files.rotatedOutput, 0) { seen.addAll(it) })

        assertTrue(first.writeLine("hello"))
        waitUntil { first.tailer.poll(); "got: hello" in seen }

        val offset = first.tailer.committedOffset

        assertTrue(first.writeLine("while away"))
        first.closeWriter()
        waitUntil { files.output.readText().contains("got: while away") }

        ProcessRecordStore.write(directory, record(launch, offset))

        // No daemon holds the FIFO now; the server must not have read end-of-file.
        Thread.sleep(300)
        assertTrue(launch.java.isAlive)
        assertFalse(files.output.readText().contains("EOF"))

        // The second daemon.
        val server = load("live")

        assertEquals(ServerProcessState.RUNNING, server.state)
        assertFalse(server.adopted)
        assertTrue(server.stdinAvailable)
        assertEquals(launch.java.pid(), server.pid)

        // What was printed while away arrives once; what the first daemon read does not repeat.
        waitUntil { console(server).any { it.contains("got: while away") } }
        assertEquals(0, console(server).count { it.contains("got: hello") })

        assertTrue(server.sendCommand("ping", "tester"))
        waitUntil { console(server).any { it.contains("got: ping") } }

        // Stop is the console command, through the FIFO; the launcher books the code.
        server.stop("tester")
        waitUntil { server.state == ServerProcessState.STOPPED }

        assertEquals(0, server.lastExitCode)
        assertEquals(1, console(server).count { it.contains("got: while away") })
        assertNull(ProcessRecordStore.read(directory))
        assertFalse(files.exit.exists())
        assertTrue(launch.launcher.onExit().get(5, TimeUnit.SECONDS) != null)
    }

    @Test
    fun `a crash after re-attach goes down the normal path with its exit code`() {
        val (directory, files) = serverDirectory("crashy")
        val launch = launch(files)

        ProcessRecordStore.write(directory, record(launch, 0))

        val server = load("crashy")

        assertEquals(ServerProcessState.RUNNING, server.state)

        assertTrue(server.sendCommand("boom", "tester"))
        waitUntil { server.state == ServerProcessState.CRASHED }

        assertEquals(3, server.lastExitCode)
        assertEquals(listOf(ServerProcessState.RUNNING, ServerProcessState.CRASHED), states.toList())
        assertNull(ProcessRecordStore.read(directory))
    }

    @Test
    fun `an exit while no daemon was running is booked from the exit file`() {
        val (directory, files) = serverDirectory("away")
        val launch = launch(files)

        ProcessRecordStore.write(directory, record(launch, 0))

        RandomAccessFile(files.stdin, "rw").use { it.write("boom\n".toByteArray()) }
        launch.launcher.onExit().get(10, TimeUnit.SECONDS)

        assertEquals(3, ExitFile.read(files.exit))

        val server = load("away")

        assertEquals(ServerProcessState.CRASHED, server.state)
        assertEquals(3, server.lastExitCode)
        assertTrue(console(server).any { it.contains("Done (") }, "what it printed is replayed")
        assertNull(ProcessRecordStore.read(directory))
        assertFalse(files.exit.exists())
    }

    @Test
    fun `the launcher forwards TERM to the server and exits with its code`() {
        val (_, files) = serverDirectory("term")
        val launch = launch(files)

        launch.launcher.destroy()
        launch.launcher.onExit().get(10, TimeUnit.SECONDS)

        assertFalse(launch.java.isAlive)
        assertEquals(143, ExitFile.read(files.exit))
    }

    // ---- helpers ------------------------------------------------------------------------------

    private class Launch(val launcher: ProcessHandle, val java: ProcessHandle)

    private fun serverDirectory(uuid: String): Pair<File, DetachedFiles> {
        val directory = File(File(dataDir, "servers"), uuid).apply { mkdirs() }

        File(directory, "server.json").writeText(
            """{"uuid":"$uuid","name":"$uuid","software":"paper","jar":"server.sh","memoryMb":1024,"port":25565,"crashRestart":false}"""
        )

        File(directory, "server.sh").writeText(SERVER)

        val files = DetachedFiles(directory)

        files.directory.mkdirs()
        assertTrue(Fifo.create(files.stdin))
        files.launcher.writeText(LauncherScript.CONTENT)

        return directory to files
    }

    private fun launch(files: DetachedFiles): Launch {
        val directory = files.directory.parentFile

        val launcher = ProcessBuilder("/bin/sh", files.launcher.absolutePath, "/bin/sh", File(directory, "server.sh").absolutePath)
            .directory(directory)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(files.launcherLog))
            .redirectErrorStream(true)
            .start()
            .toHandle()

        started.add(launcher)

        var java: ProcessHandle? = null
        val deadline = System.currentTimeMillis() + 5_000

        while (java == null && System.currentTimeMillis() < deadline) {
            java = launcher.children().findFirst().orElse(null)
            Thread.sleep(20)
        }

        assertNotNull(java, "the launcher never started the server")

        waitUntil { files.output.isFile && files.output.readText().contains("Done (") }

        return Launch(launcher, java!!)
    }

    private fun record(launch: Launch, offset: Long) = ProcessRecord(
        pid = launch.java.pid(),
        startedAt = ProcessAdoption.startedAtOf(launch.java) ?: System.currentTimeMillis(),
        command = "server.sh",
        runtime = ProcessRuntime.ID,
        io = ProcessRecord.IO_DETACHED,
        launcherPid = launch.launcher.pid(),
        javaPid = launch.java.pid(),
        outOffset = offset
    )

    private fun load(uuid: String): ServerProcess {
        val registry = ServerRegistry(dataDir, JavaRuntimeLocator(dataDir), ProcessRuntime(), scheduler, logger, listener)

        this.registry = registry

        registry.load()

        return registry.get(uuid)!!
    }

    private fun console(server: ServerProcess) = server.console.snapshot().map { it.m }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000

        while (System.currentTimeMillis() < deadline && !condition()) {
            Thread.sleep(20)
        }

        assertTrue(condition(), "never got there")
    }

    private companion object {
        /** A "server": says it is ready, answers its stdin, stops on `stop`, crashes on `boom`. */
        val SERVER = """
            |echo 'Done (0.1s)! For help, type "help"'
            |while IFS= read -r line; do
            |  case "${'$'}line" in
            |    stop) echo 'Stopping server'; exit 0 ;;
            |    boom) echo 'Exception in server tick loop'; exit 3 ;;
            |    *) echo "got: ${'$'}line" ;;
            |  esac
            |done
            |echo EOF
            |exit 4
            |""".trimMargin()
    }
}
