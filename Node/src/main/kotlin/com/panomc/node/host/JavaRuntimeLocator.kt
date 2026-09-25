package com.panomc.node.host

import com.google.gson.Gson
import com.panomc.node.java.JavaVersionOrder
import com.panomc.node.java.ManagedRuntimeMarker
import com.panomc.node.server.MinecraftJavaVersions
import java.io.File
import java.util.concurrent.TimeUnit

/** One Java runtime found on the host, in the shape `NODE_HELLO` carries. */
data class JavaRuntime(
    val major: Int,
    val path: String,
    val vendor: String?,
    /**
     * The full version: what `.pano-managed.json` recorded for a runtime the node installed,
     * otherwise `JAVA_VERSION` from the JDK's `release` file (or `java -version`). Null only when
     * neither said, which sorts it below every runtime of the same major that did.
     */
    val version: String? = null,
    /** Whether the node installed this runtime itself (SM-63), and may therefore update or remove it. */
    val managed: Boolean = false
) {
    /**
     * Whether this is a JDK: it has `javac`. A JRE runs every server but cannot build one, and
     * BuildTools handed a JRE fails ten minutes in with Maven's "No compiler is provided".
     */
    val hasCompiler: Boolean get() = File(File(path, "bin"), HostPlatform.javacExecutable).isFile
}

/** A runtime picked for a Minecraft version, and the sentence explaining the pick. */
data class RuntimeChoice(
    val runtime: JavaRuntime,
    val reason: String
)

/**
 * Finds the Java runtimes this host can start a server with.
 *
 * Pano shows these so the create-server wizard can refuse a Minecraft version the host cannot run
 * before an install spends ten minutes downloading it. Five sources are searched, in the order an
 * operator would expect them to win: the JVM running this daemon, `JAVA_HOME`, everything on
 * `PATH`, the system JDK directories, and `<data>/java` -- which is where a runtime the node
 * downloads for itself lands, so one dropped in by hand is found the same way.
 *
 * The version is read from the `release` file a JDK ships, and only when that is missing is
 * `java -version` actually run: launching every candidate would mean a dozen process starts on
 * every connect.
 *
 * A runtime under `<data>/java` that carries a `.pano-managed.json` is one the node downloaded
 * (SM-63, §2.4.28); it is reported `managed` with the version the marker recorded. Dot-prefixed
 * directories there are an install or a removal in progress and are never looked at.
 *
 * When several runtimes share a major, the newest *version* wins, managed or not. The order they
 * were found in used to decide it, which is to say nothing did: `JAVA_HOME` beat a fresher JDK in
 * `/usr/lib/jvm` for one caller and lost to it for another.
 */
class JavaRuntimeLocator(private val dataDir: File) {
    fun discover(): List<JavaRuntime> {
        val homes = LinkedHashSet<File>()

        System.getProperty("java.home")?.let { homes.add(File(it)) }
        System.getenv("JAVA_HOME")?.let { homes.add(File(it)) }

        homes.addAll(fromPath())
        homes.addAll(HostPlatform.systemJavaRoots().flatMap { children(it) })
        homes.addAll(children(javaRoot).filterNot { it.name.startsWith(".") || isHiddenBundle(it) })

        val found = LinkedHashMap<String, JavaRuntime>()

        homes.forEach { home ->
            val runtime = inspect(home) ?: return@forEach

            found.putIfAbsent(runtime.path, runtime)
        }

        return found.values.sortedWith(compareBy<JavaRuntime> { it.major }.thenBy(JavaVersionOrder) { it.version })
    }

    /**
     * Where the runtime for [major] lives, or null when the host has none.
     *
     * The exact major when installed, else the nearest one above it; among several of the chosen
     * major, the newest version.
     */
    fun resolve(major: Int): JavaRuntime? {
        val runtimes = discover()

        newestOf(runtimes, major)?.let { return it }

        val above = runtimes.filter { it.major > major }.minByOrNull { it.major } ?: return null

        return newestOf(runtimes, above.major)
    }

    /** The newest installed runtime of exactly [major], or null. */
    fun exact(major: Int): JavaRuntime? = newestOf(discover(), major)

    /** The newest installed JDK ([JavaRuntime.hasCompiler]) of exactly [major], or null. */
    fun exactJdk(major: Int): JavaRuntime? = newestOf(discover().filter { it.hasCompiler }, major)

    /**
     * The runtime for a server whose Java version Pano left to this host.
     *
     * [MinecraftJavaVersions] decides which majors are acceptable for that Minecraft version; this
     * only has to match one of them to what is installed here.
     */
    fun resolveFor(minecraftVersion: String?, requiredMajor: Int? = null): JavaRuntime? =
        chooseFor(minecraftVersion, requiredMajor)?.runtime

    /**
     * [resolveFor] with the reasoning attached.
     *
     * An automatic choice is the thing an operator second-guesses when a server misbehaves, so the
     * launch log has to be able to say which runtime was taken and what made it the right one.
     *
     * [requiredMajor] is what the server jar itself demands, read by
     * [com.panomc.node.server.JarJavaRequirement]. It is a floor under the ladder's answer and
     * never a replacement for it: the ladder knows a 1.16 server does not run past Java 16, the
     * jar knows its own class files, and a runtime has to satisfy both.
     */
    fun chooseFor(minecraftVersion: String?, requiredMajor: Int? = null): RuntimeChoice? {
        val runtimes = discover()

        val choice = MinecraftJavaVersions.choose(runtimes.map { it.major }, minecraftVersion, requiredMajor)
            ?: return null

        // A jar's requirement is a hard floor, so a choice below it is no choice at all: the
        // ladder's fallbacks exist for a server that merely prefers another runtime, and starting
        // this one would end in UnsupportedClassVersionError and a restart loop.
        if (requiredMajor != null && choice.major < requiredMajor) {
            return null
        }

        val runtime = newestOf(runtimes, choice.major) ?: return null

        return RuntimeChoice(runtime, choice.reason)
    }

    /** The `java` launcher inside [runtime], which is what a server process is started with. */
    fun launcher(runtime: JavaRuntime): File = File(File(runtime.path, "bin"), HostPlatform.javaExecutable)

    private fun fromPath(): List<File> {
        val path = System.getenv("PATH") ?: return emptyList()

        return path.split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, HostPlatform.javaExecutable) }
            .filter { it.isFile }
            // `/usr/bin/java` is usually a symlink into a real JDK; without following it the
            // runtime would be listed twice, once as `/usr` and once under its own home.
            .map { executable -> runCatching { executable.canonicalFile }.getOrDefault(executable) }
            .mapNotNull { it.parentFile?.parentFile }
            .toList()
    }

    /** `<data>/java`, where the node's own runtimes are installed. */
    val javaRoot: File get() = File(dataDir, "java")

    /**
     * Reads one runtime home, or null when it is not one.
     *
     * Public so the installer can read back the runtime it just moved into place without waiting
     * for a full [discover].
     */
    fun inspectHome(home: File): JavaRuntime? = inspect(home)

    private fun newestOf(runtimes: List<JavaRuntime>, major: Int): JavaRuntime? = runtimes
        .filter { it.major == major }
        .maxWithOrNull(compareBy(JavaVersionOrder) { it.version })

    /** A macOS bundle's `Contents/Home` inside a dot-directory that is still being installed. */
    private fun isHiddenBundle(home: File): Boolean =
        home.name == "Home" && home.parentFile?.parentFile?.name?.startsWith(".") == true

    /**
     * The marker of a runtime the node installed, when [home] is one of those.
     *
     * Only under `<data>/java`: a marker copied into a JDK somewhere else does not make the node
     * the owner of it, and "managed" is exactly the permission to delete the directory.
     */
    private fun markerOf(home: File): ManagedRuntimeMarker? {
        val root = if (home.name == "Home" && home.parentFile?.name == "Contents") {
            home.parentFile.parentFile ?: return null
        } else {
            home
        }

        val javaRootPath = try {
            javaRoot.canonicalFile.toPath()
        } catch (_: Exception) {
            return null
        }

        if (!root.toPath().startsWith(javaRootPath) || root.toPath() == javaRootPath) {
            return null
        }

        val file = File(root, ManagedRuntimeMarker.FILE)

        if (!file.isFile) {
            return null
        }

        return try {
            gson.fromJson(file.readText(), ManagedRuntimeMarker::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun children(dir: File): List<File> {
        if (!dir.isDirectory) {
            return emptyList()
        }

        val direct = dir.listFiles()?.filter { it.isDirectory }.orEmpty()

        // macOS bundles a JDK as Home inside Contents; Linux and Windows point straight at it.
        return direct.flatMap { child ->
            val bundled = File(File(child, "Contents"), "Home")

            if (bundled.isDirectory) listOf(bundled) else listOf(child)
        }
    }

    private fun inspect(home: File): JavaRuntime? {
        if (!home.isDirectory) {
            return null
        }

        val canonical = try {
            home.canonicalFile
        } catch (_: Exception) {
            home.absoluteFile
        }

        if (!File(File(canonical, "bin"), HostPlatform.javaExecutable).isFile) {
            return null
        }

        val release = readRelease(File(canonical, "release"))

        val version = release["JAVA_VERSION"] ?: probeVersion(canonical) ?: return null
        val major = parseMajor(version) ?: return null

        val marker = markerOf(canonical)

        return JavaRuntime(
            major = major,
            path = canonical.absolutePath,
            vendor = release["IMPLEMENTOR"] ?: release["JAVA_VENDOR"],
            version = marker?.version?.takeIf { it.isNotBlank() } ?: version,
            managed = marker != null
        )
    }

    private fun readRelease(file: File): Map<String, String> {
        if (!file.isFile) {
            return emptyMap()
        }

        return try {
            file.readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')

                    if (separator <= 0) {
                        null
                    } else {
                        line.substring(0, separator).trim() to line.substring(separator + 1).trim().trim('"')
                    }
                }
                .toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun probeVersion(home: File): String? = try {
        val process = ProcessBuilder(
            File(File(home, "bin"), HostPlatform.javaExecutable).absolutePath,
            "-version"
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }

        process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        Regex("version \"([^\"]+)\"").find(output)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val PROBE_TIMEOUT_SECONDS = 10L

        private val gson = Gson()

        /**
         * Reads a Java major out of a version string.
         *
         * Both shapes matter: `1.8.0_432` is Java 8 and `21.0.5` is Java 21, and a node that got
         * this wrong would tell Pano a host can run a version it cannot.
         */
        fun parseMajor(version: String): Int? {
            val cleaned = version.trim().ifBlank { return null }

            val parts = cleaned.split('.', '-', '_')

            val first = parts.firstOrNull()?.toIntOrNull() ?: return null

            if (first == 1) {
                return parts.getOrNull(1)?.toIntOrNull()
            }

            return first.takeIf { it > 0 }
        }
    }
}
