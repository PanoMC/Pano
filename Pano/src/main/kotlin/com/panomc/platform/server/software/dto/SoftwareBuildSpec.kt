package com.panomc.platform.server.software.dto

import io.vertx.core.json.JsonObject

/**
 * How a node is to *compile* a server jar it cannot simply download.
 *
 * Spigot is the reason this exists: SpigotMC may not redistribute the server jar, so the only
 * lawful way to install one is to run their BuildTools on the machine that will run the server.
 * Everything that decision needs is resolved here rather than on the node — which revision to
 * build, where the tool comes from and which Java the build has to run on — so the node keeps its
 * one rule: it never chooses a URL for itself.
 *
 * Carried on `INSTALL_SERVER.spec.build`, which is what tells the node to build instead of
 * download. A resolution has either a download URL or one of these, never neither.
 */
data class SoftwareBuildSpec(
    /** The builder to run. Only [BUILDTOOLS] exists today. */
    val tool: String,
    /** What to hand the tool as its revision, which for BuildTools is the Minecraft version. */
    val rev: String,
    /** Where the tool's own jar is downloaded from. */
    val toolUrl: String,
    /**
     * The Java major the *build* needs, which is not always the one the finished server needs.
     *
     * BuildTools refuses to compile an old revision on a modern JDK, and the versions hub is the
     * only place that says which range a revision was made for.
     */
    val javaMajor: Int? = null
) {
    fun toJsonObject(): JsonObject = JsonObject()
        .put("tool", tool)
        .put("rev", rev)
        .put("toolUrl", toolUrl)
        .put("javaMajor", javaMajor)

    companion object {
        /** SpigotMC's BuildTools, run as `java -jar BuildTools.jar --rev <rev>`. */
        const val BUILDTOOLS = "buildtools"
    }
}
