package com.panomc.platform.node

import java.io.File
import java.util.concurrent.TimeUnit

/** One Java installation Pano could start the node daemon with. */
data class JavaRuntime(
    val major: Int,
    val home: File,
    /** Where this candidate came from, so the log line says why it was picked. */
    val source: String
) {
    /** The launcher a process is actually started with. */
    val launcher: File get() = File(File(home, "bin"), JavaRuntimeLocator.executableName())

    val path: String get() = home.absolutePath
}

/** Either the runtime that was picked, or the reason there is none. */
data class JavaRuntimeLookup(
    val runtime: JavaRuntime?,
    val error: String?
)

/**
 * Finds a Java the `pano-node` daemon can actually run on.
 *
 * Pano itself is built for Java 11 and is routinely run on it -- the Gradle `run` toolchain and
 * plenty of production hosts are exactly that -- while `pano-node.jar` is compiled for 17. Handing
 * the daemon the JVM Pano happens to be running on therefore fails on a whole class of installs
 * with `UnsupportedClassVersionError (class file version 61.0)`, and because the supervisor
 * restarts what it started, it fails again every few seconds forever. So the runtime is chosen,
 * not inherited.
 *
 * The version is read from the `release` file every JDK since 9 ships (and 8 ships too), and
 * `java -version` is only ever run for a candidate that has no such file: probing a dozen
 * directories by launching a dozen JVMs is not something to do on a panel request. The process is
 * started from an argument list with a short timeout, never through a shell.
 */
object JavaRuntimeLocator {
    /** What `pano-node.jar` is compiled for; anything below cannot load its classes at all. */
    const val MINIMUM_MAJOR = 17

    private const val PROBE_TIMEOUT_SECONDS = 5L

    fun executableName(): String =
        if (System.getProperty("os.name", "").lowercase().contains("win")) "java.exe" else "java"

    /**
     * Picks the Java the daemon is launched with, or says why there is none.
     *
     * [configuredPath] is `local-node.java-path` and wins whenever it points at a runtime new
     * enough: an operator who named a JDK meant it, and quietly using a different one would make
     * the setting look broken. It is ignored (with the reason in the message) when it does not
     * resolve or is too old, because refusing to start at all would be worse than using the
     * perfectly good JDK sitting next to it.
     */
    fun locate(configuredPath: String? = null): JavaRuntimeLookup {
        val configured = configuredPath?.takeIf { it.isNotBlank() }?.let { inspect(homeOf(File(it.trim())), "local-node.java-path") }

        if (configured != null && configured.major >= MINIMUM_MAJOR) {
            return JavaRuntimeLookup(configured, null)
        }

        val discovered = discover()
        val picked = select(discovered)

        if (picked != null) {
            return JavaRuntimeLookup(picked, null)
        }

        return JavaRuntimeLookup(null, missingMessage(configuredPath, configured, discovered))
    }

    /** Every Java found on this host, newest first, one entry per real directory. */
    fun discover(): List<JavaRuntime> {
        val found = LinkedHashMap<String, JavaRuntime>()

        candidates().forEach { (home, source) ->
            val runtime = inspect(home, source) ?: return@forEach

            found.putIfAbsent(runtime.path, runtime)
        }

        return found.values.sortedWith(compareByDescending<JavaRuntime> { it.major }.thenBy { it.path })
    }

    /** The highest runtime that can load the daemon's classes. */
    fun select(runtimes: List<JavaRuntime>): JavaRuntime? =
        runtimes.filter { it.major >= MINIMUM_MAJOR }.maxByOrNull { it.major }

    /**
     * Reads a Java major out of a version string.
     *
     * Both shapes matter and they disagree about which number is the major: `1.8.0_432` is Java 8
     * and `21.0.4` is Java 21, so reading the first component of the first would give 1 and a
     * runtime that cannot run the daemon would look like one that can.
     */
    fun parseMajor(version: String?): Int? {
        val cleaned = version?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null

        val parts = cleaned.split('.', '-', '_', '+')

        val first = parts.firstOrNull()?.toIntOrNull() ?: return null

        if (first == 1) {
            return parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        }

        return first.takeIf { it > 0 }
    }

    /** `JAVA_VERSION="21.0.4"` out of the text of a JDK's `release` file. */
    fun readReleaseVersion(text: String): String? = text
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')

            if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .firstOrNull { it.first == "JAVA_VERSION" }
        ?.second
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }

    /** The version out of what `java -version` prints, which goes to stderr on every vendor. */
    fun readProbedVersion(output: String): String? =
        Regex("version \"([^\"]+)\"").find(output)?.groupValues?.getOrNull(1)

    /**
     * The home a path points at, whether it names the home itself or the launcher inside it.
     *
     * Operators write both into a config key called `java-path`, and the difference is one the
     * machine can see for itself.
     */
    fun homeOf(path: File): File {
        if (path.isFile && path.name.removeSuffix(".exe") == "java") {
            return path.parentFile?.parentFile ?: path
        }

        return path
    }

    /**
     * Where to look, in the order an operator would expect.
     *
     * Pano's own JVM and `JAVA_HOME` come first only so they are cheap to inspect; the pick is by
     * version, not by order, so a host whose `JAVA_HOME` is 11 and whose `/usr/lib/jvm` has 21
     * gets 21.
     */
    private fun candidates(): List<Pair<File, String>> {
        val candidates = mutableListOf<Pair<File, String>>()

        System.getProperty("java.home")?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(File(it) to "Pano's own JVM") }

        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(File(it) to "JAVA_HOME") }

        LINUX_ROOTS.forEach { root ->
            children(File(root)).forEach { candidates.add(it to root) }
        }

        candidates.add(File(LINUX_DEFAULT_LINK) to LINUX_DEFAULT_LINK)

        children(File(MAC_ROOT)).forEach { candidates.add(File(File(it, "Contents"), "Home") to MAC_ROOT) }

        WINDOWS_ROOTS.forEach { root ->
            children(File(root)).forEach { candidates.add(it to root) }
        }

        fromPath().forEach { candidates.add(it to "PATH") }

        return candidates
    }

    private fun fromPath(): List<File> {
        val path = System.getenv("PATH") ?: return emptyList()

        return path.split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, executableName()) }
            .filter { it.isFile }
            .mapNotNull { it.parentFile?.parentFile }
            .toList()
    }

    private fun children(dir: File): List<File> {
        if (!dir.isDirectory) {
            return emptyList()
        }

        return dir.listFiles()?.filter { it.isDirectory }.orEmpty().sortedBy { it.name }
    }

    private fun inspect(home: File, source: String): JavaRuntime? {
        if (!home.isDirectory) {
            return null
        }

        val canonical = try {
            home.canonicalFile
        } catch (_: Exception) {
            home.absoluteFile
        }

        if (!File(File(canonical, "bin"), executableName()).isFile) {
            return null
        }

        val release = File(canonical, "release")

        val version = if (release.isFile) {
            readReleaseVersion(runCatching { release.readText() }.getOrDefault(""))
        } else {
            probe(canonical)
        }

        val major = parseMajor(version) ?: return null

        return JavaRuntime(major, canonical, source)
    }

    private fun probe(home: File): String? = try {
        val process = ProcessBuilder(
            File(File(home, "bin"), executableName()).absolutePath,
            "-version"
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }

        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }

        readProbedVersion(output)
    } catch (_: Exception) {
        null
    }

    private fun missingMessage(
        configuredPath: String?,
        configured: JavaRuntime?,
        discovered: List<JavaRuntime>
    ): String {
        val reason = when {
            configured != null ->
                "local-node.java-path points at Java ${configured.major}, and the node needs $MINIMUM_MAJOR or newer."

            !configuredPath.isNullOrBlank() ->
                "local-node.java-path (\"$configuredPath\") is not a Java installation."

            discovered.isEmpty() -> "No Java installation was found on this machine."

            else -> "The newest Java found on this machine is " +
                    "${discovered.first().major} (${discovered.first().path}), and the node needs " +
                    "$MINIMUM_MAJOR or newer."
        }

        return "$reason Install a Java $MINIMUM_MAJOR+ runtime, or set local-node.java-path in config.conf to one."
    }

    private val LINUX_ROOTS = listOf("/usr/lib/jvm", "/usr/java", "/opt/java")

    private const val LINUX_DEFAULT_LINK = "/usr/lib/jvm/default"

    private const val MAC_ROOT = "/Library/Java/JavaVirtualMachines"

    private val WINDOWS_ROOTS = listOf(
        "C:\\Program Files\\Java",
        "C:\\Program Files\\Eclipse Adoptium",
        "C:\\Program Files\\Microsoft"
    )
}
