package com.panomc.platform.server.software

import io.vertx.core.json.JsonObject

/**
 * Reads md-5's Jenkins, which is the only place BungeeCord is published.
 *
 * There are no releases and no version numbers: a build number is the version, and "the latest
 * successful build" is what every BungeeCord network in the world is running. So the catalog
 * offers exactly that -- `latest` first, then the builds behind it for somebody pinning one.
 */
object BungeeCordJenkins {
    /** The archived proxy jar's file name inside a build. */
    const val ARTIFACT_FILE_NAME = "BungeeCord.jar"

    /**
     * The build numbers that finished, newest first.
     *
     * Jenkins answers newest first and that order is kept rather than re-sorted: the numbers are
     * its own and it is the side that knows what "newest" means. A build still running has a null
     * result and is not offered -- there is nothing to download yet.
     */
    fun successfulBuilds(response: JsonObject?): List<String> {
        val builds = response?.getJsonArray("builds") ?: return emptyList()

        return builds
            .mapNotNull { it as? JsonObject }
            .filter { it.getString("result").equals(SUCCESS, ignoreCase = true) }
            .mapNotNull { it.getInteger("number")?.toString() }
    }

    /** The build's own number, which is what `lastSuccessfulBuild` has to be resolved into. */
    fun buildNumber(build: JsonObject?): String? = build?.getInteger("number")?.toString()

    /**
     * Whether this build actually archived the proxy jar.
     *
     * Checked rather than assumed: a build can succeed and still archive nothing (a failed
     * deployment stage, a Jenkins that pruned the artifacts of an old build), and handing a node
     * a URL that answers 404 half an hour into a wizard is a worse failure than not offering the
     * build at all.
     */
    fun hasArtifact(build: JsonObject?, fileName: String = ARTIFACT_FILE_NAME): Boolean {
        val artifacts = build?.getJsonArray("artifacts") ?: return false

        return artifacts
            .mapNotNull { it as? JsonObject }
            .any { it.getString("fileName") == fileName }
    }

    /**
     * The MD5 Jenkins recorded for the Maven artifact of this build.
     *
     * **Not the checksum of the archived jar, and never to be sent to a node as one.** Jenkins
     * fingerprints what Maven installed into the local repository, and BungeeCord's bootstrap
     * module is shaded afterwards, so the two files differ: build 2096's fingerprint for
     * `net.md-5:BungeeCord.jar` is `689e4631…`, while `bootstrap/target/BungeeCord.jar` -- the
     * file the download URL serves -- hashes to `c47c506e…` (verified against builds 2094-2096 on
     * 2026-09-22; the real hash appears on the artifact's own `*fingerprint*` page and nowhere in
     * the build API's `fingerprint[]`).
     *
     * Kept, parsed and tested because the contract asks for the entry and because the mismatch is
     * the kind of thing that gets "fixed" back in six months by somebody reading the same API.
     */
    fun mavenFingerprintMd5(build: JsonObject?, fileName: String = ARTIFACT_FILE_NAME): String? {
        val fingerprints = build?.getJsonArray("fingerprint") ?: return null

        return fingerprints
            .mapNotNull { it as? JsonObject }
            // Maven fingerprints are named `<groupId>:<file>`, so the artifact is found by the
            // part after the last colon -- which also keeps `BungeeCord-sources.jar` out.
            .firstOrNull { it.getString("fileName")?.substringAfterLast(':') == fileName }
            ?.getString("hash")
            ?.takeIf { MD5.matches(it) }
    }

    private const val SUCCESS = "SUCCESS"

    private val MD5 = Regex("^[0-9a-fA-F]{32}$")
}
