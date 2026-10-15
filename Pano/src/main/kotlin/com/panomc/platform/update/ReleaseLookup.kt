package com.panomc.platform.update

import com.panomc.platform.ReleaseStage
import com.panomc.platform.util.NetworkFailureUtil
import com.panomc.platform.util.UpdateSource
import com.panomc.platform.util.VersionUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The repositories Pano looks up releases for, and their slug on the Pano API. */
enum class ReleaseProduct(val apiSlug: String, val repo: String) {
    PANO("pano", "PanoMC/Pano"),
    PANO_MC_PLUGIN("pano-mc-plugin", "PanoMC/pano-mc-plugin");

    /**
     * Where [assetName] of the release tagged [tag] downloads from. Always built here from the tag,
     * never taken from a lookup answer, so whoever answers "which version is newest" can never point
     * Pano at a different file host.
     */
    fun assetUrl(tag: String, assetName: String) = "https://github.com/$repo/releases/download/$tag/$assetName"
}

/** A GitHub release asset, only known when GitHub itself answered. */
data class ReleaseAsset(val name: String, val size: Long?, val digest: String?)

data class ReleaseInfo(
    /** The version without the leading `v`, derived from [tag]. */
    val version: String,
    val tag: String,
    val prerelease: Boolean,
    /** Epoch millis, or null when unknown. */
    val publishedAt: Long?,
    val url: String?,
    val notes: String?,
    /** Empty when the Pano API answered: the API does not list assets, their names are derived from the tag. */
    val assets: List<ReleaseAsset> = emptyList(),
    /**
     * The extension contract level this Pano release offers (doc 04 section 7), from the release asset
     * `pano-api-level.json` the Pano API reads. Null for a release built before levels, or when the answer does
     * not carry it (GitHub does not): the update plan then treats the target as unknown.
     */
    val apiLevel: Int? = null,
    /** The lowest level this release still runs, null when unknown. */
    val minApiLevel: Int? = null
) {
    fun toJson(): JsonObject = JsonObject()
        .put("version", version)
        .put("tag", tag)
        .put("prerelease", prerelease)
        .put("publishedAt", publishedAt)
        .put("url", url)
        .put("notes", notes)
        .put("assets", JsonArray(assets.map {
            JsonObject().put("name", it.name).put("size", it.size).put("digest", it.digest)
        }))
        .put("apiLevel", apiLevel)
        .put("minApiLevel", minApiLevel)

    /** The same release with the levels read from its `pano-api-level.json` asset. */
    fun withLevels(levels: ReleaseApiLevel?): ReleaseInfo =
        if (levels == null) this else copy(apiLevel = levels.apiLevel, minApiLevel = levels.minApiLevel)

    companion object {
        /**
         * A release tag Pano is willing to put in a download URL: `v1.2.3` or `v1.2.3-alpha.4`.
         * Anything else (a slash, `..`, a query) makes the whole answer malformed.
         */
        private val TAG = Regex("^v?\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9]+(\\.[A-Za-z0-9]+)*)?$")

        fun isValidTag(tag: String?) = tag != null && TAG.matches(tag)

        /** Parses one release as the Pano API (and the last-good store) writes it; null if unusable. */
        fun fromJson(json: JsonObject?): ReleaseInfo? {
            json ?: return null

            val tag = (json.getValue("tag") as? String)?.trim()
            if (!isValidTag(tag)) return null

            val publishedAt = when (val value = json.getValue("publishedAt")) {
                is Number -> value.toLong()
                else -> null
            }

            val assets = (json.getValue("assets") as? JsonArray)?.mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                val name = asset.getValue("name") as? String ?: return@mapNotNull null
                ReleaseAsset(name, (asset.getValue("size") as? Number)?.toLong(), asset.getValue("digest") as? String)
            } ?: emptyList()

            return ReleaseInfo(
                version = tag!!.removePrefix("v"),
                tag = tag,
                prerelease = json.getValue("prerelease") as? Boolean ?: VersionUtil.getReleaseType(tag) != "stable",
                publishedAt = publishedAt,
                url = json.getValue("url") as? String,
                notes = json.getValue("notes") as? String,
                assets = assets,
                apiLevel = ReleaseApiLevel.level(json.getValue("apiLevel")),
                minApiLevel = ReleaseApiLevel.level(json.getValue("minApiLevel"))
            )
        }
    }
}

/** Where a [ReleaseList] came from. */
enum class ReleaseOrigin { PANO_API, GITHUB, LAST_GOOD }

/** A channel's releases, newest first, and the one the channel considers newest. */
data class ReleaseList(
    val latest: ReleaseInfo?,
    val releases: List<ReleaseInfo>,
    val fetchedAt: Long,
    val origin: ReleaseOrigin
) {
    fun toJson(): JsonObject = JsonObject()
        .put("latest", latest?.toJson())
        .put("releases", JsonArray(releases.map { it.toJson() }))
        .put("fetchedAt", fetchedAt)
        .put("origin", origin.name)

    companion object {
        fun fromJson(json: JsonObject): ReleaseList? {
            val releases = (json.getValue("releases") as? JsonArray ?: return null)
                .mapNotNull { ReleaseInfo.fromJson(it as? JsonObject) }

            return ReleaseList(
                latest = ReleaseInfo.fromJson(json.getValue("latest") as? JsonObject),
                releases = releases,
                fetchedAt = (json.getValue("fetchedAt") as? Number)?.toLong() ?: 0L,
                origin = runCatching { ReleaseOrigin.valueOf(json.getString("origin")) }.getOrDefault(ReleaseOrigin.GITHUB)
            )
        }
    }
}

/** No source could answer and nothing was stored from an earlier answer. */
class ReleaseLookupFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Answers "which release is newest" for Pano and the Pano MC plugin.
 *
 * Instances often share one egress IP (Pano Host Portals, NAT'd self-hosts) and api.github.com allows
 * sixty anonymous requests an hour per IP, so the Pano API (edge-cached, no per-IP GitHub limit)
 * answers first and GitHub is the fallback. Which of them is asked follows `update-source`:
 *  - [UpdateSource.AUTO]: the Pano API; on a network error, a non-2xx answer or a malformed body, GitHub.
 *  - [UpdateSource.PANO_API]: the Pano API only.
 *  - [UpdateSource.GITHUB]: GitHub only.
 * The last good answer per product and channel is kept in [store] and returned when every source
 * asked failed, so an outage of the website (or GitHub) never blanks the update info.
 *
 * A change of source is logged once per transition, not on every check.
 */
class ReleaseLookup(
    private val webClient: WebClient,
    private val logger: Logger,
    private val store: LastGoodReleaseStore,
    private val panoApiUrl: () -> String,
    private val updateSource: () -> UpdateSource,
    private val githubApiBase: String = GITHUB_API,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The origin each key was last answered from, to log transitions only. */
    private val lastOrigin = ConcurrentHashMap<String, ReleaseOrigin>()

    /**
     * The releases of [product] on [channel]. A null [channel] means every channel merged, newest
     * first (what the managed-server plugin install has always taken).
     *
     * @throws ReleaseLookupFailed when every source failed and nothing was stored before.
     */
    suspend fun lookup(product: ReleaseProduct, channel: ReleaseStage?): ReleaseList {
        val key = storeKey(product, channel)
        val failures = mutableListOf<String>()
        var lastError: Throwable? = null

        for (origin in originsFor(updateSource())) {
            try {
                val list = when (origin) {
                    ReleaseOrigin.PANO_API -> fromPanoApi(product, channel)
                    else -> fromGitHub(product, channel)
                }

                noteOrigin(key, origin, failures)
                store.put(key, list)

                return list
            } catch (e: Exception) {
                lastError = e
                failures += "${originLabel(origin)}: ${describe(e)}"
            }
        }

        val stored = store.get(key)

        if (stored != null) {
            noteOrigin(key, ReleaseOrigin.LAST_GOOD, failures, stored.fetchedAt)

            return stored.copy(origin = ReleaseOrigin.LAST_GOOD)
        }

        lastOrigin.remove(key)

        throw ReleaseLookupFailed(failures.joinToString("; "), lastError)
    }

    private fun noteOrigin(key: String, origin: ReleaseOrigin, failures: List<String>, storedAt: Long? = null) {
        val previous = lastOrigin.put(key, origin)

        if (previous == origin) {
            if (failures.isNotEmpty()) {
                logger.debug("Release info for {} still from {} ({}).", key, originLabel(origin), failures.joinToString("; "))
            }
            return
        }

        when (origin) {
            ReleaseOrigin.PANO_API -> if (previous != null) {
                logger.info("Release info for {} is coming from the Pano API again.", key)
            }

            ReleaseOrigin.GITHUB -> if (failures.isNotEmpty()) {
                logger.warn("Pano API could not answer release info for {} ({}); using GitHub instead.", key, failures.joinToString("; "))
            } else if (previous != null) {
                logger.info("Release info for {} is coming from GitHub again.", key)
            }

            ReleaseOrigin.LAST_GOOD -> logger.warn(
                "Could not look up release info for {} ({}); using the last known answer from {}.",
                key,
                failures.joinToString("; "),
                storedAt?.let { Instant.ofEpochMilli(it).toString() } ?: "an earlier check"
            )
        }
    }

    internal suspend fun fromPanoApi(product: ReleaseProduct, channel: ReleaseStage?): ReleaseList {
        if (channel != null) {
            return fetchPanoApiChannel(product, channel)
        }

        // Every channel: the API answers one channel at a time, so ask alpha and beta and merge.
        val lists = API_CHANNELS.map { fetchPanoApiChannel(product, it) }
        val merged = lists.flatMap { it.releases }
            .distinctBy { it.tag }
            .sortedWith(NEWEST_FIRST)

        return ReleaseList(merged.firstOrNull(), merged, clock(), ReleaseOrigin.PANO_API)
    }

    private suspend fun fetchPanoApiChannel(product: ReleaseProduct, channel: ReleaseStage): ReleaseList {
        val base = panoApiUrl().trim().trimEnd('/')
        val url = "${if (base.contains("://")) base else "https://$base"}/releases/${product.apiSlug}?channel=${apiChannel(channel)}"

        val response = webClient.getAbs(url)
            .timeout(REQUEST_TIMEOUT_MS)
            .send()
            .coAwait()

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()}")
        }

        return parsePanoApiBody(response.bodyAsString(), channel, clock())
    }

    internal suspend fun fromGitHub(product: ReleaseProduct, channel: ReleaseStage?): ReleaseList {
        if (channel == ReleaseStage.RELEASE) {
            val response = webClient.getAbs("$githubApiBase/repos/${product.repo}/releases/latest")
                .timeout(REQUEST_TIMEOUT_MS)
                .send()
                .coAwait()

            // No stable release at all is an answer, not a failure.
            if (response.statusCode() == 404) {
                return ReleaseList(null, emptyList(), clock(), ReleaseOrigin.GITHUB)
            }

            if (response.statusCode() != 200) {
                throw IllegalStateException("HTTP ${response.statusCode()}")
            }

            val release = parseGitHubRelease(response.bodyAsJsonObject())
                ?: throw IllegalStateException("malformed release")

            return ReleaseList(release, listOf(release), clock(), ReleaseOrigin.GITHUB)
        }

        val response = webClient.getAbs("$githubApiBase/repos/${product.repo}/releases?per_page=$GITHUB_PAGE_SIZE")
            .timeout(REQUEST_TIMEOUT_MS)
            .send()
            .coAwait()

        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()}")
        }

        val releases = response.bodyAsJsonArray()
            .mapNotNull { element ->
                val json = element as? JsonObject ?: return@mapNotNull null
                if (json.getBoolean("draft", false)) return@mapNotNull null
                parseGitHubRelease(json)
            }
            .filter { channel == null || inChannel(it.tag, channel) }

        return ReleaseList(releases.firstOrNull(), releases, clock(), ReleaseOrigin.GITHUB)
    }

    companion object {
        const val GITHUB_API = "https://api.github.com"
        const val REQUEST_TIMEOUT_MS = 15_000L
        private const val GITHUB_PAGE_SIZE = 30

        /** How many releases per key the last-good store keeps. */
        const val STORED_RELEASES = 20

        /** alpha and beta both contain every stable release, so together they are every release. */
        private val API_CHANNELS = listOf(ReleaseStage.ALPHA, ReleaseStage.BETA)

        private val NEWEST_FIRST = Comparator<ReleaseInfo> { a, b -> VersionUtil.compareVersions(b.tag, a.tag) }

        /**
         * Channel membership, the same on both sources: `stable` = releases without a pre-release
         * part, `beta` = stable + `-beta.*`, `alpha` = stable + `-alpha.*`. Betas are not in alpha:
         * semver puts 1.0.0-beta.34 above 1.0.0-alpha.529, so they would offer alpha installs an
         * older build.
         */
        fun inChannel(tag: String, channel: ReleaseStage): Boolean {
            val type = VersionUtil.getReleaseType(tag)

            return type == "stable" || (channel != ReleaseStage.RELEASE && type == channel.stage)
        }

        /** Pano's release channel as the Pano API names it: `alpha`, `beta` or `stable`. */
        fun apiChannel(channel: ReleaseStage): String = when (channel) {
            ReleaseStage.ALPHA -> "alpha"
            ReleaseStage.BETA -> "beta"
            ReleaseStage.RELEASE -> "stable"
        }

        fun storeKey(product: ReleaseProduct, channel: ReleaseStage?) =
            "${product.apiSlug}:${channel?.let { apiChannel(it) } ?: "all"}"

        fun originsFor(source: UpdateSource): List<ReleaseOrigin> = when (source) {
            UpdateSource.AUTO -> listOf(ReleaseOrigin.PANO_API, ReleaseOrigin.GITHUB)
            UpdateSource.PANO_API -> listOf(ReleaseOrigin.PANO_API)
            UpdateSource.GITHUB -> listOf(ReleaseOrigin.GITHUB)
        }

        private fun originLabel(origin: ReleaseOrigin) = when (origin) {
            ReleaseOrigin.PANO_API -> "Pano API"
            ReleaseOrigin.GITHUB -> "GitHub"
            ReleaseOrigin.LAST_GOOD -> "the last good answer"
        }

        private fun describe(error: Throwable): String =
            if (NetworkFailureUtil.isConnectivityFailure(error)) NetworkFailureUtil.describe(error)
            else error.message ?: error.javaClass.simpleName

        /**
         * Parses a `/releases/<product>?channel=` answer, the Pano API's standard envelope:
         * `{"result":"ok","data":{"channel":…,"latest":<release|null>,"releases":[<release>…]}}`.
         * Anything that does not fit throws, which makes AUTO fall back to GitHub.
         */
        fun parsePanoApiBody(body: String?, channel: ReleaseStage, now: Long): ReleaseList {
            val envelope = try {
                JsonObject(body ?: "")
            } catch (e: Exception) {
                throw IllegalStateException("malformed body")
            }

            if (envelope.getValue("result") != "ok") {
                throw IllegalStateException("result is not ok")
            }

            val json = envelope.getValue("data") as? JsonObject ?: throw IllegalStateException("no data object")

            val answeredChannel = json.getValue("channel")
            if (answeredChannel != null && answeredChannel != apiChannel(channel)) {
                throw IllegalStateException("answered channel $answeredChannel")
            }

            val rawReleases = json.getValue("releases") as? JsonArray ?: throw IllegalStateException("no releases array")
            val releases = rawReleases.map {
                ReleaseInfo.fromJson(it as? JsonObject) ?: throw IllegalStateException("malformed release")
            }

            val latest = if (json.containsKey("latest")) {
                when (val raw = json.getValue("latest")) {
                    null -> null
                    is JsonObject -> ReleaseInfo.fromJson(raw) ?: throw IllegalStateException("malformed latest")
                    else -> throw IllegalStateException("malformed latest")
                }
            } else {
                releases.firstOrNull()
            }

            return ReleaseList(latest, releases, now, ReleaseOrigin.PANO_API)
        }

        /** A GitHub API release object as a [ReleaseInfo]; null when its tag is unusable. */
        fun parseGitHubRelease(json: JsonObject): ReleaseInfo? {
            val tag = (json.getValue("tag_name") as? String)?.trim()
            if (!ReleaseInfo.isValidTag(tag)) return null

            val publishedAt = (json.getValue("published_at") as? String)?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }

            val assets = (json.getValue("assets") as? JsonArray)?.mapNotNull { element ->
                val asset = element as? JsonObject ?: return@mapNotNull null
                val name = asset.getValue("name") as? String ?: return@mapNotNull null
                ReleaseAsset(name, (asset.getValue("size") as? Number)?.toLong(), asset.getValue("digest") as? String)
            } ?: emptyList()

            return ReleaseInfo(
                version = tag!!.removePrefix("v"),
                tag = tag,
                prerelease = json.getBoolean("prerelease", false),
                publishedAt = publishedAt,
                url = json.getValue("html_url") as? String,
                notes = json.getValue("body") as? String,
                assets = assets
            )
        }
    }
}
