package com.panomc.platform.update

import com.panomc.platform.InstallManager.Companion.ResourceType
import com.panomc.platform.config.ConfigManager
import io.vertx.core.Vertx
import io.vertx.core.file.OpenOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpRequest
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.File

/** A resource the store is asked about: what is installed, by id and version. */
data class QueriedResource(val id: String, val type: ResourceType, val version: String)

/**
 * "The newest version of each resource that runs on API levels [minApiLevel]..[apiLevel]" (doc 04 section 7).
 * The range is the one of this Pano at boot, and the one of a later release for the update plan.
 */
data class CompatibleQuery(val apiLevel: Int, val minApiLevel: Int, val resources: List<QueriedResource>)

/**
 * The store's answer for one resource.
 *
 * @property versionId what the file is downloaded by.
 * @property version the version tag, as the store shows it.
 * @property hash SHA-256 of the file when the store told it (the linked query does), checked before installing.
 * @property linked whether the linked-platform query answered, which decides the download route.
 */
data class CompatibleHit(
    val id: String,
    val type: ResourceType,
    val versionId: String,
    val version: String,
    val apiLevel: Int,
    val hash: String? = null,
    val linked: Boolean = false
)

/** A downloaded resource file, ready for the install code. */
data class DownloadedResource(val file: File, val hash: String?)

/**
 * The store answered, but it does not offer the compatibility query (HTTP 404, 405 or 501): an older store, not an
 * outage. The reconcile tells it apart from an unreachable store in its log and in the `lastError` code.
 */
class CompatibilityQueryUnsupported(val status: Int) : IllegalStateException("HTTP $status")

/** The failure for a non-2xx answer of the compatible-version query. */
internal fun queryFailure(status: Int): IllegalStateException =
    if (status == 404 || status == 405 || status == 501) CompatibilityQueryUnsupported(status) else IllegalStateException("HTTP $status")

/**
 * The part of the Pano store the compatibility reconcile and the update plan talk to. Its one real implementation
 * is [PanoCompatibilityStore]; tests use a fake.
 */
interface CompatibilityStore {
    /**
     * The newest compatible version of each resource of [query]; a resource with none is left out. Throws when the
     * store could not be asked at all (network, HTTP error, malformed answer), so a miss and an outage are told apart.
     */
    suspend fun newestCompatible(query: CompatibleQuery): List<CompatibleHit>

    /** Downloads [hit] into [targetFolder] and returns the file. Throws on any failure; nothing is left behind then. */
    suspend fun download(hit: CompatibleHit, targetFolder: File): DownloadedResource
}

/**
 * The Pano API as a [CompatibilityStore] (doc 04 section 7, "Newest compatible in the store").
 *
 * Linked to a platform account: `GET /platform/api/store/resources/versions?apiLevel=&minApiLevel=` (the account's
 * own resources, paid ones included) and the platform download route. Whatever that did not answer, and
 * everything when not linked or when the linked call failed, goes to the anonymous
 * `POST /platform/api/store/compatible-versions` (free resources) and the public version file route.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoCompatibilityStore internal constructor(
    private val vertx: Vertx,
    private val webClient: WebClient,
    private val apiUrl: () -> String,
    private val accessToken: () -> String?
) : CompatibilityStore {
    @Autowired
    constructor(vertx: Vertx, webClient: WebClient, configManager: ConfigManager) : this(
        vertx,
        webClient,
        { configManager.config.panoApiUrl },
        { configManager.config.panoAccount.accessToken.takeIf { it.isNotBlank() } }
    )

    private val logger = LoggerFactory.getLogger(PanoCompatibilityStore::class.java)

    override suspend fun newestCompatible(query: CompatibleQuery): List<CompatibleHit> {
        if (query.resources.isEmpty()) {
            return emptyList()
        }

        val found = linkedMapOf<String, CompatibleHit>()
        var linkedFailure: Exception? = null

        if (accessToken() != null) {
            try {
                queryLinked(query).forEach { found.putIfAbsent(it.id, it) }
            } catch (e: Exception) {
                linkedFailure = e
                logger.warn("The linked store query for compatible versions failed ({}); asking anonymously.", e.message)
            }
        }

        val missing = query.resources.filter { it.id !in found }

        if (missing.isNotEmpty()) {
            try {
                queryAnonymous(query, missing).forEach { found.putIfAbsent(it.id, it) }
            } catch (e: Exception) {
                // The linked answer is still an answer; only when nothing could be asked at all is it an outage.
                if (found.isEmpty() && (linkedFailure != null || accessToken() == null)) {
                    throw e
                }

                logger.warn("The anonymous store query for compatible versions failed: {}", e.message)
            }
        }

        return query.resources.mapNotNull { found[it.id] }
    }

    private suspend fun queryLinked(query: CompatibleQuery): List<CompatibleHit> {
        val response = request(HttpMethod.GET, "/platform/api/store/resources/versions", authenticated = true)
            .addQueryParam("apiLevel", query.apiLevel.toString())
            .addQueryParam("minApiLevel", query.minApiLevel.toString())
            .send()
            .coAwait()

        if (response.statusCode() !in 200..299) {
            throw queryFailure(response.statusCode())
        }

        val data = response.bodyAsJsonObject()?.getValue("data") as? JsonArray
            ?: throw IllegalStateException("malformed answer")

        val asked = query.resources.associateBy { it.id }

        return data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.getString("id") ?: return@mapNotNull null
            val resource = asked[id] ?: return@mapNotNull null
            val level = ReleaseApiLevel.level(item.getValue("apiLevel")) ?: return@mapNotNull null

            if (level < query.minApiLevel || level > query.apiLevel) {
                return@mapNotNull null
            }

            val type = runCatching { ResourceType.valueOf(item.getString("type")) }.getOrDefault(resource.type)

            if (type != resource.type) {
                return@mapNotNull null
            }

            CompatibleHit(
                id = id,
                type = type,
                versionId = item.getString("versionId") ?: return@mapNotNull null,
                version = item.getString("version") ?: return@mapNotNull null,
                apiLevel = level,
                hash = item.getString("hash")?.takeIf { it.isNotBlank() },
                linked = true
            )
        }
    }

    private suspend fun queryAnonymous(query: CompatibleQuery, resources: List<QueriedResource>): List<CompatibleHit> {
        val body = JsonObject()
            .put("apiLevel", query.apiLevel)
            .put("minApiLevel", query.minApiLevel)
            .put(
                "resources",
                JsonArray(resources.map { JsonObject().put("id", it.id).put("version", it.version) })
            )

        val response = request(HttpMethod.POST, "/platform/api/store/compatible-versions", authenticated = false)
            .sendJsonObject(body)
            .coAwait()

        if (response.statusCode() !in 200..299) {
            throw queryFailure(response.statusCode())
        }

        val envelope = response.bodyAsJsonObject() ?: throw IllegalStateException("malformed answer")
        val data = envelope.getValue("data") as? JsonObject ?: envelope
        val items = data.getValue("items") as? JsonArray ?: throw IllegalStateException("malformed answer")

        val asked = resources.associateBy { it.id }

        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.getString("id") ?: return@mapNotNull null
            val resource = asked[id] ?: return@mapNotNull null
            val level = ReleaseApiLevel.level(item.getValue("apiLevel")) ?: return@mapNotNull null

            if (level < query.minApiLevel || level > query.apiLevel) {
                return@mapNotNull null
            }

            CompatibleHit(
                id = id,
                type = resource.type,
                versionId = item.getString("versionId") ?: return@mapNotNull null,
                version = item.getString("version") ?: return@mapNotNull null,
                apiLevel = level,
                hash = null,
                linked = false
            )
        }
    }

    override suspend fun download(hit: CompatibleHit, targetFolder: File): DownloadedResource {
        if (!SAFE_SEGMENT.matches(hit.versionId) || !SAFE_SEGMENT.matches(hit.id)) {
            throw IllegalArgumentException("unsafe resource or version id")
        }

        targetFolder.mkdirs()

        val extension = if (hit.type == ResourceType.PLUGIN) "jar" else "zip"
        val target = File(targetFolder, "compat-${hit.id}-${hit.versionId}.$extension")
        val temporary = File(targetFolder, target.name + ".part")

        try {
            val path = if (hit.linked) {
                "/platform/api/store/versions/${hit.versionId}/file"
            } else {
                "/resources/${hit.id}/versions/${hit.versionId}/file"
            }

            var response = pipeTo(request(HttpMethod.GET, path, authenticated = hit.linked, timeoutMs = DOWNLOAD_TIMEOUT_MS).followRedirects(false), temporary)
            var hops = 0

            // The file host (S3 behind the store) is another host and must not see our bearer token, so redirects
            // are followed here, without the header.
            while (response.statusCode() in 300..399 && hops < MAX_REDIRECTS) {
                val location = response.getHeader("Location") ?: throw IllegalStateException("redirect without a location")

                response = pipeTo(webClient.getAbs(location).timeout(DOWNLOAD_TIMEOUT_MS).followRedirects(false), temporary)
                hops++
            }

            if (response.statusCode() != 200) {
                throw IllegalStateException("HTTP ${response.statusCode()}")
            }

            if (!temporary.isFile || temporary.length() == 0L) {
                throw IllegalStateException("empty file")
            }

            target.delete()

            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }

            return DownloadedResource(target, hit.hash)
        } catch (e: Throwable) {
            temporary.delete()
            target.delete()

            throw e
        }
    }

    private suspend fun pipeTo(request: HttpRequest<*>, file: File): io.vertx.ext.web.client.HttpResponse<Void> {
        file.delete()

        val stream = vertx.fileSystem()
            .open(file.absolutePath, OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true))
            .coAwait()

        @Suppress("UNCHECKED_CAST")
        return (request as HttpRequest<io.vertx.core.buffer.Buffer>)
            .`as`(BodyCodec.pipe(stream))
            .send()
            .coAwait()
    }

    private fun request(
        method: HttpMethod,
        path: String,
        authenticated: Boolean,
        timeoutMs: Long = REQUEST_TIMEOUT_MS
    ): HttpRequest<io.vertx.core.buffer.Buffer> {
        val request = webClient.requestAbs(method, apiUrl().trim().trimEnd('/') + path)

        if (authenticated) {
            accessToken()?.let { request.putHeader("Authorization", "Bearer $it") }
        }

        return request.timeout(timeoutMs)
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val DOWNLOAD_TIMEOUT_MS = 300_000L
        const val MAX_REDIRECTS = 3

        /** What goes into a URL path or a file name: a resource id or a version uuid, nothing else. */
        val SAFE_SEGMENT = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
