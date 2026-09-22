package com.panomc.platform.node

/**
 * Version of the Pano <-> node wire protocol this platform speaks.
 *
 * A node announces its own version in `NODE_HELLO`; Pano stores it and degrades rather than
 * refusing a node it is newer than, exactly like it does for Minecraft plugins. The number is
 * bumped whenever a message changes shape in a way an older peer cannot ignore.
 */
object NodeProtocol {
    const val VERSION = 5

    /** What a node that announced nothing is assumed to speak. */
    const val LEGACY_PROTOCOL_VERSION = 1

    /**
     * First version that answers `FILE_HASHES`.
     *
     * Gated on the announced version rather than discovered by asking, because the way an older
     * daemon says no is an `UNKNOWN_OPERATION` after a round trip, and the panel needs to know
     * before it draws the button whether pressing it can work.
     */
    const val FILE_HASHES_VERSION = 2

    /**
     * First version that answers `JAVA_CATALOG` and runs `JAVA_INSTALL` / `JAVA_REMOVE` (SM-63,
     * §2.4.28). An older daemon ignores all three without a word, so the only way to find out by
     * asking is a ten-second timeout — see [NodeJavaSupport] for the other signals that count.
     */
    const val JAVA_RUNTIMES_VERSION = 3

    /**
     * First version that understands `NODE_UNINSTALL` and removes a server's backups with
     * `DELETE_SERVER` (SM-64, §2.4.29). An older daemon would ignore the uninstall silently, so a
     * node below this is treated as "cannot uninstall": deleting it needs `force`, and the manual
     * steps are Pano's own guess.
     */
    const val NODE_UNINSTALL_VERSION = 4

    /**
     * First version that understands `SET_NODE_METRICS_INTERVAL` (SM-65, §2.4.30). Shipped in the
     * same protocol bump as the uninstall.
     */
    const val NODE_METRICS_INTERVAL_VERSION = 4

    /**
     * First version that honours `spec.keep` on `REINSTALL_SERVER` (SM-66, §2.4.31): worlds with a
     * `level.dat`, plugins/mods and config files carried over, the old directory kept until the new
     * one is DONE. Shipped in the same (unreleased) bump as the uninstall. An older node carries the
     * `world*` folders only, so Pano offers nothing else to it.
     */
    const val REINSTALL_KEEP_VERSION = 4

    /**
     * First version that adopts a directory where it is (`IMPORT_SERVER` mode `IN_PLACE`), reports
     * `inPlace` and `directory` for every server in its hello and on `IMPORT_RESULT`, refuses a
     * reinstall of such a server with `IN_PLACE_UNSUPPORTED`, and can run in agent mode (`agent`,
     * `agentServer` on the hello). An older node would fail an `IN_PLACE` import as "a mode this
     * node does not know", so Pano refuses to send one to it.
     */
    const val IN_PLACE_VERSION = 5

    /** Whether a node that announced [protocolVersion] can adopt a server in place. */
    fun supportsInPlace(protocolVersion: Int?): Boolean =
        (protocolVersion ?: LEGACY_PROTOCOL_VERSION) >= IN_PLACE_VERSION

    /** Whether a node that announced [protocolVersion] copies a reinstall's whole keep list. */
    fun supportsReinstallKeep(protocolVersion: Int?): Boolean =
        (protocolVersion ?: LEGACY_PROTOCOL_VERSION) >= REINSTALL_KEEP_VERSION

    /** Whether a node that announced [protocolVersion] can uninstall itself. */
    fun supportsUninstall(protocolVersion: Int?): Boolean = (protocolVersion ?: LEGACY_PROTOCOL_VERSION) >= NODE_UNINSTALL_VERSION
}
