package com.panomc.platform.mail

import com.panomc.platform.backup.PanoBackupManager
import com.panomc.platform.backup.remote.PanoHostClient
import com.panomc.platform.backup.remote.PanoHostException
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.error.InvalidData
import com.panomc.platform.error.PanoMailConnectRequired
import com.panomc.platform.error.PanoMailNameInvalid
import com.panomc.platform.error.PanoMailNameRateLimited
import com.panomc.platform.error.PanoMailNameTaken
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
    private var cachedBackupFreeGb: Pair<Long, Number?>? = null

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
            shortStatus()
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

    /** `GET /host/connected/mail` with a short timeout; null when not connected or on any failure. Never throws. */
    suspend fun shortStatus(): JsonObject? {
        if (!isConnected()) {
            return null
        }

        return try {
            guarded { clientProvider().mailStatus(Duration.ofSeconds(6)) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `{name, status, reason?, minLength?, maxLength?}`: `status` is AVAILABLE, CURRENT, TAKEN (also for the
     * website's RESERVED), INVALID, or UNKNOWN when not connected or on any failure (timeout ~5 s, no caching).
     * Never throws.
     */
    suspend fun checkName(name: String): Map<String, Any?> {
        val unknown = mapOf<String, Any?>("name" to name, "status" to NAME_UNKNOWN)

        if (!isConnected()) {
            return unknown
        }

        return try {
            val answer = guarded { clientProvider().mailNameCheck(name, Duration.ofSeconds(5)) }
            val status = when (val raw = answer.getString("status")) {
                "AVAILABLE", "CURRENT", "INVALID" -> raw
                "TAKEN", "RESERVED" -> "TAKEN"
                else -> return unknown
            }
            val result = linkedMapOf<String, Any?>("name" to (answer.getString("name") ?: name), "status" to status)

            listOf("reason", "minLength", "maxLength").forEach { key -> answer.getValue(key)?.let { result[key] = it } }

            result
        } catch (_: Exception) {
            unknown
        }
    }

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

    /** The result of switching Pano Mail on: the website's status body and the sender address it resolves to. */
    data class Enabled(val status: JsonObject, val address: String?)

    /**
     * Enables Pano Mail on the website, creating or renaming the address when [name] is given, and resolves the
     * sender: `<localPart>@<name>.<zone>` when [name] or [localPart] is given (local part default: the stored
     * sender's while the provider is PANO_MAIL, else `no-reply`), else the address the website reports.
     *
     * @throws InvalidData when [localPart] is not a plain e-mail local part (max 64 chars)
     * @throws PanoMailConnectRequired when no account is connected
     * @throws PanoMailNameTaken, PanoMailNameInvalid, PanoMailNameRateLimited for the website's `LABEL_*` / `RATE_LIMITED`
     * @throws PanoMailUnavailable when the website refuses or cannot be reached (`extras.reason`)
     */
    suspend fun enableAs(name: String?, localPart: String?): Enabled {
        val local = localPart?.trim()?.takeIf { it.isNotEmpty() }?.also { validateLocalPart(it) }
        val label = name?.trim()?.takeIf { it.isNotEmpty() }

        if (!isConnected()) {
            throw PanoMailConnectRequired()
        }

        val status = try {
            clientCall(label)
        } catch (e: PanoMailException) {
            throw enableFailure(e)
        }

        return Enabled(status, resolveAddress(status, label, local))
    }

    /** The API error for a website refusal of the enable call. */
    private fun enableFailure(e: PanoMailException): Throwable = when (e.code) {
        PanoMailException.CONNECT_REQUIRED -> PanoMailConnectRequired()
        "LABEL_TAKEN", "LABEL_RESERVED" -> PanoMailNameTaken(e.message ?: "")
        "LABEL_INVALID" -> PanoMailNameInvalid(e.message ?: "")
        "RATE_LIMITED" -> PanoMailNameRateLimited(
            e.message ?: "",
            mapOf("minutes" to e.extras.getValue("minutes"), "retryAfter" to e.extras.getValue("retryAfter"))
        )
        else -> PanoMailUnavailable(e.message ?: "", mapOf("reason" to e.reason))
    }

    private fun resolveAddress(status: JsonObject, label: String?, local: String?): String? {
        val sub = status.getJsonObject("subdomain")
        val subName = sub?.getString("name")
        val zone = sub?.getString("zone")

        return if ((label != null || local != null) && !subName.isNullOrBlank() && !zone.isNullOrBlank()) {
            "${local ?: currentLocalPart(configManager.config)}@$subName.$zone"
        } else {
            status.getString("from")?.let { bareAddress(it) }
        }
    }

    /**
     * What [enableAs] would do, without doing it (the set-up wizard: nothing is registered on the website until the
     * set-up is finished, see [enableChosen]). Reads the website's mail status and, when [name] is given, checks the
     * name; the address is `<localPart>@<name>.<zone>` with the zone of the status. `ready` is the status' own.
     *
     * @throws InvalidData, PanoMailConnectRequired, PanoMailNameTaken, PanoMailNameInvalid as [enableAs]
     * @throws PanoMailUnavailable when the website cannot be reached or cannot confirm the name
     */
    suspend fun verifyAs(name: String?, localPart: String?): Enabled {
        val local = localPart?.trim()?.takeIf { it.isNotEmpty() }?.also { validateLocalPart(it) }
        val label = name?.trim()?.takeIf { it.isNotEmpty() }

        if (!isConnected()) {
            throw PanoMailConnectRequired()
        }

        val status = try {
            guarded { clientProvider().mailStatus(Duration.ofSeconds(10)) }
        } catch (e: PanoMailException) {
            throw enableFailure(e)
        }

        if (label != null) {
            val check = checkName(label)

            when (check["status"]) {
                "TAKEN" -> throw PanoMailNameTaken(check["reason"] as? String ?: "")
                "INVALID" -> throw PanoMailNameInvalid(check["reason"] as? String ?: "")
                NAME_UNKNOWN -> throw PanoMailUnavailable("The name could not be checked.", mapOf("reason" to "PANO_HOST_UNAVAILABLE"))
            }

            // The zone is the website's; the name is only applied at the end of the set-up.
            val zone = status.getJsonObject("subdomain")?.getString("zone")
            val sub = JsonObject().put("name", label).put("zone", zone)

            status.put("subdomain", sub)
        }

        return Enabled(status, resolveAddress(status, label, local))
    }

    /**
     * The wizard's step 3 for Pano Mail: [verifyAs], then only this Pano's own config is written (provider
     * `PANO_MAIL`, `enabled`, the sender); the website is not touched until [enableChosen] at the end of the set-up.
     */
    suspend fun choose(from: String?, name: String? = null, localPart: String? = null) {
        val verified = verifyAs(name, localPart)
        val email = configManager.config.email
        val chosen = !name.isNullOrBlank() || !localPart.isNullOrBlank()

        email.provider = MailManager.PROVIDER_PANO_MAIL
        email.enabled = true
        email.sender = (if (chosen) verified.address else null)
            ?: from?.takeIf { it.isNotBlank() }
            ?: verified.address
            ?: email.sender
        email.hostManaged = false

        configManager.saveConfig()
        invalidateStatus()
    }

    /**
     * The end of the set-up for a Pano that chose Pano Mail: switches it on at the website (under the id this Pano
     * keeps), with the address name picked in the wizard (the sender's domain is `<name>.<zone>`). When the website
     * refuses, this Pano goes back to SMTP with e-mail off so it never claims to send through Pano Mail.
     *
     * @return null when Pano Mail is on (or was not chosen), else the error code of the refusal
     */
    suspend fun enableChosen(): String? {
        val email = configManager.config.email

        if (!email.enabled || email.provider != MailManager.PROVIDER_PANO_MAIL) {
            return null
        }

        return try {
            val current = shortStatus()?.getJsonObject("subdomain")
            val zone = current?.getString("zone")
            val domain = bareAddress(email.sender).substringAfter('@', "")
            val name = if (!zone.isNullOrBlank() && domain.endsWith(".$zone")) domain.removeSuffix(".$zone").takeIf { '.' !in it && it.isNotEmpty() } else null

            enableAs(name?.takeIf { it != current?.getString("name") }, null)
            invalidateStatus()

            null
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e

            fallBackToSmtp()

            (e as? com.panomc.platform.model.Error)?.getErrorCode() ?: "PANO_MAIL_UNAVAILABLE"
        }
    }

    /** `backupFreeGb` of the public catalog (Pano Backup storage every account has), cached like [freePlan]; null when unreadable. Never throws. */
    suspend fun freeBackupGb(): Number? {
        val cached = cachedBackupFreeGb

        if (cached != null && now() - cached.first < FREE_PLAN_CACHE_MS) {
            return cached.second
        }

        val gb = try {
            clientProvider().catalog(Duration.ofSeconds(4)).getValue("backupFreeGb") as? Number
        } catch (_: Exception) {
            null
        }

        cachedBackupFreeGb = now() to gb

        return gb
    }

    private suspend fun clientCall(name: String?): JsonObject = guarded { clientProvider().enableMail(name) }

    /**
     * Turns Pano Mail on for this Pano: [enableAs], then stores provider `PANO_MAIL`, `enabled = true` and the
     * sender (the resolved address when [name] / [localPart] is given, else [from], else the website's address).
     * `ready = false` is not a failure (a new address becomes ready shortly).
     *
     * @return the website's status body
     */
    suspend fun activate(from: String?, name: String? = null, localPart: String? = null): JsonObject {
        val enabled = enableAs(name, localPart)
        val email = configManager.config.email
        val chosen = !name.isNullOrBlank() || !localPart.isNullOrBlank()

        email.provider = MailManager.PROVIDER_PANO_MAIL
        email.enabled = true
        email.sender = (if (chosen) enabled.address else null)
            ?: from?.takeIf { it.isNotBlank() }
            ?: enabled.address
            ?: email.sender
        // Hosted installs: the env must not rewrite this block back to the relay.
        email.hostManaged = false

        configManager.saveConfig()
        invalidateStatus()

        return enabled.status
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
        const val NAME_UNKNOWN = "UNKNOWN"
        const val STATUS_CACHE_MS = 60 * 1000L
        const val FREE_PLAN_CACHE_MS = 5 * 60 * 1000L

        /** `{id, name, free, mailsPerMonth, mailsPerDay}` of the account's plan in force; null when [status] is unknown. */
        fun planView(status: JsonObject?): JsonObject? = status?.getJsonObject("plan")?.let {
            JsonObject()
                .put("id", it.getValue("id")).put("name", it.getValue("name")).put("free", it.getValue("free"))
                .put("mailsPerMonth", it.getValue("mailsPerMonth")).put("mailsPerDay", it.getValue("mailsPerDay"))
        }

        /** Where the plan is changed: `<website>/host/manage/mail`. */
        fun manageUrl(config: PanoConfig): String = config.panoWebsiteUrl.trimEnd('/') + "/host/manage/mail"

        /** The panel's Pano Mail view; see `PanelGetPanoMailAPI`. [status] is the website's mail status, null when unknown. */
        fun panelView(config: PanoConfig, status: JsonObject?, freePlan: JsonObject?): Map<String, Any?> {
            val connected = config.panoAccount.accessToken.isNotBlank()
            return mapOf(
                "website" to config.panoWebsiteUrl,
                "connected" to connected,
                "account" to if (connected) mapOf("username" to config.panoAccount.username) else null,
                "provider" to config.email.provider,
                "enabled" to config.email.enabled,
                "ready" to status?.getValue("ready"),
                "from" to status?.getValue("from"),
                "held" to status?.getValue("held"),
                "plan" to planView(status),
                "usage" to status?.getJsonObject("usage"),
                "freePlan" to freePlan,
                "sender" to senderView(config, status),
                "manageUrl" to manageUrl(config)
            )
        }

        private val LOCAL_PART = Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")

        /** The part of [sender] before `@` while the provider is PANO_MAIL, else `no-reply`. */
        fun currentLocalPart(config: PanoConfig): String {
            if (config.email.provider != MailManager.PROVIDER_PANO_MAIL) {
                return "no-reply"
            }

            val bare = bareAddress(config.email.sender)

            return if ('@' in bare) bare.substringBefore('@').ifBlank { "no-reply" } else "no-reply"
        }

        /** `Name <a@b>` → `a@b`; a bare address stays. */
        fun bareAddress(address: String): String {
            val trimmed = address.trim()
            val open = trimmed.lastIndexOf('<')
            val close = trimmed.lastIndexOf('>')

            return if (open >= 0 && close > open) trimmed.substring(open + 1, close).trim() else trimmed
        }

        fun validateLocalPart(localPart: String) {
            if (localPart.length > 64 || !LOCAL_PART.matches(localPart)) {
                throw InvalidData(extras = mapOf("field" to "localPart"))
            }
        }

        /** `{name, zone, localPart}` for the sender pickers; null when the website's status is unknown or has no `subdomain`. */
        fun senderView(config: PanoConfig, status: JsonObject?): Map<String, Any?>? {
            val sub = status?.getJsonObject("subdomain") ?: return null
            val zone = sub.getString("zone") ?: return null

            return mapOf(
                "name" to (sub.getString("name") ?: sub.getString("suggested")),
                "zone" to zone,
                "localPart" to currentLocalPart(config)
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
