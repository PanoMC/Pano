package com.panomc.platform.node

/**
 * Whether a node can download and remove Java runtimes (SM-63, §2.4.28).
 *
 * Decided from the hello, never by asking: a node that does not know `JAVA_CATALOG` does not answer
 * it at all, and a panel that found out by waiting would show a spinner for ten seconds on every
 * old node. Three signals count, any one is enough, because the protocol number is the one a
 * daemon forgets to bump and the other two cannot be sent by a daemon without the feature:
 *
 * - a protocol version of at least [NodeProtocol.JAVA_RUNTIMES_VERSION];
 * - a `javaAutoDownload` flag in the hello, which only a daemon with the feature has a value for;
 * - [CAPABILITY] in the hello's `capabilities`, for a daemon that announces features by name.
 */
object NodeJavaSupport {
    /** The feature name a node may list in its hello's `capabilities`. */
    const val CAPABILITY = "java-downloads"

    fun supportsDownloads(protocolVersion: Int?, javaAutoDownload: Boolean?, capabilities: List<String?>?): Boolean =
        (protocolVersion ?: NodeProtocol.LEGACY_PROTOCOL_VERSION) >= NodeProtocol.JAVA_RUNTIMES_VERSION ||
            javaAutoDownload != null ||
            capabilities?.any { it == CAPABILITY } == true
}
