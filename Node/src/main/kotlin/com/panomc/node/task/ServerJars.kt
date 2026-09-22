package com.panomc.node.task

import com.panomc.node.agent.AgentFiles
import com.panomc.node.agent.AgentLayout
import java.io.File

/**
 * Which jars in a server folder could be the server, and which one adoption picks (SM-76).
 *
 * Plain JDK on purpose: a Pano Agent's launcher asks the admin which jar to run before anything
 * heavy is loaded, and it has to offer the same default an adoption of that folder would pick, so
 * the rule lives here once and [ServerInspection.findServerJar] is only a front for it.
 */
object ServerJars {
    /** Jar names that are never the server, however they sort. */
    private val IGNORED_PREFIXES = listOf("installer", "forge-installer", "neoforge-installer", "pano")

    /**
     * Every top-level `*.jar` in [directory] except the agent's own ([agentJar], and every
     * `pano-agent*.jar`), sorted by name: what the admin may pick from.
     */
    fun candidates(directory: File, agentJar: File?): List<String> = (directory.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
        .filterNot { AgentLayout.isAgentJarName(it.name) || AgentFiles.isOwnJar(directory, it.name, agentJar) }
        .map { it.name }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /**
     * The jar most likely to be the server.
     *
     * Installers are excluded by name because they are left behind by Forge and NeoForge and are
     * bigger than the jar they produce; `server.jar` wins outright because it is what this daemon
     * itself writes, and beyond that the largest remaining jar in the top level is the server --
     * a mod is kilobytes and a server is tens of megabytes. The agent's own jar is never it.
     */
    fun pick(directory: File, agentJar: File?): String? {
        val jars = candidates(directory, agentJar)
            .filterNot { name -> IGNORED_PREFIXES.any { name.lowercase().startsWith(it) } }
            .filterNot { it.lowercase().contains("installer") }
            .map { File(directory, it) }

        if (jars.isEmpty()) {
            return null
        }

        jars.firstOrNull { it.name.equals("server.jar", ignoreCase = true) }?.let { return it.name }

        return jars.maxByOrNull { it.length() }?.name
    }
}
