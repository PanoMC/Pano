package com.panomc.node.server

import com.google.gson.JsonParser
import java.time.Instant

/**
 * Every `docker` command line this node ever runs, built as argument lists.
 *
 * Its own object because this is where a mistake is both easy and expensive: the difference
 * between a list and a string here is the difference between a server name and a second command,
 * and a Minecraft server's name is text somebody typed into a web panel. Nothing in this file
 * concatenates a command; everything returns a `List<String>` that goes straight to
 * `ProcessBuilder`, which never involves a shell.
 *
 * Pure, so the whole surface is tested without Docker being installed anywhere.
 */
object DockerCommands {
    const val CONTAINER_PREFIX = "pano-"

    /** The image family managed servers run on, tagged by Java major. */
    const val IMAGE_REPOSITORY = "eclipse-temurin"

    /** Where the server directory is bind-mounted inside the container. */
    const val WORK_DIR = "/data"

    /** Java the image ships when the spec asked for one it has no tag for. */
    const val FALLBACK_JAVA_MAJOR = 21

    /** The name a container inside this runtime reaches the host by. */
    const val HOST_ALIAS = "host.docker.internal"

    /**
     * The mapping that makes [HOST_ALIAS] resolve, on every platform.
     *
     * Docker Desktop provides the name by itself; Linux does not, and `host-gateway` is the
     * literal Docker understands as "the address of the machine this daemon runs on".
     */
    const val HOST_GATEWAY_MAPPING = "$HOST_ALIAS:host-gateway"

    /** The container one server runs in. Derived from the uuid, which is already a safe segment. */
    fun containerName(uuid: String) = "$CONTAINER_PREFIX$uuid"

    /** `eclipse-temurin:<major>-jre`, the smallest official image that can run a server jar. */
    fun imageFor(javaMajor: Int): String {
        val major = if (javaMajor in MIN_JAVA_MAJOR..MAX_JAVA_MAJOR) javaMajor else FALLBACK_JAVA_MAJOR

        return "$IMAGE_REPOSITORY:$major-jre"
    }

    /** `docker version`, the check that decides whether `--runtime DOCKER` is usable at all. */
    fun versionArgs(): List<String> = listOf("docker", "version", "--format", "{{.Server.Version}}")

    fun pullArgs(image: String): List<String> = listOf("docker", "pull", image)

    /** Whether the image is already on this host, so a pull is only done when it is needed. */
    fun imageInspectArgs(image: String): List<String> = listOf("docker", "image", "inspect", image)

    /**
     * `docker create` for one server.
     *
     * The choices worth naming:
     * - `--memory` is the container's ceiling, the server's whole memory setting, and the JVM's
     *   `-Xmx` is that setting minus the JVM's own share ([JvmHeap], via
     *   [ServerProcess.buildCommand]): a heap as large as the cgroup always takes the process over
     *   it once the heap fills, and the kernel kills the container instead of the JVM collecting.
     * - `-e JAVA_TOOL_OPTIONS=` blanks the variable inside the container for the same reason the
     *   process runtime strips it: whatever started this daemon must not reach the server.
     * - `--user` keeps files in the bind mount owned by the node's own user, so the file manager
     *   and the backup service — which work on host paths and know nothing about containers —
     *   can still read and write what the server wrote.
     * - The port is published one-to-one so the number in `server.properties`, the number Pano
     *   stored and the number a player types stay the same one.
     * - `--add-host` is what makes a Pano on this machine reachable at all. A container has its
     *   own loopback, so the `127.0.0.1` the node connects to Pano on means the container itself
     *   in here; [DockerRuntime.pluginEndpoint] writes [HOST_ALIAS] into the plugin config
     *   instead, and this is the line that makes that name point somewhere.
     */
    fun createArgs(
        uuid: String,
        image: String,
        spec: ServerSpec,
        hostDirectory: String,
        jarName: String,
        port: Int,
        /** `uid:gid` on POSIX hosts, null on Windows where it means nothing. */
        user: String?,
        /** The image's Java major, for the flags only a new enough JVM accepts. */
        javaMajor: Int? = null
    ): List<String> {
        val args = mutableListOf(
            "docker", "create",
            "--name", containerName(uuid),
            "--memory", "${spec.memoryMb}m",
            "--workdir", WORK_DIR,
            "--volume", "$hostDirectory:$WORK_DIR",
            "-e", "JAVA_TOOL_OPTIONS=",
            // The same allocator setting a plain process gets (JvmHeap.MALLOC_ARENA_MAX).
            "-e", "MALLOC_ARENA_MAX=${JvmHeap.MALLOC_ARENA_MAX}",
            // stdin stays open: it is the only way a console command reaches the server.
            "--interactive",
            // Pano supervises restarts itself, with its own backoff; Docker also doing it would
            // race the crash handler and hide every crash from the panel.
            "--restart", "no",
            // Unconditional: the Pano plugin is not the only thing in a server that may want the
            // host, and a name that resolves costs nothing when nobody uses it.
            "--add-host", HOST_GATEWAY_MAPPING
        )

        if (port in 1..65535) {
            args.add("--publish")
            args.add("$port:$port")
        }

        user?.let {
            args.add("--user")
            args.add(it)
        }

        args.add(image)
        // `java` from the image's PATH, never this host's: the container brings its own.
        args.addAll(ServerProcess.buildCommand("java", jarName, spec, javaMajor))

        return args
    }

    /** Attached start: the client's pipes are the server's console. */
    fun startArgs(uuid: String): List<String> =
        listOf("docker", "start", "--attach", "--interactive", containerName(uuid))

    /**
     * Re-attaches a console to a running container after a daemon restart (SM-62, §2.4.27).
     *
     * Input only, as far as the node is concerned: `--sig-proxy=false` so a daemon going away never
     * signals the server, stdin explicitly on because it is the whole reason for this client. Its
     * output is discarded — `docker logs` ([logsArgs]) delivers that, including what was printed
     * while no daemon was attached.
     */
    fun attachArgs(uuid: String): List<String> =
        listOf("docker", "attach", "--no-stdin=false", "--sig-proxy=false", containerName(uuid))

    /**
     * The container's output from [sinceNanos] on, every line prefixed by Docker's own timestamp so
     * the reader can drop the ones it had already seen at the boundary.
     */
    fun logsArgs(uuid: String, sinceNanos: Long, follow: Boolean): List<String> {
        val args = mutableListOf("docker", "logs", "--timestamps", "--since", formatSince(sinceNanos))

        if (follow) {
            args.add("--follow")
        }

        args.add(containerName(uuid))

        return args
    }

    /** How a stopped container ended, for an exit that happened while no daemon was running. */
    fun inspectExitCodeArgs(uuid: String): List<String> =
        listOf("docker", "inspect", "-f", "{{.State.ExitCode}}", containerName(uuid))

    /** [inspectExitCodeArgs]'s answer, or null when it is not a number. */
    fun parseExitCode(output: String): Int? =
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()

    /** Epoch nanos as the `seconds.nanoseconds` Unix timestamp `docker logs --since` accepts. */
    fun formatSince(nanos: Long): String {
        val safe = nanos.coerceAtLeast(0L)

        return "${safe / NANOS_PER_SECOND}.${(safe % NANOS_PER_SECOND).toString().padStart(9, '0')}"
    }

    /**
     * One `docker logs --timestamps` line as its timestamp (epoch nanos) and its text; the timestamp
     * is null and the line whole when it does not start with one.
     */
    fun splitTimestamp(line: String): Pair<Long?, String> {
        val space = line.indexOf(' ')

        if (space <= 0) {
            return null to line
        }

        val instant = try {
            Instant.parse(line.substring(0, space))
        } catch (_: Exception) {
            return null to line
        }

        return (instant.epochSecond * NANOS_PER_SECOND + instant.nano) to line.substring(space + 1)
    }

    const val NANOS_PER_SECOND = 1_000_000_000L

    fun stopArgs(uuid: String, timeoutSeconds: Long): List<String> =
        listOf("docker", "stop", "--time", timeoutSeconds.toString(), containerName(uuid))

    fun killArgs(uuid: String): List<String> = listOf("docker", "kill", containerName(uuid))

    fun removeArgs(uuid: String): List<String> = listOf("docker", "rm", "--force", containerName(uuid))

    fun statsArgs(uuid: String): List<String> =
        listOf("docker", "stats", "--no-stream", "--format", "{{json .}}", containerName(uuid))

    /**
     * Whether this server's container is running, asked of Docker rather than of a handle.
     *
     * The question adoption is built on for this runtime (SM-51): after a daemon restart there is
     * no attached client left to hold, and the container either still exists and runs or it does
     * not. `-f` prints the one field rather than a page of JSON to parse.
     */
    fun inspectRunningArgs(uuid: String): List<String> =
        listOf("docker", "inspect", "-f", "{{.State.Running}}", containerName(uuid))

    /** [inspectRunningArgs]'s answer. Anything but a plain `true` is "no". */
    fun parseRunning(output: String): Boolean =
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().equals("true", ignoreCase = true)

    /**
     * One `docker stats` line as CPU percent, resident bytes and the container's network totals.
     *
     * Docker reports all of them for humans — `"12.34%"`, `"1.5GiB / 4GiB"`, `"1.2MB / 3.4kB"` —
     * so they are parsed rather than read as numbers, and anything unparseable becomes null
     * instead of a made-up figure. NetIO is received first, sent second, both cumulative since the
     * container started (§2.4.22 A).
     */
    fun parseStats(line: String): ServerRuntime.Sample {
        val json = try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: Exception) {
            return ServerRuntime.Sample(null, null)
        }

        fun string(key: String) = try {
            json.get(key)?.asString
        } catch (_: Exception) {
            null
        }

        val netIo = string("NetIO")?.let { parsePair(it) }

        return ServerRuntime.Sample(
            cpuPercent = string("CPUPerc")?.let { parsePercent(it) },
            residentBytes = string("MemUsage")?.let { parseBytes(it.substringBefore('/').trim()) },
            netRxTotal = netIo?.first,
            netTxTotal = netIo?.second
        )
    }

    /**
     * `"1.2MB / 3.4kB"` as two byte counts, or null when either side is not one.
     *
     * Both halves or neither: a pair where one side is `--` is a container that is not reporting,
     * and half of it would be a rate for one direction next to a gap in the other.
     */
    fun parsePair(value: String): Pair<Long, Long>? {
        val parts = value.split('/')

        if (parts.size != 2) {
            return null
        }

        val first = parseBytes(parts[0]) ?: return null
        val second = parseBytes(parts[1]) ?: return null

        return first to second
    }

    /** `"12.34%"` as a number, clamped to a percentage of one host. */
    fun parsePercent(value: String): Double? =
        value.trim().removeSuffix("%").trim().toDoubleOrNull()?.coerceIn(0.0, MAX_CPU_PERCENT)

    /** `"1.5GiB"`, `"512MiB"`, `"1.2kB"` as bytes. */
    fun parseBytes(value: String): Long? {
        val cleaned = value.trim()

        if (cleaned.isEmpty()) {
            return null
        }

        val unit = cleaned.takeLastWhile { !it.isDigit() && it != '.' && it != ',' }.trim()
        val number = cleaned.dropLast(unit.length).trim().replace(',', '.').toDoubleOrNull() ?: return null

        val multiplier = UNITS[unit.lowercase()] ?: return null

        return (number * multiplier).toLong().coerceAtLeast(0L)
    }

    private const val MIN_JAVA_MAJOR = 8
    private const val MAX_JAVA_MAJOR = 64

    /** Docker reports CPU as a share of all cores, so 400% on a quad core is real. */
    private const val MAX_CPU_PERCENT = 100_000.0

    // Docker prints both the binary units and the decimal ones depending on the value.
    private val UNITS = mapOf(
        "b" to 1L,
        "kb" to 1_000L,
        "mb" to 1_000_000L,
        "gb" to 1_000_000_000L,
        "tb" to 1_000_000_000_000L,
        "kib" to 1024L,
        "mib" to 1024L * 1024L,
        "gib" to 1024L * 1024L * 1024L,
        "tib" to 1024L * 1024L * 1024L * 1024L
    )
}
