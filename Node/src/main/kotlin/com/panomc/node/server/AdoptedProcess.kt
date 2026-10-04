package com.panomc.node.server

import com.panomc.node.host.ProcessMetrics
import java.util.concurrent.TimeUnit

/**
 * A server process this daemon did not start and can no longer talk to (SM-51, §2.4.16).
 *
 * What is missing compared to a [Process] is the whole reason this interface exists: there are no
 * pipes. The console comes from the log file instead, stdin is gone until the server is restarted
 * from the panel, and ending it is a signal rather than a `stop` written to a console. Everything
 * else -- state, metrics, the exit path -- carries on exactly as for a process the daemon spawned,
 * which is why [ServerProcess] holds one of these next to its [Process] rather than branching on
 * "was this adopted" in a dozen places.
 */
interface AdoptedProcess {
    /** The process id, or null for a runtime that has no host process (a container). */
    val pid: Long?

    fun isAlive(): Boolean

    /** SIGTERM, or the runtime's equivalent, and whether it worked inside [timeoutSeconds]. */
    fun terminate(timeoutSeconds: Long): Boolean

    /** Ends it now, with no chance to save. */
    fun kill()

    /** This server's vital signs, asked the way this runtime can answer them. */
    fun sample(metrics: ServerRuntime.ProcessSampler): ServerRuntime.Sample

    /** Runs [action] once, when the process is gone. */
    fun onExit(action: () -> Unit)
}

/**
 * An adopted JVM, held by its [ProcessHandle].
 *
 * A handle to a process that is not a child of this JVM can do everything the supervisor needs
 * except read its output: `destroy` sends SIGTERM, which Paper, Velocity and BungeeCord all shut
 * down cleanly on, and `onExit` is a future the JDK completes from its own reaper.
 */
class AdoptedHandle(private val handle: ProcessHandle) : AdoptedProcess {
    override val pid: Long? get() = try {
        handle.pid()
    } catch (_: Exception) {
        null
    }

    override fun isAlive(): Boolean = handle.isAlive

    override fun terminate(timeoutSeconds: Long): Boolean {
        handle.destroy()

        return waitFor(timeoutSeconds)
    }

    override fun kill() {
        handle.destroyForcibly()
    }

    override fun sample(metrics: ServerRuntime.ProcessSampler): ServerRuntime.Sample {
        if (!handle.isAlive) {
            return ServerRuntime.Sample(null, null)
        }

        return ServerRuntime.Sample(
            cpuPercent = metrics.cpuPercent(handle),
            residentBytes = pid?.let { ProcessMetrics.residentBytes(it) }
        )
    }

    override fun onExit(action: () -> Unit) {
        handle.onExit().thenRun(action)
    }

    private fun waitFor(timeoutSeconds: Long): Boolean = try {
        handle.onExit().get(timeoutSeconds, TimeUnit.SECONDS)

        true
    } catch (_: Exception) {
        !handle.isAlive
    }
}

/**
 * An adopted container, addressed by name through the `docker` CLI.
 *
 * There is no host process at all here -- the attached client died with the old daemon -- so
 * "is it alive" is an inspect, ending it is a `docker stop`, and the exit has to be polled for.
 * The poll is what a container costs: Docker has no signal to wait on from a client that was
 * never started, and a few seconds of latency on a state change is a fair price for not holding
 * a second `docker` process open per server forever.
 */
class AdoptedContainer(
    private val uuid: String,
    private val runner: (List<String>) -> DockerRuntime.CommandResult
) : AdoptedProcess {
    override val pid: Long? = null

    override fun isAlive(): Boolean {
        val result = runner(DockerCommands.inspectRunningArgs(uuid))

        return result.ok && DockerCommands.parseRunning(result.output)
    }

    override fun terminate(timeoutSeconds: Long): Boolean {
        runner(DockerCommands.stopArgs(uuid, timeoutSeconds))

        return !isAlive()
    }

    override fun kill() {
        runner(DockerCommands.killArgs(uuid))
    }

    override fun sample(metrics: ServerRuntime.ProcessSampler): ServerRuntime.Sample {
        val result = runner(DockerCommands.statsArgs(uuid))

        if (!result.ok) {
            return ServerRuntime.Sample(null, null)
        }

        return DockerCommands.parseStats(result.output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
    }

    override fun onExit(action: () -> Unit) {
        Thread({
            try {
                while (isAlive()) {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                }

                action()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
                // A docker CLI that stopped answering is not something to take the daemon down
                // for; the container keeps its last known state until the next node restart.
            }
        }, "pano-node-adopted-$uuid").apply { isDaemon = true }.start()
    }

    companion object {
        /** How often a container is asked whether it is still running. */
        const val POLL_INTERVAL_MILLIS = 5_000L
    }
}
