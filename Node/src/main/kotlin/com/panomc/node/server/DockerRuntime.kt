package com.panomc.node.server

import com.panomc.node.host.HostPlatform
import com.panomc.node.net.PlatformEndpoint
import com.panomc.node.util.NodeLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs every managed server in its own container, through the `docker` CLI (SM-43, §2.4.12).
 *
 * The CLI rather than the socket API on purpose: the daemon already has to be able to launch
 * processes with argument lists and read their output, the CLI is what an operator debugging this
 * would type by hand, and depending on a Docker Java client would put a large library and a
 * second authentication story into a daemon whose whole point is being small enough to trust.
 *
 * Files and backups keep working untouched, because the server directory is bind-mounted rather
 * than copied: the file manager, the backup service and the installer all go on operating on host
 * paths and never learn that a container exists. `--user` is what keeps that true — without it
 * every file the server writes would be owned by root and the node could not read its own backups.
 *
 * The one thing that is genuinely different is what a process handle means. `docker start -a -i`
 * is a client attached to a container: killing it detaches, it does not stop the server. So every
 * verb that ends something is a `docker` command addressed to the container by name, and only the
 * console pipeline uses the local handle.
 */
class DockerRuntime(
    private val logger: NodeLogger,
    /** Starts a long-lived `docker` client; the flag says whether its output is to be discarded. */
    private val starter: (List<String>, Boolean) -> Process = ::startClient,
    // Last, so `DockerRuntime(logger) { … }` keeps meaning the runner, as it always has.
    private val runner: (List<String>) -> CommandResult = ::run
) : ServerRuntime {
    override val id = ID

    /** Images already known to be present, so a pull is attempted once per image per boot. */
    private val pulledImages = mutableSetOf<String>()

    override fun verify() {
        val result = runner(DockerCommands.versionArgs())

        if (!result.ok) {
            throw IllegalStateException(
                "This node was started with --runtime DOCKER but `docker version` failed: " +
                    "${result.output.ifBlank { "command not found" }}. Install Docker and make sure " +
                    "this user may use its socket, or start the node without --runtime."
            )
        }

        logger.info("Docker runtime ready (server ${result.output.trim().ifBlank { "unknown" }}).")
    }

    override fun launch(request: ServerRuntime.LaunchRequest): Process {
        val image = DockerCommands.imageFor(request.javaMajor)

        ensureImage(image, request.onProgress)

        // A container left over from a previous run holds the name and the published port. It is
        // removed rather than reused because its command line was built from the spec as it was
        // then, and a memory or port change since would silently not apply.
        runner(DockerCommands.removeArgs(request.uuid))

        val created = runner(
            DockerCommands.createArgs(
                uuid = request.uuid,
                image = image,
                spec = request.spec,
                hostDirectory = request.directory.absolutePath,
                jarName = request.jarName,
                port = request.spec.port,
                user = posixUser()
            )
        )

        if (!created.ok) {
            throw IllegalStateException("Could not create the container for this server: ${created.output}")
        }

        val builder = ProcessBuilder(DockerCommands.startArgs(request.uuid))

        builder.directory(request.directory)
        builder.redirectErrorStream(false)

        ServerProcess.sanitizeChildEnvironment(builder.environment())

        return builder.start()
    }

    override fun terminate(uuid: String, process: Process, timeoutSeconds: Long): Boolean {
        val result = runner(DockerCommands.stopArgs(uuid, timeoutSeconds))

        if (!result.ok) {
            logger.warn("docker stop failed for $uuid: ${result.output}")
        }

        // The attached client exits once the container does, so waiting on it is still how this
        // node learns the server is gone.
        return process.waitFor(timeoutSeconds + CLIENT_GRACE_SECONDS, TimeUnit.SECONDS)
    }

    override fun kill(uuid: String, process: Process) {
        runner(DockerCommands.killArgs(uuid))

        process.destroyForcibly()
    }

    override fun remove(uuid: String) {
        runner(DockerCommands.removeArgs(uuid))
    }

    /**
     * Takes back a container started by an earlier daemon, with full control (SM-62, §2.4.27).
     *
     * Input through a fresh `docker attach` (never `docker exec`: a command has to reach the game's
     * own stdin), output through `docker logs --follow` from the last line the previous daemon saw,
     * the exit through the attach client ending with the container's code. A container that is no
     * longer running is booked from `docker inspect`, with whatever it printed in the meantime.
     */
    override fun reattach(uuid: String, directory: File, record: ProcessRecord): Reattachment? {
        if (!record.runtime.equals(ID, ignoreCase = true) || !record.isDetached) {
            return null
        }

        val since = record.logsSince ?: (record.startedAt * NANOS_PER_MILLI)

        if (AdoptedContainer(uuid, runner).isAlive()) {
            val attach = starter(DockerCommands.attachArgs(uuid), true)
            val logs = starter(DockerCommands.logsArgs(uuid, since, follow = true), false)

            return Reattachment.Container(attach, logs, since)
        }

        val code = runner(DockerCommands.inspectExitCodeArgs(uuid))
            .takeIf { it.ok }
            ?.let { DockerCommands.parseExitCode(it.output) }

        val logs = runner(DockerCommands.logsArgs(uuid, since, follow = false))

        val replay = if (logs.ok) {
            logs.output.lineSequence()
                .map { DockerCommands.splitTimestamp(it) }
                .filter { (at, _) -> at == null || at > since }
                .map { it.second }
                .toList()
                .takeLast(REPLAY_MAX_LINES)
        } else {
            emptyList()
        }

        return Reattachment.Exited(code, replay)
    }

    /**
     * Re-attaches to a container that outlived the daemon (SM-51).
     *
     * The container name *is* the record here: a pid would be the attached client's, and that
     * died with the old daemon. The name is derived from the uuid rather than read out of the
     * record, so a record that names some other container cannot make this node act on it.
     */
    override fun adopt(uuid: String, directory: File, record: ProcessRecord): AdoptedProcess? {
        if (!record.runtime.equals(ID, ignoreCase = true)) {
            return null
        }

        val container = AdoptedContainer(uuid, runner)

        return container.takeIf { it.isAlive() }
    }

    /**
     * Points the installed plugin at the host rather than at the container's own loopback.
     *
     * This is the bug a live test found: a node on a VPS reaches its Pano at `127.0.0.1:18088`,
     * that address went into the plugin config verbatim, and inside the container it named the
     * container -- so a freshly created managed server sat there failing to connect to a port
     * nothing in its network namespace was listening on.
     *
     * Only the host is rewritten, and only when it is one that never leaves the machine or the
     * private network ([PlatformEndpoint.isLocal]). The port and the scheme were always right,
     * and a Pano on a public address is already reachable from inside a container, so touching
     * either would only break what works.
     */
    override fun pluginEndpoint(nodeEndpoint: PlatformEndpoint?): PlatformEndpoint? {
        val endpoint = nodeEndpoint ?: return null

        if (!isLoopback(endpoint.host)) {
            return endpoint
        }

        return endpoint.copy(host = DockerCommands.HOST_ALIAS)
    }

    override fun sample(
        uuid: String,
        process: Process,
        metrics: ServerRuntime.ProcessSampler
    ): ServerRuntime.Sample {
        // Never the local handle: that is the CLI client, which uses no CPU and holds no world.
        val result = runner(DockerCommands.statsArgs(uuid))

        if (!result.ok) {
            return ServerRuntime.Sample(null, null)
        }

        return DockerCommands.parseStats(result.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
    }

    private fun ensureImage(image: String, onProgress: (Int, String) -> Unit) {
        if (image in pulledImages) {
            return
        }

        if (runner(DockerCommands.imageInspectArgs(image)).ok) {
            pulledImages.add(image)

            return
        }

        // The first server on a fresh host waits for a few hundred megabytes, so the install task
        // says so rather than looking hung.
        onProgress(PULL_PERCENT, "Downloading the $image container image")

        logger.info("Pulling $image; the first server on this host waits for the download.")

        val pull = runner(DockerCommands.pullArgs(image))

        if (!pull.ok) {
            throw IllegalStateException("Could not pull $image: ${pull.output}")
        }

        pulledImages.add(image)
    }

    /**
     * `uid:gid` of the user this daemon runs as, or null on Windows.
     *
     * Read once from the OS rather than assumed, because the whole point is that the files in the
     * bind mount end up owned by whoever the node is — which on a packaged install is a dedicated
     * `pano-node` user and in a container is often root.
     */
    private fun posixUser(): String? {
        if (HostPlatform.isWindows) {
            return null
        }

        val uid = runner(listOf("id", "-u")).takeIf { it.ok }?.output?.trim()?.toLongOrNull() ?: return null
        val gid = runner(listOf("id", "-g")).takeIf { it.ok }?.output?.trim()?.toLongOrNull() ?: return null

        return "$uid:$gid"
    }

    /** One finished command: whether it succeeded and everything it printed. */
    data class CommandResult(val ok: Boolean, val output: String)

    companion object {
        const val ID = "DOCKER"

        /** Percent reported on the install task while an image is downloading. */
        const val PULL_PERCENT = 2

        /** How much longer than `docker stop` the attached client is given to notice. */
        const val CLIENT_GRACE_SECONDS = 10L

        const val NANOS_PER_MILLI = 1_000_000L

        private const val REPLAY_MAX_LINES = 5_000

        /** Starts one long-lived client with an argument list. Never a shell. */
        fun startClient(args: List<String>, discardOutput: Boolean): Process {
            val builder = ProcessBuilder(args)

            if (discardOutput) {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            }

            ServerProcess.sanitizeChildEnvironment(builder.environment())

            return builder.start()
        }

        private const val COMMAND_TIMEOUT_SECONDS = 600L

        /** Runs one argument list, capturing its output. Never a shell. */
        fun run(args: List<String>): CommandResult = try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()

            process.outputStream.close()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
            }

            CommandResult(finished && process.exitValue() == 0, output.trim())
        } catch (exception: Exception) {
            CommandResult(false, exception.message ?: exception.javaClass.simpleName)
        }
    }
}

/**
 * Only the node's own machine needs the container-side alias: a Pano on another LAN host is
 * reachable from a container directly through NAT, and rewriting it would point the plugin at the
 * wrong machine.
 */
private fun isLoopback(host: String): Boolean {
    val lower = host.trim().lowercase()

    return lower == "localhost" || lower == "::1" || lower == "0.0.0.0" || lower.startsWith("127.")
}
