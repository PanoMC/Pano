package com.panomc.node.agent

import com.panomc.node.NodeOptions
import java.io.File
import java.security.MessageDigest

/**
 * Where a Pano Agent lives, and whether this process is one at all (SM-74).
 *
 * The agent is the node's own jar saved as `pano-agent.jar` in an existing server's folder and run
 * from there instead of the server jar. Everything it keeps sits next to the server in
 * `.pano-agent/` — its config and token, its log, downloaded Java runtimes, staged updates,
 * backups — so the folder is the whole installation: moving it moves the agent, and deleting
 * `.pano-agent` (with the jar) removes every trace of it.
 *
 * Deliberately plain JDK: this is read by the launcher before anything heavy is loaded.
 */
data class AgentLayout(
    /** The server folder, canonical. */
    val serverDir: File,
    /** `<server>/.pano-agent`, unless `--data` named another directory. */
    val dataDir: File
) {
    /** `pano-agent-<12 hex>`: the name `--service install` gives this folder's service. */
    val serviceName: String get() = serviceName(serverDir)

    /** Whether [dataDir] is the default one inside the server folder. */
    val defaultDataDir: Boolean get() = dataDir.absoluteFile.normalize() == File(serverDir, DATA_DIRECTORY)

    companion object {
        /** The folder in the server directory that holds everything the agent keeps. */
        const val DATA_DIRECTORY = ".pano-agent"

        /** What the jar is called when Pano serves it as the agent. */
        const val JAR_NAME = "pano-agent.jar"

        /** Every jar whose name starts with this is taken for the agent, never for a server. */
        const val JAR_PREFIX = "pano-agent"

        /** The file whose presence makes a folder an agent's even when nothing else says so. */
        const val CONFIG_FILE = "config.conf"

        /**
         * Whether this invocation is a Pano Agent's: `--agent` (or `PANO_AGENT`, or `--server`), a
         * worker started by the launcher, a jar called `pano-agent*.jar`, or — when no data directory
         * was named — a working directory that already holds `.pano-agent/config.conf`, which is how
         * an agent renamed to `server.jar` for a hosting panel still knows itself.
         */
        fun isAgent(options: NodeOptions, jarPath: String?, workingDir: File): Boolean =
            options.agent ||
                options.agentWorker ||
                isAgentJarName(jarPath?.let { File(it).name }) ||
                (!options.dataDirGiven && File(File(workingDir, DATA_DIRECTORY), CONFIG_FILE).isFile)

        /** The agent's folders, or null when this invocation is an ordinary node's. */
        fun resolve(options: NodeOptions, jarPath: String?, workingDir: File): AgentLayout? {
            if (!isAgent(options, jarPath, workingDir)) {
                return null
            }

            val server = canonical(
                options.agentServer
                    ?.let { File(it) }
                    ?.let { if (it.isAbsolute) it else File(workingDir, it.path) }
                    ?: workingDir
            )

            val data = if (options.dataDirGiven) options.dataDir.absoluteFile.normalize() else File(server, DATA_DIRECTORY)

            return AgentLayout(server, data)
        }

        /** Whether [name] is the agent's jar by its name: `pano-agent*.jar`, any case. */
        fun isAgentJarName(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false

            return lower.startsWith(JAR_PREFIX) && lower.endsWith(".jar")
        }

        /**
         * `pano-agent-` and the first twelve hex digits of the SHA-256 of the folder's path: stable for
         * the folder, distinct between two agents on one host, and short enough to type.
         */
        fun serviceName(serverDir: File): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(serverDir.path.toByteArray(Charsets.UTF_8))

            return "$JAR_PREFIX-" + digest.joinToString("") { "%02x".format(it) }.take(SERVICE_ID_LENGTH)
        }

        private const val SERVICE_ID_LENGTH = 12

        private fun canonical(file: File): File = try {
            file.canonicalFile
        } catch (_: Exception) {
            file.absoluteFile.normalize()
        }
    }
}
