package com.panomc.platform.node

/**
 * Whether the daemon a node is running is older than the one this Pano would hand it.
 *
 * Two questions wearing one coat. For a released Pano it is simply whether the version strings
 * differ, because the daemon ships inside the platform jar and the two move together by
 * construction. For a development build both sides call themselves `local-build` forever, and the
 * jar is nonetheless rebuilt several times an hour — so there the answer has to come from the bytes
 * the node reported in `NODE_HELLO` against the bytes Pano is currently serving.
 *
 * Unknown is never "yes". A node that reported no version, or a Pano with no jar to serve, produces
 * false: offering an update Pano cannot deliver is worse than not offering one.
 */
object NodeUpdateAvailability {
    /** What a jar built outside a release calls itself, on both sides. */
    const val LOCAL_BUILD = "local-build"

    fun isAvailable(
        nodeVersion: String?,
        platformVersion: String?,
        nodeJarSha256: String?,
        servedSha256: String?
    ): Boolean {
        val node = nodeVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val platform = platformVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return false

        if (isLocalBuild(node) || isLocalBuild(platform)) {
            // Both halves of "unknown is never yes" live here rather than in [isSameJar]: not
            // knowing whether the bytes differ is not an update, and not knowing whether they
            // match is not a reason to skip one.
            val reported = nodeJarSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val served = servedSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false

            return !isSameJar(reported, served)
        }

        return node != platform
    }

    /**
     * Whether the node is provably already running the very jar Pano would hand it.
     *
     * Used to skip a `SELF_UPDATE` that would download, verify and stage the bytes the daemon is
     * executing from, then restart it for nothing. Unknown is never "yes": a node that reported no
     * checksum, or a Pano with no jar to serve, gets the update it asked for rather than a silent
     * no-op.
     */
    fun isSameJar(nodeJarSha256: String?, servedSha256: String?): Boolean {
        val reported = nodeJarSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val served = servedSha256?.trim()?.takeIf { it.isNotEmpty() } ?: return false

        return reported.equals(served, ignoreCase = true)
    }

    private fun isLocalBuild(version: String) = version.equals(LOCAL_BUILD, ignoreCase = true)
}
