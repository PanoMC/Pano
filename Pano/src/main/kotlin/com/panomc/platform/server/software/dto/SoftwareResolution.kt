package com.panomc.platform.server.software.dto

import io.vertx.core.json.JsonObject

/**
 * The concrete artifact a node should download for one software and version.
 *
 * This is the whole reason Pano talks to these APIs at all: by the time the node is involved there
 * are no build numbers or manifests left to interpret, only URLs.
 */
data class SoftwareResolution(
    val id: String,
    val version: String,
    val downloadUrl: String?,
    val installerUrl: String? = null,
    /** Build identifier the URL was resolved from, shown so a reinstall can be reasoned about. */
    val build: String? = null,
    /** Checksum the upstream publishes next to the artifact, when it publishes one. */
    val sha256: String? = null,
    /**
     * The same, for an upstream whose checksum is an MD5.
     *
     * Weaker than a SHA-256 and kept anyway: a Jenkins that publishes nothing else still lets the
     * node tell a finished jar from a truncated download or an error page, and checking with the
     * hash somebody actually published beats not checking at all.
     */
    val md5: String? = null,
    /** Java this version needs, so the wizard can label its "automatic" choice. */
    val javaMajor: Int? = null,
    /**
     * The release channel the upstream published this build under, where it publishes one.
     *
     * PaperMC's `STABLE`/`RECOMMENDED` versus its experimental channels is the only way to tell a
     * finished proxy build from a development one whose version number says nothing — see
     * [com.panomc.platform.server.software.SoftwareVersions] for the other half of that rule.
     */
    val channel: String? = null,
    /**
     * Set when there is no jar to download and the node has to build one (Spigot via BuildTools).
     *
     * Mutually exclusive with [downloadUrl] in practice, and the reason a resolution with no
     * download URL is still a usable answer.
     */
    val buildSpec: SoftwareBuildSpec? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("id", id)
        .put("version", version)
        .put("downloadUrl", downloadUrl)
        .put("installerUrl", installerUrl)
        .put("build", build)
        .put("sha256", sha256)
        .put("md5", md5)
        .put("javaMajor", javaMajor)
        .put("channel", channel)
        .put("buildSpec", buildSpec?.toJsonObject())
}
