package com.panomc.platform.panoplus

import com.panomc.platform.PanoApiManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import io.vertx.core.http.HttpMethod
import io.vertx.kotlin.coroutines.coAwait
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Pano+ for this Pano: what panomc.com says about the connected account (cached, see
 * [PanoPlusCache]) and the one switch that turns every Pano+ hint in the panel off for good.
 */
@Lazy
@Component
class PanoPlusManager(
    private val configManager: ConfigManager,
    private val databaseManager: DatabaseManager,
    private val applicationContext: ApplicationContext
) {
    private val logger = LoggerFactory.getLogger(PanoPlusManager::class.java)

    private val panoApiManager by lazy { applicationContext.getBean(PanoApiManager::class.java) }

    internal var cache = PanoPlusCache(
        fetch = ::fetch,
        onError = { logger.warn("Pano+ info unavailable: {}", it.message ?: it.javaClass.simpleName) }
    )

    /** The connected account's Pano+ info; [PanoPlusInfo.DISABLED] when not connected or unknown. */
    suspend fun info(refresh: Boolean = false): PanoPlusInfo {
        val token = configManager.config.panoAccount.accessToken.takeIf { it.isNotBlank() }

        return cache.get(token, refresh)
    }

    /** `<panomc website>/plus`, or null when the website address is not an http(s) URL. */
    fun plusUrl(): String? = plusUrlOf(configManager.config.panoWebsiteUrl)

    suspend fun hintsDismissed(): Boolean =
        databaseManager.systemPropertyDao.getByOption(HINTS_DISMISSED, databaseManager.getSqlClient())?.value == "true"

    suspend fun dismissHints() {
        val sqlClient = databaseManager.getSqlClient()

        if (databaseManager.systemPropertyDao.existsByOption(HINTS_DISMISSED, sqlClient)) {
            databaseManager.systemPropertyDao.update(HINTS_DISMISSED, "true", sqlClient)
        } else {
            databaseManager.systemPropertyDao.add(SystemProperty(option = HINTS_DISMISSED, value = "true"), sqlClient)
        }
    }

    private suspend fun fetch(): PanoPlusInfo {
        val response = panoApiManager.createRequest(HttpMethod.GET, "/platform/api/pano-plus")
            .timeout(FETCH_TIMEOUT_MS)
            .send()
            .coAwait()

        return when (response.statusCode()) {
            200 -> PanoPlusInfo.fromApi(runCatching { response.bodyAsJsonObject() }.getOrNull())
            // An older panomc.com without Pano+, a rejected token or Pano+ switched off: nothing to show.
            401, 403, 404 -> PanoPlusInfo.DISABLED
            else -> throw IllegalStateException("panomc.com answered the Pano+ request with ${response.statusCode()}")
        }
    }

    companion object {
        const val HINTS_DISMISSED = "pano_plus_hints_dismissed"

        private const val FETCH_TIMEOUT_MS = 5_000L

        internal fun plusUrlOf(websiteUrl: String?): String? {
            val base = websiteUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return null

            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null

            return "$base/plus"
        }
    }
}
