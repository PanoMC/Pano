package com.panomc.node.server

import com.panomc.node.console.ConsoleFileTailer
import com.panomc.node.host.HostPlatform
import com.panomc.node.host.ProcessMetrics
import com.panomc.node.net.PlatformEndpoint
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The runtime this daemon has always had: one JVM per server, started by this process.
 *
 * Extracted from [ServerProcess] unchanged (SM-43). The handle it returns *is* the server, so
 * stopping it is a signal and its CPU time is the server's CPU time — which is exactly what the
 * Docker runtime cannot assume, and therefore what the interface had to stop assuming.
 */
class ProcessRuntime : ServerRuntime {
    override val id = ID

    override fun verify() {
        // A JVM is how this daemon is running in the first place; there is nothing to check.
    }

    override fun launch(request: ServerRuntime.LaunchRequest): Process {
        val builder = ProcessBuilder(
            ServerProcess.buildCommand(request.javaPath, request.jarName, request.spec, request.javaMajor)
        )

        builder.directory(request.directory)
        builder.redirectErrorStream(false)

        ServerProcess.sanitizeChildEnvironment(builder.environment())

        return builder.start()
    }

    /**
     * Starts the server through the launcher (SM-62, §2.4.27), or null where that cannot be done —
     * Windows, a host with no `/bin/sh`, a FIFO that could not be made — and the caller starts it
     * the old way instead.
     *
     * The launcher gets no pipes from this daemon at all: stdin is `/dev/null`, its own output goes
     * to `launcher.log`. That is what lets it outlive the daemon — a child holding a pipe to a dead
     * parent is one write away from a SIGPIPE — and the server's console reaches the daemon through
     * the FIFO and `console.out` instead.
     */
    override fun launchDetached(request: ServerRuntime.LaunchRequest): DetachedLaunch? {
        if (HostPlatform.isWindows || !SHELL.canExecute()) {
            return null
        }

        val files = DetachedFiles(request.directory)

        files.directory.mkdirs()

        if (!Fifo.create(files.stdin)) {
            return null
        }

        ExitFile.clear(files.exit)

        // A fresh run starts a fresh file: whatever the last run printed is already in the
        // console history, and replaying it into this one would read as this server's boot.
        files.output.writeText("")
        files.launcherLog.writeText("")

        files.launcher.writeText(LauncherScript.CONTENT)
        ownerOnly(files.launcher, executable = true)

        val builder = ProcessBuilder(
            listOf(SHELL.path, files.launcher.absolutePath) +
                ServerProcess.buildCommand(request.javaPath, request.jarName, request.spec, request.javaMajor)
        )

        builder.directory(request.directory)
        builder.redirectInput(ProcessBuilder.Redirect.from(File(DEV_NULL)))
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(files.launcherLog))
        builder.redirectErrorStream(true)

        ServerProcess.sanitizeChildEnvironment(builder.environment())

        val launcher = builder.start()

        return DetachedLaunch(launcher, awaitChild(launcher.toHandle()), files)
    }

    /**
     * Takes back a server started through the launcher (SM-62).
     *
     * The JVM has to pass the same checks an adoption does (a live pid whose start time and command
     * line match the record), because a pid alone is a number a busy host hands out again. When it
     * is gone, the exit file says how it ended and `console.out` what it said on the way.
     */
    override fun reattach(uuid: String, directory: File, record: ProcessRecord): Reattachment? {
        if (!record.runtime.equals(ID, ignoreCase = true) || !record.isDetached) {
            return null
        }

        val files = DetachedFiles(directory)
        val offset = record.outOffset ?: 0L

        val java = ProcessAdoption.handleFor(record.copy(pid = record.javaPid ?: record.pid), directory)

        val launcher = record.launcherPid
            ?.let { ProcessHandle.of(it).orElse(null) }
            ?.takeIf { it.isAlive && isLauncherOf(it, files) }

        if (java != null) {
            return Reattachment.Detached(launcher, java, files, offset)
        }

        // A launcher whose server just exited is a moment away from writing the exit code.
        launcher?.let {
            try {
                it.onExit().get(LAUNCHER_EXIT_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }

        return Reattachment.Exited(ExitFile.read(files.exit), ConsoleFileTailer.readLines(files.output, offset))
    }

    override fun terminate(uuid: String, process: Process, timeoutSeconds: Long): Boolean {
        process.destroy()

        return process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    }

    override fun kill(uuid: String, process: Process) {
        process.destroyForcibly()
    }

    override fun remove(uuid: String) {
        // The files are the whole of it, and removing those is the delete task's job.
    }

    // A pid outlives the daemon that spawned it and means nothing on its own, so the handle is
    // only accepted once [ProcessAdoption] has agreed it is still the process the record names.
    override fun adopt(uuid: String, directory: File, record: ProcessRecord): AdoptedProcess? {
        if (!record.runtime.equals(ID, ignoreCase = true)) {
            return null
        }

        return ProcessAdoption.handleFor(record, directory)?.let { AdoptedHandle(it) }
    }

    // The server is a child of this daemon on this host, so whatever address the node reaches
    // Pano on is an address the plugin reaches it on too. Nothing to translate.
    override fun pluginEndpoint(nodeEndpoint: PlatformEndpoint?): PlatformEndpoint? = nodeEndpoint

    override fun sample(
        uuid: String,
        process: Process,
        metrics: ServerRuntime.ProcessSampler
    ): ServerRuntime.Sample {
        val handle = process.toHandle()

        if (!handle.isAlive) {
            return ServerRuntime.Sample(null, null)
        }

        return ServerRuntime.Sample(
            cpuPercent = metrics.cpuPercent(handle),
            residentBytes = ProcessMetrics.residentBytes(handle.pid())
        )
    }

    /** The JVM the launcher forks, which takes a few milliseconds to appear. */
    private fun awaitChild(launcher: ProcessHandle): ProcessHandle? {
        val deadline = System.currentTimeMillis() + CHILD_WAIT_MILLIS

        while (System.currentTimeMillis() < deadline) {
            val child = try {
                launcher.children().findFirst().orElse(null)
            } catch (_: Exception) {
                null
            }

            if (child != null) {
                return child
            }

            if (!launcher.isAlive) {
                return null
            }

            try {
                Thread.sleep(CHILD_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()

                return null
            }
        }

        return null
    }

    /** Whether [handle] is running this server's launcher script, the guard against a reused pid. */
    private fun isLauncherOf(handle: ProcessHandle, files: DetachedFiles): Boolean {
        val commandLine = ProcessAdoption.commandLineOf(handle) ?: return true

        return commandLine.contains(files.launcher.absolutePath)
    }

    companion object {
        const val ID = "PROCESS"

        /** The shell the launcher runs under. POSIX guarantees it at this path. */
        val SHELL = File("/bin/sh")

        private const val DEV_NULL = "/dev/null"
        private const val CHILD_WAIT_MILLIS = 3_000L
        private const val CHILD_POLL_MILLIS = 20L
        private const val LAUNCHER_EXIT_GRACE_SECONDS = 2L
    }
}
