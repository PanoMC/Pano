package com.panomc.node.agent

import com.panomc.node.NodeVersion
import com.panomc.node.files.ServerFileDenylist
import java.io.File

/**
 * The Pano Agent's own jar, as the rest of the node has to see it (SM-74).
 *
 * The agent sits in the server's folder next to the server jar, so everything that looks at that
 * folder has to know which jar is not the server's: adoption must never pick it as the jar to
 * launch, a backup must not carry it (restoring an old backup would otherwise downgrade the agent),
 * and a restore must never write over it. "The agent's jar" is every top-level `pano-agent*.jar`,
 * and the jar the admin runs as the agent — which a hosting panel may have made them call
 * `server.jar`. (The worker's own copy, `.pano-agent/worker.jar`, is inside the agent's folder,
 * which backups and the file manager never reach anyway.)
 */
object AgentFiles {
    /**
     * Set by the launcher on its worker (SM-76): the jar the admin runs, which the worker -- run
     * from its own copy in `.pano-agent/worker.jar` -- could not otherwise tell from the server's.
     * An environment variable rather than a flag, so a worker older than the launcher never sees an
     * option it does not know.
     */
    const val ENV_LAUNCHER_JAR = "PANO_AGENT_LAUNCHER_JAR"

    /** The jar this process runs from, canonical; null when it was not started from one. */
    val runningJar: File? by lazy { NodeVersion.jarPath()?.let { canonical(File(it)) } }

    /** The launcher's jar as its worker was told ([ENV_LAUNCHER_JAR]), canonical; null anywhere else. */
    val launcherJar: File? by lazy {
        System.getenv(ENV_LAUNCHER_JAR)?.trim()?.takeIf { it.isNotEmpty() }?.let { canonical(File(it)) }
    }

    /**
     * The agent's jar in the server folder: the launcher's, when this is a worker started from its
     * own copy, and otherwise the jar this process runs from.
     */
    val agentJar: File? get() = launcherJar ?: runningJar

    /**
     * Whether [relative] (a path inside [serverDirectory]) is the agent's jar. Only a top-level
     * entry can be: the agent is always run from the server folder itself.
     */
    fun isOwnJar(serverDirectory: File, relative: String, runningJar: File? = this.agentJar): Boolean {
        val normalised = ServerFileDenylist.normalise(relative)

        if (normalised.isEmpty() || normalised.contains('/')) {
            return false
        }

        if (AgentLayout.isAgentJarName(normalised)) {
            return true
        }

        val jar = runningJar ?: return false

        return jar.name.equals(normalised, ignoreCase = true) && canonical(File(serverDirectory, normalised)) == jar
    }

    private fun canonical(file: File): File = try {
        file.canonicalFile
    } catch (_: Exception) {
        file.absoluteFile.normalize()
    }
}
