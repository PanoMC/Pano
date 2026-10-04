package com.panomc.node.config

/**
 * Everything the daemon remembers between restarts.
 *
 * Only two things in here are secrets -- the platform token and the AES key -- and both are
 * useless without the other, which is why the file is written with owner-only permissions where
 * the filesystem supports them. The RSA pair is generated once and kept forever: re-generating it
 * would mean re-pairing, and a node that re-pairs on every restart would pile up rows in Pano.
 */
data class NodeConfig(
    /** Base URL of the Pano this node belongs to, for example `http://127.0.0.1:8080`. */
    var platformUrl: String = "",

    /** Bearer token issued by pairing. Empty means "not paired yet". */
    var token: String = "",

    /** Base64 AES-256 key, already unwrapped with [publicKey]'s private half. */
    var encryptionKey: String = "",

    /** Base64 X.509 public key handed to Pano when pairing. */
    var publicKey: String = "",

    /** Base64 PKCS8 private key. Never leaves this file. */
    var privateKey: String = "",

    /** What the node calls itself in the panel. */
    var name: String = "pano-node",

    /**
     * First port a managed server may bind (`node.port-range.start`). Announced in the hello, so
     * Pano allocates inside the range, and enforced on install: a port Pano asks for outside it is
     * moved into it. `--port-range` / `PANO_NODE_PORT_RANGE` set both ends.
     */
    var portRangeStart: Int = DEFAULT_PORT_RANGE_START,

    /** Last port, inclusive. */
    var portRangeEnd: Int = DEFAULT_PORT_RANGE_END,

    /**
     * Whether the daemon stops its servers when it exits (SM-51, §2.4.16).
     *
     * False, because a daemon exit is hardly ever a request to take Minecraft servers down: it is
     * a restart, a self-update or a crash, and the servers survive it and are adopted again on the
     * next start. An operator who would rather have their servers stopped than running
     * unsupervised sets this to true and gets the behaviour the node had before.
     */
    var stopServersOnExit: Boolean = false,

    /**
     * Whether a Java runtime a server needs and this host lacks is downloaded automatically
     * (`node.java-auto-download`, SM-63). True, because the alternative is a server that cannot
     * start until somebody logs in to install a JDK by hand; an operator who wants no downloads on
     * their host (offline, audited, or simply their own JDKs) turns it off here or with
     * `PANO_NODE_JAVA_AUTO_DOWNLOAD=false`, and the panel's Install button still works.
     */
    var javaAutoDownload: Boolean = true,

    /**
     * Whether a build tool a job needs and this host lacks is downloaded automatically
     * (`node.tool-auto-download`, SM-67) -- today the portable git BuildTools needs to compile
     * Spigot, and the one switch for any tool added later. True for the same reason as
     * [javaAutoDownload]; `PANO_NODE_TOOL_AUTO_DOWNLOAD=false` overrides it without editing the file.
     */
    var toolAutoDownload: Boolean = true,

    /**
     * Whether this daemon is a Pano Agent (`node.agent`): dedicated to the one existing server in
     * [agentServer], which Pano adopts in place on the first connect. An agent refuses every other
     * install or import (`AGENT_SINGLE_SERVER`) and is shown in the panel as its server, never as a
     * node. Written once and kept, so a restart cannot quietly turn an agent back into a node.
     */
    var agent: Boolean = false,

    /**
     * The absolute path of the server an agent runs (`node.agent-server`). Rewritten on every start
     * from where the agent actually is -- in the folder layout (SM-74) the server folder is always
     * the one holding `.pano-agent` -- so a folder that was moved keeps working.
     */
    var agentServer: String = ""
) {
    /** Whether pairing has completed and a socket can be opened. */
    fun isPaired() = platformUrl.isNotBlank() && token.isNotBlank() && encryptionKey.isNotBlank()

    /** The directory this daemon is dedicated to, or null for an ordinary node. */
    fun agentServerOrNull(): String? = agentServer.trim().takeIf { agent && it.isNotEmpty() }

    /** The configured range, corrected when a hand-edited file put it the wrong way round. */
    fun portRange(): IntRange {
        val start = portRangeStart.coerceIn(1, 65535)
        val end = portRangeEnd.coerceIn(1, 65535)

        return if (start <= end) start..end else end..start
    }

    companion object {
        const val DEFAULT_PORT_RANGE_START = 25565
        const val DEFAULT_PORT_RANGE_END = 25600
    }
}
