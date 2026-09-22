package com.panomc.node.server

import com.panomc.node.net.PlatformEndpoint
import java.io.File

/**
 * How a managed server's process is actually run (SM-43, §2.4.12).
 *
 * Everything above this interface — the console pipeline, the state machine, the crash backoff,
 * the installer, files and backups — is identical whether a server is a JVM this daemon spawned
 * or a container it asked Docker to spawn. The differences fit in five verbs, and they are all of
 * the shape "the handle you hold is not the thing you want to act on": a `docker start -a` client
 * can be killed without the container noticing, so stopping is a command rather than a signal,
 * and its CPU usage is the client's, not the server's.
 *
 * [ServerProcess] therefore owns the lifecycle and this owns the mechanism, which is what keeps
 * `--runtime DOCKER` from being a second copy of the supervisor.
 */
interface ServerRuntime {
    /** `PROCESS` or `DOCKER`, as announced in `NODE_HELLO.runtime`. */
    val id: String

    /**
     * Checks this host can run the runtime at all, once, at boot.
     *
     * Throws with a message meant for a person. A node started with `--runtime DOCKER` on a host
     * without Docker refuses to start rather than accepting servers it will fail to launch.
     */
    fun verify()

    /**
     * Starts [request]'s server and returns the process whose pipes carry its console.
     *
     * For a container that is the attached client, not the server: it is still the right handle,
     * because it is what stdin is written to and what stdout is read from.
     */
    fun launch(request: LaunchRequest): Process

    /**
     * Starts [request]'s server so that it outlives this daemon (SM-62, §2.4.27), or null when this
     * runtime or this host cannot — the caller then falls back to [launch], which is today's
     * behaviour. Only the process runtime does this: a container outlives the daemon by itself.
     */
    fun launchDetached(request: LaunchRequest): DetachedLaunch? = null

    /**
     * Takes back, with full control, a server whose [record] says it was started to be re-attached
     * to (SM-62), or null when the record is not one of those — the caller then falls back to
     * [adopt]. [Reattachment.Exited] when it exited while no daemon was running.
     */
    fun reattach(uuid: String, directory: File, record: ProcessRecord): Reattachment? = null

    /**
     * Ends the server gracefully after its console `stop` was already written and ignored.
     *
     * Returns whether it is actually gone. [timeoutSeconds] is how long to wait before giving up
     * and letting the caller escalate.
     */
    fun terminate(uuid: String, process: Process, timeoutSeconds: Long): Boolean

    /** Ends the server now, with no chance to save. */
    fun kill(uuid: String, process: Process)

    /** Releases whatever this runtime left behind for a server that is being deleted. */
    fun remove(uuid: String)

    /**
     * Re-attaches to a server that outlived the daemon that started it, or null when it is gone
     * (SM-51, §2.4.16).
     *
     * [record] is what the previous daemon wrote down about the process; each runtime decides for
     * itself what still proves it is the same one -- a pid with a matching start time here, a
     * container with the right name there -- and a runtime that cannot tell simply adopts
     * nothing, which leaves the server STOPPED exactly as it was before this existed.
     */
    fun adopt(uuid: String, directory: File, record: ProcessRecord): AdoptedProcess?

    /**
     * The Pano address to write into the plugin config of a server this runtime runs.
     *
     * [nodeEndpoint] is the address the daemon itself is connected on, and everywhere but a
     * container that is also the right answer for the server: same host, same network stack. A
     * runtime that gives each server its own stack has to translate, because `127.0.0.1` then
     * names the container rather than the machine Pano is on -- which is exactly how an
     * auto-installed plugin ends up dialling a loopback with nothing behind it.
     *
     * Null in, null out: an unpaired or misconfigured node has no address to offer and the
     * installer falls back to what Pano guessed.
     */
    fun pluginEndpoint(nodeEndpoint: PlatformEndpoint?): PlatformEndpoint?

    /** This server's vital signs, or nulls where this runtime cannot answer. */
    fun sample(uuid: String, process: Process, metrics: ProcessSampler): Sample

    /**
     * What one server is using right now.
     *
     * [netRxTotal]/[netTxTotal] are cumulative bytes the server's own network stack has received
     * and sent (§2.4.22 A), which only a runtime that gives each server its own stack can know —
     * a container does, a plain process shares the host's and reports null.
     */
    data class Sample(
        val cpuPercent: Double?,
        val residentBytes: Long?,
        val netRxTotal: Long? = null,
        val netTxTotal: Long? = null
    )

    /** Everything needed to start one server. */
    data class LaunchRequest(
        val uuid: String,
        val directory: File,
        val spec: ServerSpec,
        /**
         * Absolute path of the host's `java`, and the runtime is free to ignore it: a container
         * brings its own JDK and the path of this host's would mean nothing inside it.
         */
        val javaPath: String,
        /** Major version that path is, which is what picks a container image. */
        val javaMajor: Int,
        val jarName: String,
        /** Called with a percent and a line while an image is being fetched. */
        val onProgress: (Int, String) -> Unit = { _, _ -> }
    )

    /** The CPU baseline a runtime samples against, kept per server across readings. */
    interface ProcessSampler {
        fun cpuPercent(handle: ProcessHandle): Double?
        fun reset()
    }
}
