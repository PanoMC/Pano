package com.panomc.platform.hosted

import com.panomc.platform.AppConstants
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.hosted.PanoHostClient.Bootstrap
import com.panomc.platform.hosted.PanoHostClient.BootstrapSite
import com.panomc.platform.util.UsageMode
import com.panomc.platform.util.WebsiteUrlUtil
import kotlinx.coroutines.delay
import java.net.URI

/**
 * What the hosted first boot changes on the instance (config, database, platform connection).
 * Production wires it to the real managers ([HostedAutoSetupRunner]); tests use an in-memory one.
 */
interface HostedSetupTarget {
    /** `setup.step` of config.conf (0 = wizard not started, 5 = done). */
    fun currentStep(): Int

    /** Applies [change] to the running config and saves config.conf. */
    fun updateConfig(change: (PanoConfig) -> Unit)

    /** Creates the schema; an already-initialised database (a crashed earlier attempt) is fine. */
    suspend fun initDatabase()

    /** Local users for the owner's SSO mapping (the same store the SSO login uses). */
    suspend fun userStore(): HostSsoUserStore

    /** Registers an admin with a password (validated like the wizard); throws when refused (taken, invalid). */
    suspend fun registerAdmin(username: String, email: String, password: String): Long

    /** Records [ownerId] as the user who installed the platform (activity log, telemetry setup date). */
    suspend fun markInstaller(ownerId: Long, username: String)

    /** Redeems the one-time handover code; throws on failure. */
    suspend fun connectPlatform(code: String, apiUrl: String?)

    /** `setup.step = 5` + the plugins' setup-finished event. */
    suspend fun finishSetup()
}

/**
 * Pano Host first boot (host-api.md §Instance bootstrap): on a hosted instance whose setup has not
 * started, asks the control plane for the order-form answers and
 *
 * - **AUTOMATIC**: finishes setup without the installer — site name/description/URL, locale,
 *   telemetry, usage mode WEBSITE, database schema, the owner as admin under the SSO mapping key
 *   (so "Open panel" lands on that user), the optional extra admin, the owner's panomc.com account
 *   connected through the handover code (best effort), `finishSetup()`, `setupCompleted` announced.
 * - **MANUAL**: writes the answers into config.conf so the installer shows them, and stays at step 0.
 * - nothing to do (`NO_BOOTSTRAP`/`BOOTSTRAP_DONE`) or unreachable → the installer is untouched.
 *
 * Restart-safe: until `finishSetup()` the step stays 0, so a crashed first boot runs again; the
 * schema, the owner mapping and the extra admin are all reused when they already exist. The extra
 * admin's password and the handover code are never logged.
 */
class HostedAutoSetup(
    private val client: PanoHostClient,
    private val target: HostedSetupTarget,
    private val workloadId: String?,
    private val log: (String) -> Unit,
    private val attempts: Int = 4,
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    enum class Outcome {
        /** `setup.step != 0`: the wizard was started or setup is done. */
        SKIPPED,

        /** The control plane has no first boot for this instance (or it is done / restored). */
        NOTHING_TO_DO,

        /** The control plane could not be asked; the installer runs, a restart tries again. */
        UNAVAILABLE,

        /** MANUAL: answers written into config, installer at step 0. */
        PREFILLED,

        /** AUTOMATIC: setup finished. */
        COMPLETED,

        /** AUTOMATIC failed part-way; step 0 is kept so the next boot retries. */
        FAILED
    }

    companion object {
        /**
         * The order-form answers → [config]. MANUAL only prefills what the installer shows; AUTOMATIC
         * also sets the usage mode to BOTH (website and server management; the owner can narrow it later).
         */
        fun applySite(config: PanoConfig, site: BootstrapSite, automatic: Boolean) {
            site.siteName?.takeIf { it.isNotBlank() }?.let { config.websiteName = it.trim() }

            val description = site.description.trim()
            if (description.isNotEmpty()) config.websiteDescription = description
            else if (automatic) config.websiteDescription = config.websiteName

            site.websiteUrl?.let { httpUrl(it) }?.let { config.websiteUrl = WebsiteUrlUtil.normalize(it) }

            site.locale?.takeIf { it in AppConstants.AVAILABLE_LOCALES }?.let { config.locale = it }

            val telemetry = config.telemetry ?: PanoConfig.Companion.TelemetryConfig().also { config.telemetry = it }
            telemetry.enabled = site.telemetry

            if (automatic) config.usageMode = UsageMode.BOTH
        }

        private fun httpUrl(raw: String): String? = runCatching { URI(raw.trim()) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty() }
            ?.let { raw.trim() }

        fun hostnameOf(url: String?): String = url?.let { runCatching { URI(it.trim()).host }.getOrNull() } ?: ""
    }

    suspend fun run(): Outcome {
        if (target.currentStep() != 0) return Outcome.SKIPPED

        val bootstrap = fetch() ?: return Outcome.UNAVAILABLE

        return when (bootstrap) {
            is Result.None -> {
                log("Pano Host: no automatic setup for this instance, the installer runs")
                Outcome.NOTHING_TO_DO
            }

            is Result.Got -> when (val answers = bootstrap.value) {
                is Bootstrap.Manual -> {
                    target.updateConfig { applySite(it, answers.site, automatic = false) }
                    log("Pano Host: manual setup, the installer is prefilled with the order form answers")
                    Outcome.PREFILLED
                }

                is Bootstrap.Automatic -> automatic(answers)
            }
        }
    }

    private sealed class Result {
        object None : Result()
        class Got(val value: Bootstrap) : Result()
    }

    /** The bootstrap, retrying transient failures a few times; null when the control plane stays unreachable. */
    private suspend fun fetch(): Result? {
        for (attempt in 1..attempts) {
            try {
                return client.bootstrap()?.let { Result.Got(it) } ?: Result.None
            } catch (e: PanoHostClient.HostApiException) {
                if (!e.retryable || attempt == attempts) {
                    log("Pano Host: could not fetch the first-boot setup (${e.message}), the installer runs")
                    return null
                }

                wait(2_000L shl (attempt - 1))
            }
        }

        return null
    }

    private suspend fun automatic(answers: Bootstrap.Automatic): Outcome {
        log("Pano Host: setting up this instance automatically")

        val ownerId: Long
        val ownerUsername: String

        try {
            target.updateConfig { applySite(it, answers.site, automatic = true) }

            target.initDatabase()

            val identity = PanoHostClient.SsoIdentity(
                accountId = answers.owner.accountId,
                email = answers.owner.email,
                username = answers.owner.username,
                role = "owner",
                workloadId = workloadId ?: "",
                hostname = hostnameOf(answers.site.websiteUrl)
            )

            val store = target.userStore()
            val mapping = HostSsoUserMapper(store).resolve(identity, keepUsername = true)
            ownerId = mapping.userId
            ownerUsername = answers.owner.username

            answers.extraAdmin?.let { admin -> registerExtraAdmin(admin, store) }

            target.markInstaller(ownerId, ownerUsername)
        } catch (e: Throwable) {
            // The message never carries the password (it is only passed to the hasher).
            if (e is VirtualMachineError) throw e
            log("Pano Host: automatic setup failed (${e.javaClass.simpleName}), it is retried on the next start")
            return Outcome.FAILED
        }

        answers.platform?.let { platform ->
            try {
                target.connectPlatform(platform.code, platform.apiUrl)
                log("Pano Host: connected the owner's panomc.com account")
            } catch (e: Throwable) {
                log("Pano Host: could not connect the panomc.com account (${e.javaClass.simpleName}); it can be connected from the panel")
            }
        }

        try {
            target.finishSetup()
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            // `setup.step = 5` is written before the plugins' setup-finished listeners run.
            log("Pano Host: finishing setup reported ${e.javaClass.simpleName}")
            if (target.currentStep() != 5) return Outcome.FAILED
        }

        log("Pano Host: setup finished, owner is local user #$ownerId")

        try {
            client.announceCapabilities(ssoSupported = true, setupCompleted = true)
        } catch (e: PanoHostClient.HostApiException) {
            log("Pano Host: setup completion not reported yet (${e.message}), the announce loop retries")
        }

        return Outcome.COMPLETED
    }

    private suspend fun registerExtraAdmin(admin: PanoHostClient.BootstrapAdmin, store: HostSsoUserStore) {
        if (store.usernameTaken(admin.username)) {
            log("Pano Host: extra admin ${admin.username} not created, the username is taken")
            return
        }

        if (store.emailTaken(admin.email)) {
            log("Pano Host: extra admin ${admin.username} not created, the e-mail is taken")
            return
        }

        try {
            val id = target.registerAdmin(admin.username, admin.email, admin.password)
            log("Pano Host: created extra admin ${admin.username} (local user #$id)")
        } catch (e: Throwable) {
            log("Pano Host: extra admin ${admin.username} not created (${e.javaClass.simpleName})")
        }
    }
}
