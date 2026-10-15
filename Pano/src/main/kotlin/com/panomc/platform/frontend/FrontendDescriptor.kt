package com.panomc.platform.frontend

import com.panomc.platform.UIManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Error
import com.panomc.platform.ui.DescriptorHostNotAllowed
import com.panomc.platform.ui.UpstreamTarget
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.RequestOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import org.slf4j.Logger
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI

/** The descriptor document is not usable. [field] names what is wrong in it. */
class FrontendDescriptorInvalid(field: String, reason: String) : Error(
    "FRONTEND_DESCRIPTOR_INVALID",
    400,
    "",
    mapOf("message" to "The front-end descriptor is not usable at '$field': $reason.", "field" to field, "reason" to reason)
)

/** The descriptor could not be fetched (no answer, an error status, too large). */
class FrontendDescriptorUnreachable(reason: String) : Error(
    "FRONTEND_DESCRIPTOR_UNREACHABLE",
    400,
    "",
    mapOf("message" to "The front-end descriptor could not be fetched: $reason.", "reason" to reason)
)

/**
 * The one JSON shape a non-theme front-end describes itself with (doc 05 section 8.2): `id`, `title`, `urls` (the
 * front-end URL map, step 2) and `settingsSchema` (doc 05 section 9). It is the `manifest.json` of a custom app,
 * the `core-meta.json` of a theme (same keys), or for an EXTERNAL / NONE site the document at `descriptor-url`.
 *
 * @property json what is cached (`frontend_descriptor`), without keys Pano does not read
 * @property schema the parsed `settingsSchema`, null when it declares no `fields`
 */
class FrontendDescriptorDocument(
    val id: String,
    val title: String?,
    val json: JsonObject,
    val schema: FrontendSettingsSchema?
)

/**
 * The descriptor of an `EXTERNAL` / `NONE` front-end: where it is fetched from, the host rule, the fetch itself
 * and the cache in the system property `frontend_descriptor` (read by the URL map for `urls` and by
 * [FrontendSettings] for `settingsSchema`). The descriptor is optional; without one the front-end is called
 * `external` and has no schema.
 *
 * Fetched on "Refresh" ([refresh]); read from the database the first time something asks ([ensureLoaded]). Its
 * `id` is handed to [UIManager.descriptorId], which makes it the key of the front-end's settings.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class FrontendDescriptor(
    private val configManager: ConfigManager,
    private val uiManager: ObjectProvider<UIManager>,
    private val databaseManager: ObjectProvider<DatabaseManager>,
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val urlInputs: ObjectProvider<PlatformFrontendUrlInputs>
) {
    @Volatile
    private var document: FrontendDescriptorDocument? = null

    @Volatile
    private var loaded = false

    /** The cached descriptor, null when there is none. Call [ensureLoaded] first when the database was not read yet. */
    fun cached(): FrontendDescriptorDocument? = document

    /** Reads the cached descriptor from the database once, and gives its `id` to the UI manager. */
    suspend fun ensureLoaded(sqlClient: SqlClient) {
        if (!loaded) load(sqlClient)
    }

    suspend fun load(sqlClient: SqlClient) {
        val text = databaseManager.getObject().systemPropertyDao.getByOption(PROPERTY, sqlClient)?.value

        publish(text?.let {
            try {
                parse(it)
            } catch (e: Error) {
                logger.warn("The cached front-end descriptor is not usable and is ignored: {}", e.message)

                null
            }
        })

        loaded = true
    }

    /** Where the descriptor is fetched from: `descriptor-url`, else `<upstream-url>/.well-known/pano-frontend.json`; null when neither can be built. */
    fun resolvedUrl(): String? {
        val frontend = configManager.config.effectiveFrontend

        return resolveUrl(frontend.descriptorUrl, frontend.upstreamUrl)
    }

    /**
     * Fetches the descriptor, checks it, caches it and returns it. With no URL to fetch from the cache is cleared
     * and null comes back. Throws [DescriptorHostNotAllowed], [FrontendDescriptorUnreachable],
     * [FrontendDescriptorInvalid] or [FrontendSettingsSchemaInvalid]; the previous cache is kept then.
     */
    suspend fun refresh(sqlClient: SqlClient): FrontendDescriptorDocument? {
        val frontend = configManager.config.effectiveFrontend
        val url = resolveUrl(frontend.descriptorUrl, frontend.upstreamUrl)

        if (url == null) {
            clear(sqlClient)

            return null
        }

        checkHost(url, frontend.upstreamUrl, frontend.siteUrl)

        val parsed = parse(fetch(httpClient, url))

        save(parsed, sqlClient)

        return parsed
    }

    /** Forgets the cached descriptor. */
    suspend fun clear(sqlClient: SqlClient) {
        val dao = databaseManager.getObject().systemPropertyDao

        if (dao.getByOption(PROPERTY, sqlClient) != null) {
            dao.deleteByOption(PROPERTY, sqlClient)
        }

        publish(null)
        loaded = true
    }

    private suspend fun save(parsed: FrontendDescriptorDocument, sqlClient: SqlClient) {
        val dao = databaseManager.getObject().systemPropertyDao
        val value = parsed.json.encode()

        if (dao.getByOption(PROPERTY, sqlClient) != null) {
            dao.update(PROPERTY, value, sqlClient)
        } else {
            dao.add(com.panomc.platform.db.model.SystemProperty(option = PROPERTY, value = value), sqlClient)
        }

        publish(parsed)
        loaded = true
    }

    private fun publish(parsed: FrontendDescriptorDocument?) {
        document = parsed
        uiManager.getObject().descriptorId = parsed?.id
        urlInputs.getIfAvailable()?.useDescriptor(parsed?.json)
    }

    companion object {
        /** The system property the URL map reads `urls` from, too. */
        const val PROPERTY = PlatformFrontendUrlInputs.DESCRIPTOR_PROPERTY

        const val WELL_KNOWN_PATH = "/.well-known/pano-frontend.json"
        const val MAX_BYTES = 256 * 1024
        const val FETCH_TIMEOUT_MS = 5_000L

        private val ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")

        /** [descriptorUrl] when set, else the well-known path of [upstreamUrl]; null when neither gives an address. */
        fun resolveUrl(descriptorUrl: String, upstreamUrl: String): String? {
            descriptorUrl.trim().takeIf { it.isNotEmpty() }?.let { return it }

            val upstream = UpstreamTarget.parse(upstreamUrl) ?: return null

            return upstream.origin + WELL_KNOWN_PATH
        }

        /** The host of an `http(s)` URL, lower-case; null for anything else. */
        fun hostOf(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null

            if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null

            return uri.host?.trim('[', ']')?.lowercase()?.takeIf { it.isNotEmpty() }
        }

        /** The descriptor must be served from the host of `upstream-url` or `site-url`. */
        fun checkHost(descriptorUrl: String, upstreamUrl: String, siteUrl: String) {
            val host = hostOf(descriptorUrl) ?: throw DescriptorHostNotAllowed()

            val allowed = listOfNotNull(
                UpstreamTarget.parse(upstreamUrl)?.host?.lowercase(),
                siteUrl.takeIf { it.isNotBlank() }?.let { hostOf(it) }
            )

            if (host !in allowed) throw DescriptorHostNotAllowed()
        }

        /**
         * Reads a descriptor document. `id` is required (a lower-case slug, as a custom app id); `title`, `urls` and
         * `settingsSchema` are optional. A broken schema is [FrontendSettingsSchemaInvalid].
         */
        fun parse(text: String): FrontendDescriptorDocument {
            if (text.length > MAX_BYTES) throw FrontendDescriptorInvalid("document", "TOO_LARGE")

            val json = try {
                JsonObject(text)
            } catch (_: Exception) {
                throw FrontendDescriptorInvalid("document", "NOT_A_JSON_OBJECT")
            }

            val id = (json.getValue("id") as? String)?.trim().orEmpty()

            if (!ID.matches(id)) throw FrontendDescriptorInvalid("id", "INVALID_ID")

            val title = json.getValue("title")

            if (title != null && title !is String) throw FrontendDescriptorInvalid("title", "NOT_A_STRING")

            val urls = json.getValue("urls")

            if (urls != null && urls !is JsonObject) throw FrontendDescriptorInvalid("urls", "NOT_AN_OBJECT")

            val schemaJson = json.getValue("settingsSchema")

            if (schemaJson != null && schemaJson !is JsonObject) throw FrontendDescriptorInvalid("settingsSchema", "NOT_AN_OBJECT")

            val schema = FrontendSettingsSchema.parse(schemaJson)

            val kept = JsonObject().put("id", id)

            (title as String?)?.let { kept.put("title", it) }
            (urls as JsonObject?)?.let { kept.put("urls", it) }
            (schemaJson as JsonObject?)?.let { kept.put("settingsSchema", it) }

            return FrontendDescriptorDocument(id, title as String?, kept, schema)
        }

        /**
         * GETs [url] once: no redirect is followed, the answer must be 200 and at most [maxBytes] long. The body is
         * counted while it arrives, so an endless answer is cut off.
         */
        suspend fun fetch(
            httpClient: HttpClient,
            url: String,
            timeoutMs: Long = FETCH_TIMEOUT_MS,
            maxBytes: Int = MAX_BYTES
        ): String {
            val options = RequestOptions()
                .setAbsoluteURI(url)
                .setMethod(HttpMethod.GET)
                .setFollowRedirects(false)
                .setTimeout(timeoutMs)
                .putHeader("Accept", "application/json")

            val body: Future<Buffer> = httpClient.request(options).compose { request ->
                request.send().compose { response ->
                    if (response.statusCode() != 200) {
                        request.reset()

                        return@compose Future.failedFuture(FrontendDescriptorUnreachable("HTTP ${response.statusCode()}"))
                    }

                    val promise = Promise.promise<Buffer>()
                    val buffer = Buffer.buffer()

                    response.handler { chunk ->
                        buffer.appendBuffer(chunk)

                        if (buffer.length() > maxBytes) {
                            promise.tryFail(FrontendDescriptorUnreachable("TOO_LARGE"))
                            request.reset()
                        }
                    }
                    response.endHandler { promise.tryComplete(buffer) }
                    response.exceptionHandler { promise.tryFail(it) }

                    promise.future()
                }
            }

            try {
                return body.coAwait().toString(Charsets.UTF_8)
            } catch (e: Error) {
                throw e
            } catch (e: Throwable) {
                throw FrontendDescriptorUnreachable(e.message?.take(120) ?: "NO_ANSWER")
            }
        }
    }
}
