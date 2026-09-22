package com.panomc.platform.server.software

/**
 * Every upstream URL the software catalog talks to, built in one place.
 *
 * Pulled out of the catalog so the address building is a pure function that can be tested without
 * a network, and so there is exactly one list of the hosts Pano fetches from. Each builder returns
 * `null` rather than a string when a segment is not safe to put in a path: versions reach these
 * from a panel request, and a segment containing a slash or a dot-dot would silently turn a
 * version lookup into a request for a completely different endpoint.
 */
object ServerSoftwareUrls {
    const val MOJANG_VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    // PaperMC's "fill" v3 API serves Paper, Folia, Velocity and Waterfall. The v2 API this used
    // to call is gone -- api.papermc.io/v2 answers 410 "sunset" now, which is why every one of
    // those four showed an empty version list -- so there is nothing left to fall back to.
    const val PAPER_API_BASE = "https://fill.papermc.io/v3"

    // Builds are served from a separate object store, and a download URL comes out of the builds
    // response rather than being built here. It is still checked against these hosts before it is
    // handed to a node: the node downloads whatever Pano tells it to, so "whatever an upstream
    // put in a JSON field" is not a good enough answer for where that comes from.
    val PAPER_DOWNLOAD_HOSTS = listOf("fill-data.papermc.io", "fill.papermc.io", "api.papermc.io")

    const val PURPUR_API_BASE = "https://api.purpurmc.org/v2/purpur"

    const val FABRIC_META_BASE = "https://meta.fabricmc.net/v2"

    // SpigotMC publishes no server jar at all -- BuildTools compiles one on the node -- so the
    // only thing fetched from here is the list of revisions BuildTools accepts and what each one
    // was built for. The listing is an Nginx directory index of `<version>.json` files.
    const val SPIGOT_HUB_BASE = "https://hub.spigotmc.org"

    const val SPIGOT_VERSIONS = "$SPIGOT_HUB_BASE/versions/"

    /** BuildTools itself, always the latest build: it updates itself against its own git repos. */
    const val BUILDTOOLS_JAR =
        "$SPIGOT_HUB_BASE/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar"

    // BungeeCord has no release channel and never has: md-5's Jenkins is where it is published,
    // and "the latest successful build" is what everyone runs. `ci.md-5.net` answers a 301 to
    // hub.spigotmc.org/jenkins, so every client used against it has to follow redirects.
    const val BUNGEECORD_CI_BASE = "https://ci.md-5.net/job/BungeeCord"

    /** The archived proxy jar inside a build, relative to the build's artifact root. */
    const val BUNGEECORD_ARTIFACT_PATH = "bootstrap/target/BungeeCord.jar"

    /** What a version of `bungeecord` means when it is not a build number. */
    const val BUNGEECORD_LATEST = "latest"

    /** Jenkins' name for the build [BUNGEECORD_LATEST] stands for. */
    const val BUNGEECORD_LAST_SUCCESSFUL = "lastSuccessfulBuild"

    /**
     * Whether [segment] may be placed in a URL path unescaped.
     *
     * Deliberately an allow-list of what Minecraft versions, project ids, build numbers and jar
     * file names actually look like, rather than an escape: anything outside it is a value Pano
     * has no business forwarding to an upstream API at all.
     */
    fun isSafeSegment(segment: String?): Boolean {
        if (segment.isNullOrEmpty() || segment.length > MAX_SEGMENT_LENGTH) {
            return false
        }

        // "." and ".." pass the character allow-list (a dot is legal inside a version) but are
        // exactly the two segments that move around the path instead of naming something in it.
        if (segment.all { it == '.' }) {
            return false
        }

        return SAFE_SEGMENT.matches(segment)
    }

    fun paperProject(project: String): String? =
        if (isSafeSegment(project)) "$PAPER_API_BASE/projects/$project" else null

    fun paperBuilds(project: String, version: String): String? =
        if (isSafeSegment(project) && isSafeSegment(version)) {
            "$PAPER_API_BASE/projects/$project/versions/$version/builds"
        } else {
            null
        }

    /**
     * Whether a download URL the builds response carried is one Pano will forward to a node.
     *
     * Only https, and only PaperMC's own hosts: a field in someone else's JSON is not authority to
     * make a node fetch and run a jar from an arbitrary address.
     */
    fun isPaperDownload(url: String?): Boolean {
        val parsed = try {
            java.net.URI(url ?: return false)
        } catch (_: Exception) {
            return false
        }

        if (!parsed.scheme.equals("https", ignoreCase = true)) {
            return false
        }

        return parsed.host?.lowercase() in PAPER_DOWNLOAD_HOSTS
    }

    fun purpurVersions(): String = PURPUR_API_BASE

    fun purpurVersion(version: String): String? =
        if (isSafeSegment(version)) "$PURPUR_API_BASE/$version" else null

    fun purpurDownload(version: String, build: String): String? =
        if (isSafeSegment(version) && isSafeSegment(build)) "$PURPUR_API_BASE/$version/$build/download" else null

    /** The per-version metadata BuildTools' own revision list is built from. */
    fun spigotVersion(version: String): String? =
        if (isSafeSegment(version)) "$SPIGOT_HUB_BASE/versions/$version.json" else null

    /**
     * The newest [limit] builds of BungeeCord, with just their number and result.
     *
     * Jenkins' `tree` query is the difference between a 2 KB answer and a megabyte of build
     * metadata, and its brackets and braces have to go on the wire percent-encoded -- a WebClient
     * rejects them raw.
     */
    fun bungeeCordBuilds(limit: Int): String =
        "$BUNGEECORD_CI_BASE/api/json?tree=builds%5Bnumber,result%5D%7B0,$limit%7D"

    /** One build's number, artifacts and fingerprints. [build] is a number or `lastSuccessfulBuild`. */
    fun bungeeCordBuild(build: String): String? =
        if (isSafeSegment(build)) {
            "$BUNGEECORD_CI_BASE/$build/api/json" +
                "?tree=number,timestamp,artifacts%5BfileName,relativePath%5D,fingerprint%5BfileName,hash%5D"
        } else {
            null
        }

    /** Where the proxy jar of one build is downloaded from. */
    fun bungeeCordDownload(build: String): String? =
        if (isSafeSegment(build)) "$BUNGEECORD_CI_BASE/$build/artifact/$BUNGEECORD_ARTIFACT_PATH" else null

    /** Jenkins' path segment for [version], which is `latest` for the last successful build. */
    fun bungeeCordReference(version: String): String =
        if (version.equals(BUNGEECORD_LATEST, ignoreCase = true)) BUNGEECORD_LAST_SUCCESSFUL else version

    fun fabricGameVersions(): String = "$FABRIC_META_BASE/versions/game"

    fun fabricLoaderVersions(gameVersion: String): String? =
        if (isSafeSegment(gameVersion)) "$FABRIC_META_BASE/versions/loader/$gameVersion" else null

    fun fabricInstallerVersions(): String = "$FABRIC_META_BASE/versions/installer"

    /** The ready-made server launcher jar Fabric publishes, so no installer has to be run. */
    fun fabricServerJar(gameVersion: String, loaderVersion: String, installerVersion: String): String? =
        if (isSafeSegment(gameVersion) && isSafeSegment(loaderVersion) && isSafeSegment(installerVersion)) {
            "$FABRIC_META_BASE/versions/loader/$gameVersion/$loaderVersion/$installerVersion/server/jar"
        } else {
            null
        }

    private const val MAX_SEGMENT_LENGTH = 128

    // Minecraft versions ("1.21.4", "1.20.1-rc1", "24w45a"), Paper build file names
    // ("paper-1.21.4-123.jar") and loader versions ("0.16.9") all fit; slashes, spaces, query
    // characters and percent signs do not.
    private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]+$")
}
