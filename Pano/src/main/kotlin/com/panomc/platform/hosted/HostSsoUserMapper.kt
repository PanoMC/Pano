package com.panomc.platform.hosted

import java.security.SecureRandom
import java.util.*

/** Local user storage the SSO mapping needs (DB-backed in production, in-memory in tests). */
interface HostSsoUserStore {
    suspend fun mappedUserId(key: String): Long?
    suspend fun setMapping(key: String, userId: Long)
    suspend fun userExists(userId: Long): Boolean
    suspend fun userIdByEmail(email: String): Long?
    suspend fun userIdByUsername(username: String): Long?
    suspend fun isAdmin(userId: Long): Boolean
    suspend fun isBanned(userId: Long): Boolean
    suspend fun usernameTaken(username: String): Boolean
    suspend fun emailTaken(email: String): Boolean
    suspend fun createUser(username: String, email: String, password: String): Long
    suspend fun grantAdmin(userId: Long)
}

/**
 * Maps a redeemed panomc.com identity to a local panel admin.
 *
 * - `support` → one dedicated `pano_support` admin (never a customer's account).
 * - Anyone else → the local user remembered for that panomc.com account; else the local admin with
 *   the same e-mail; else a new `host_<username>` admin with a random password (the panel's
 *   username rule has no `-`, hence `_`).
 *
 * The mapping is kept by panomc.com account id, so two accounts with the same e-mail local part
 * never share a local user. panomc.com is authoritative for who may manage a hosted instance: a
 * mapped user that lost admin locally gets it back.
 *
 * An owner who chose a local admin on panomc.com (`adminUsername`) signs in as that user when it
 * exists, is still an admin and is not banned. The choice never grants admin and never replaces
 * the remembered mapping, so switching back to automatic restores the previous user; otherwise the
 * normal mapping applies and the reason is logged.
 */
class HostSsoUserMapper(
    private val store: HostSsoUserStore,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {}
) {
    companion object {
        const val SUPPORT_KEY = "pano_host_sso_support"
        const val ACCOUNT_KEY_PREFIX = "pano_host_sso_account:"
        const val SUPPORT_USERNAME = "pano_support"
        const val HOST_PREFIX = "host_"
        private const val MAX_USERNAME = 16
        private const val MIN_USERNAME = 3
    }

    class Denied(reason: String) : RuntimeException(reason)

    /** [chosen]: the owner's chosen local admin was used (the account mapping is untouched). */
    data class Mapping(val userId: Long, val created: Boolean, val chosen: Boolean = false)

    fun keyOf(identity: PanoHostClient.SsoIdentity) =
        if (identity.isSupport) SUPPORT_KEY else ACCOUNT_KEY_PREFIX + identity.accountId.lowercase(Locale.ROOT)

    /**
     * [keepUsername]: a newly created admin takes the panomc.com username itself (hosted first boot,
     * where the owner is the site's own admin) instead of `host_<username>`; a digit suffix is added
     * when it is taken. [preferredUserId]: an existing admin to map an unmapped account to (hosted
     * first boot: the site admin from the order form) instead of matching by e-mail or creating one.
     */
    suspend fun resolve(identity: PanoHostClient.SsoIdentity, keepUsername: Boolean = false, preferredUserId: Long? = null): Mapping {
        chosenAdmin(identity)?.let { return Mapping(it, created = false, chosen = true) }

        val key = keyOf(identity)
        var created = false

        val userId = store.mappedUserId(key)?.takeIf { store.userExists(it) }
            ?: preferredUserId?.takeIf { !identity.isSupport && store.userExists(it) && store.isAdmin(it) }
            ?: (if (identity.isSupport) null else store.userIdByEmail(identity.email)?.takeIf { store.isAdmin(it) })
            ?: create(identity, keepUsername).also { created = true }

        if (store.isBanned(userId)) throw Denied("banned")

        if (!store.isAdmin(userId)) store.grantAdmin(userId)

        if (store.mappedUserId(key) != userId) store.setMapping(key, userId)

        return Mapping(userId, created)
    }

    /** The chosen admin's user id when it may be used as is, else null (reason logged). */
    private suspend fun chosenAdmin(identity: PanoHostClient.SsoIdentity): Long? {
        val username = identity.adminUsername?.takeIf { it.isNotBlank() } ?: return null
        if (identity.isSupport) return null

        val userId = store.userIdByUsername(username)
        val reason = when {
            userId == null || !store.userExists(userId) -> "does not exist"
            !store.isAdmin(userId) -> "is not an admin"
            store.isBanned(userId) -> "is banned"
            else -> return userId
        }

        log("Pano Host SSO: chosen admin account '$username' $reason, using the default mapping")
        return null
    }

    private suspend fun create(identity: PanoHostClient.SsoIdentity, keepUsername: Boolean): Long {
        val plain = identity.username.replace(Regex("[^A-Za-z0-9_]"), "").take(MAX_USERNAME)
        val base = when {
            identity.isSupport -> SUPPORT_USERNAME
            keepUsername && plain.length >= MIN_USERNAME -> plain
            else -> HOST_PREFIX + sanitize(identity.username)
        }
        val username = freeUsername(base)

        val email = when {
            !identity.isSupport && !store.emailTaken(identity.email) -> identity.email
            else -> freeEmail(username, identity.hostname)
        }

        return store.createUser(username, email, randomPassword())
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9_]"), "").take(MAX_USERNAME - HOST_PREFIX.length).ifEmpty { "user" }

    private suspend fun freeUsername(base: String): String {
        val trimmed = base.take(MAX_USERNAME)
        if (!store.usernameTaken(trimmed)) return trimmed

        for (n in 2..9999) {
            val suffix = n.toString()
            val candidate = trimmed.take(MAX_USERNAME - suffix.length) + suffix
            if (!store.usernameTaken(candidate)) return candidate
        }

        throw Denied("no free username")
    }

    /** A placeholder address on the instance's own hostname for an account whose e-mail is taken. */
    private suspend fun freeEmail(username: String, hostname: String): String {
        val domain = hostname.lowercase(Locale.ROOT).takeIf { it.matches(Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")) } ?: "pano.invalid"
        val local = username.lowercase(Locale.ROOT)

        if (!store.emailTaken("$local@$domain")) return "$local@$domain"

        repeat(20) {
            val candidate = "$local.${randomHex(4)}@$domain"
            if (!store.emailTaken(candidate)) return candidate
        }

        throw Denied("no free email")
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Never shown to anyone: the account is reached through SSO (or a password reset). */
    private fun randomPassword(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
}
