package com.panomc.platform.node.dto

/**
 * How a Pano Agent's admin said their server runs, from the agent's first run (SM-76): the
 * optional `agentLaunch` of a hello, sent while the agent has no server yet.
 *
 * Untrusted and possibly from a newer agent, so every field is nullable;
 * [com.panomc.platform.node.ServerStartupLimits] decides what of it is used (see `AgentServerLinkService`).
 */
data class AgentLaunchData(
    /** The server jar's file name in the folder. Informational: the agent adopts with it itself. */
    val jar: String? = null,
    val memoryMb: Int? = null,
    val jvmArgs: List<String?>? = null
)
