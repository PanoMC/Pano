package com.panomc.node.server

/**
 * What a server directory's `server.json` holds (`<data>/servers/<uuid>/server.json`, or the same
 * file inside an adopted directory): everything needed to start that server again.
 *
 * The node is the authority on this, not Pano. A daemon that lost its platform must still be able
 * to bring its servers back up, and an operator looking at the directory must be able to see what
 * is going to be launched without asking a web panel.
 *
 * Every field is nullable or defaulted because the file is read back by Gson, which fills nothing
 * it cannot find and happily writes an explicit null into a non-null Kotlin type.
 */
data class ServerSpec(
    val uuid: String = "",
    val name: String = "",
    val software: String = "",
    val version: String = "",
    /** [AUTO_JAVA_MAJOR] means "whatever this host has that runs [version]". */
    val javaMajor: Int = AUTO_JAVA_MAJOR,
    val memoryMb: Int = DEFAULT_MEMORY_MB,
    val jvmArgs: List<String> = emptyList(),
    val port: Int = 0,
    val jar: String = DEFAULT_JAR,
    val autoStart: Boolean = false,
    val crashRestart: Boolean = true,
    val properties: Map<String, String> = emptyMap(),
    /**
     * Whether this server was adopted where it already was (`IMPORT_SERVER` mode `IN_PLACE`) rather
     * than living in `<data>/servers/<uuid>`. Written so an operator reading the file can tell, and
     * so the directory is never treated as the node's to delete; the registry's authority on it is
     * [ExternalServerIndex], and a spec loaded from there is marked in place whatever it says.
     */
    val inPlace: Boolean = false
) {
    /**
     * Whether this software is a proxy.
     *
     * It decides two things a proxy gets wrong otherwise: the shutdown command is `end` rather
     * than `stop`, and `nogui` is a vanilla-server argument that means nothing to a proxy.
     */
    val isProxy: Boolean get() = software.lowercase() in PROXY_SOFTWARE

    /** The console line that stops this server cleanly. */
    val stopCommand: String get() = if (isProxy) "end" else "stop"

    fun sanitised(): ServerSpec = copy(
        // A non-positive value is not nonsense to be corrected, it is the absence of a choice:
        // pinning it to 21 here is what made an automatic pick impossible to express on disk.
        javaMajor = if (javaMajor <= 0) AUTO_JAVA_MAJOR else javaMajor,
        memoryMb = if (memoryMb <= 0) DEFAULT_MEMORY_MB else memoryMb,
        jvmArgs = jvmArgs.filter { it.isNotBlank() },
        jar = jar.ifBlank { DEFAULT_JAR }
    )

    companion object {
        /** Stored when Pano left the Java version to this host. */
        const val AUTO_JAVA_MAJOR = 0

        const val DEFAULT_JAVA_MAJOR = 21
        const val DEFAULT_MEMORY_MB = 2048
        const val DEFAULT_JAR = "server.jar"

        val PROXY_SOFTWARE = setOf("velocity", "bungeecord", "waterfall")
    }
}
