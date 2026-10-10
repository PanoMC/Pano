package com.panomc.platform.mail

import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailUnavailable
import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * A Pano Mail call failed. [code] is the website's (`MAIL_DISABLED`, `MAIL_SENDER_NOT_ALLOWED`, `QUOTA_EXCEEDED`,
 * `MAIL_HELD`, `INVALID_INPUT`, `MAIL_UNAVAILABLE`), [CONNECT_REQUIRED] or [UNAVAILABLE] (the website could not be
 * reached). [extras] carry the website's fields (`reason`, `resetsAt`, …).
 */
class PanoMailException(
    val code: String,
    message: String,
    val extras: JsonObject = JsonObject(),
    cause: Throwable? = null
) : Exception(message, cause) {
    /** The `reason` for the set-up / panel answer: the website's own reason, else its code. */
    val reason: String get() = extras.getString("reason") ?: code

    companion object {
        const val CONNECT_REQUIRED = "CONNECT_REQUIRED"
        const val UNAVAILABLE = "PANO_HOST_UNAVAILABLE"
    }
}

/** Pano Mail: sending through, and managing, the Pano Mail of the panomc.com account this Pano is connected to. */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoMailService internal constructor(
    private val configManager: ConfigManager,
    private val clientProvider: () -> PanoHostClient,
    private val now: () -> Long
) {
    @Autowired
    constructor(configManager: ConfigManager, panoBackupManager: PanoBackupManager) :
        this(configManager, { panoBackupManager.mailClient() }, System::currentTimeMillis)

    @Volatile
    private var cachedFreePlan: Pair<Long, JsonObject?>? = null

    @Volatile
    private var cachedStatus: Triple<Long, String, JsonObject?>? = null

    /**
     * The website's mail status for the panel, cached [STATUS_CACHE_MS] per account; null when not
     * connected or the website cannot be reached. Never throws.
     */
    suspend fun cachedStatus(): JsonObject? {
        if (!isConnected()) {
            return null
        }

        val key = configManager.config.panoAccount.platformId
        val cached = cachedStatus

        if (cached != null && cached.second == key && now() - cached.first < STATUS_CACHE_MS) {
            return cached.third
        }

        val status = try {
            status()
        } catch (_: Exception) {
            null
        }

        cachedStatus = Triple(now(), key, status)

        return status
    }

    /** Forgets the cached status (after enabling / disabling). */
    fun invalidateStatus() {
        cachedStatus = null
    }

    fun isConnected(): Boolean = configManager.config.panoAccount.accessToken.isNotBlank()

    /** `GET /host/connected/mail`. */
    suspend fun status(): JsonObject = guarded { clientProvider().mailStatus() }

    /** Switches Pano Mail on (idempotent); the status body. */
    suspend fun enable(): JsonObject = guarded { clientProvider().enableMail() }

    suspend fun send(from: String, to: List<String>, mime: ByteArray): JsonObject =
        guarded { clientProvider().sendMail(from, to, mime) }

    /**
     * `{mailsPerMonth, mailsPerDay}` of the free plan in the website's public catalog, cached for
     * [FREE_PLAN_CACHE_MS]; null when the catalog cannot be read. Never throws.
     */
    suspend fun freePlan(): JsonObject? {
        val cached = cachedFreePlan

        if (cached != null && now() - cached.first < FREE_PLAN_CACHE_MS) {
            return cached.second
        }

        val plan = try {
            clientProvider().catalog(Duration.ofSeconds(4)).getJsonArray("mailPlans")
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.getBoolean("free") == true }
                ?.let { JsonObject().put("mailsPerMonth", it.getValue("mailsPerMonth")).put("mailsPerDay", it.getValue("mailsPerDay")) }
        } catch (_: Exception) {
            null
        }

        cachedFreePlan = now() to plan

        return plan
    }

    /**
     * Turns Pano Mail on for this Pano: enables it on the website, then stores provider `PANO_MAIL`,
     * `enabled = true` and the sender ([from], else the address the website gave the account).
     * `ready = false` is not a failure (a new address becomes ready shortly).
     *
     * @throws PanoMailConnectRequired when no account is connected
     * @throws PanoMailUnavailable when the website refuses or cannot be reached (`extras.reason`)
     * @return the website's status body
     */
    suspend fun activate(from: String?): JsonObject {
        if (!isConnected()) {
            throw PanoMailConnectRequired()
        }

        val status = try {
            enable()
        } catch (e: PanoMailException) {
            if (e.code == PanoMailException.CONNECT_REQUIRED) throw PanoMailConnectRequired()

            throw PanoMailUnavailable(e.message ?: "", mapOf("reason" to e.reason))
        }

        val email = configManager.config.email

        email.provider = MailManager.PROVIDER_PANO_MAIL
        email.enabled = true
        email.sender = from?.takeIf { it.isNotBlank() } ?: status.getString("from") ?: email.sender
        // Hosted installs: the env must not rewrite this block back to the relay.
        email.hostManaged = false

        configManager.saveConfig()
        invalidateStatus()

        return status
    }

    /** Back to SMTP with e-mail off (the account was disconnected). */
    suspend fun fallBackToSmtp() {
        val email = configManager.config.email

        if (email.provider != MailManager.PROVIDER_PANO_MAIL) {
            return
        }

        email.provider = MailManager.PROVIDER_SMTP
        email.enabled = false

        configManager.saveConfig()
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: PanoHostException) {
        throw map(e)
    }

    companion object {
        const val STATUS_CACHE_MS = 60 * 1000L
        const val FREE_PLAN_CACHE_MS = 5 * 60 * 1000L

        /** The panel's Pano Mail view; see `PanelGetPanoMailAPI`. [status] is the website's mail status, null when unknown. */
        fun panelView(config: PanoConfig, status: JsonObject?, freePlan: JsonObject?): Map<String, Any?> {
            val website = config.panoWebsiteUrl.trimEnd('/')
            val connected = config.panoAccount.accessToken.isNotBlank()
            val plan = status?.getJsonObject("plan")?.let {
                JsonObject()
                    .put("id", it.getValue("id")).put("name", it.getValue("name")).put("free", it.getValue("free"))
                    .put("mailsPerMonth", it.getValue("mailsPerMonth")).put("mailsPerDay", it.getValue("mailsPerDay"))
            }

            return mapOf(
                "website" to config.panoWebsiteUrl,
                "connected" to connected,
                "account" to if (connected) mapOf("username" to config.panoAccount.username) else null,
                "provider" to config.email.provider,
                "enabled" to config.email.enabled,
                "ready" to status?.getValue("ready"),
                "from" to status?.getValue("from"),
                "held" to status?.getValue("held"),
                "plan" to plan,
                "usage" to status?.getJsonObject("usage"),
                "freePlan" to freePlan,
                "manageUrl" to "$website/host/manage/mail"
            )
        }

        internal fun map(e: PanoHostException): PanoMailException {
            val message = when (e.code) {
                PanoHostException.CONNECT_REQUIRED, PanoHostException.INVALID_TOKEN ->
                    "This Pano is not connected to a panomc.com account."
                PanoHostException.UNAVAILABLE -> "Pano Mail could not be reached: ${e.message}"
                "MAIL_DISABLED" -> "Pano Mail is turned off for this Pano."
                "MAIL_SENDER_NOT_ALLOWED" -> "Pano Mail does not allow this sender address."
                "QUOTA_EXCEEDED" -> "The Pano Mail ${e.extras.getString("reason") ?: ""} limit is used up (resets at ${e.extras.getValue("resetsAt")}).".replace("  ", " ")
                "MAIL_HELD" -> "Pano Mail held this message."
                "INVALID_INPUT" -> "Pano Mail refused the message (over 1 MB or more than 50 recipients)."
                "MAIL_UNAVAILABLE" -> "Pano Mail is unavailable: ${e.extras.getString("reason") ?: "unknown reason"}."
                else -> "Pano Mail failed: ${e.code}."
            }

            val code = if (e.code == PanoHostException.INVALID_TOKEN) PanoMailException.CONNECT_REQUIRED else e.code

            return PanoMailException(code, message, e.extras, e)
        }
    }
}
