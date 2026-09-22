package com.panomc.platform.node.message

import com.panomc.platform.node.NodeMessage

/**
 * Pushes new startup settings for a managed server (`UPDATE_STARTUP`).
 *
 * Applied to the next launch, not the running process: changing the heap size of a JVM that is
 * already up is not a thing, and silently restarting someone's server because they edited a field
 * would be worse than waiting.
 */
data class UpdateStartupMessage(
    val serverUuid: String,
    val spec: StartupSpec
) : NodeMessage

data class StartupSpec(
    val javaMajor: Int?,
    val memoryMb: Int?,
    val jvmArgs: List<String>,
    val port: Int?,
    val autoStart: Boolean,
    val crashRestart: Boolean,
    val properties: Map<String, String>
)
