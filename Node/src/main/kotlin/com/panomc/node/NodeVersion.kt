package com.panomc.node

import java.util.jar.Manifest

/** The daemon's own identity, read from the jar it was launched from. */
object NodeVersion {
    /**
     * Exit code the daemon uses to say "I have staged an update of myself, start me again".
     *
     * A distinct code rather than a plain zero because the thing that restarts the node -- Pano's
     * local-node supervisor, systemd, a container runtime -- has to be able to tell a deliberate
     * update restart from a crash and from an operator stopping the service.
     */
    const val SELF_UPDATE_EXIT_CODE = 75

    /**
     * Another daemon already holds the data directory. Not a failure to retry: the one running is
     * the node, and whoever started this one should leave it be.
     */
    const val ALREADY_RUNNING_EXIT_CODE = 76

    /**
     * This node was removed from Pano and has nothing left to do (SM-64, §2.4.29 B).
     *
     * 78 is `EX_CONFIG` from sysexits.h, and it is what every supervisor is told to leave alone:
     * the unit `install.sh` writes carries `RestartPreventExitStatus=78` and Pano's local-node
     * supervisor never restarts on it. Used both when the uninstall finishes and when a daemon is
     * started on a data directory that still holds the retired marker.
     */
    const val RETIRED_EXIT_CODE = 78

    /**
     * A Pano Agent's worker could not pair, or was started with no pairing at all (SM-74).
     *
     * Only the agent uses it, so its launcher can tell "the code was wrong or expired" from a crash:
     * restarting a worker with the same used-up code every thirty seconds would only repeat the
     * refusal into the server's console forever. 77 is `EX_NOPERM` from sysexits.h. An ordinary
     * node keeps exiting with 1 here, which is what its supervisors already expect.
     */
    const val NOT_PAIRED_EXIT_CODE = 77

    val VERSION: String by lazy {
        try {
            val stream = NodeVersion::class.java.classLoader.getResourceAsStream("META-INF/MANIFEST.MF")

            stream?.use { Manifest(it).mainAttributes.getValue("VERSION") } ?: "local-build"
        } catch (_: Exception) {
            "local-build"
        }
    }

    /**
     * SHA-256 of the jar this daemon is running from, or null when it was not started from one.
     *
     * Announced in `NODE_HELLO` so Pano can answer "is there an update for this node" even when
     * neither side has a version number to compare — which is every development build, where both
     * call themselves [VERSION] `local-build` and are nonetheless rebuilt several times an hour.
     *
     * Computed once: it is ten megabytes of I/O and the file cannot change under a running JVM in
     * any way this process would survive.
     */
    val jarSha256: String? by lazy {
        try {
            jarPath()?.let { com.panomc.node.util.Sha256.of(java.io.File(it)) }
        } catch (_: Exception) {
            null
        }
    }

    /** Where this daemon's own jar is on disk, or null when it was not started from one. */
    fun jarPath(): String? = try {
        val location = NodeVersion::class.java.protectionDomain?.codeSource?.location ?: return null
        val file = java.io.File(location.toURI())

        if (file.isFile) file.absolutePath else null
    } catch (_: Exception) {
        null
    }
}
