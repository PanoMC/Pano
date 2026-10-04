package com.panomc.platform.hosted

import com.panomc.platform.PanoApiManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.PermissionNode.Companion.HolderType
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.db.model.User
import com.panomc.platform.util.BanUtil
import com.panomc.platform.util.PasswordHasher
import com.panomc.platform.util.RegisterUtil
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.net.URI
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Pano Host integration on the instance side: announces `ssoSupported` to the control plane once
 * setup is done (at boot and when the wizard finishes) and redeems SSO tickets into local panel
 * users. Inert unless `PANO_HOSTED`, `PANO_HOST_API_URL` and `PANO_HOST_INSTANCE_SECRET` are set.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class PanoHostManager(
    private val vertx: Vertx,
    private val databaseManager: DatabaseManager,
    private val authProvider: AuthProvider,
    private val permissionManager: PermissionManager,
    private val passwordHasher: PasswordHasher,
    private val configManager: ConfigManager,
    private val logger: Logger
) {
    companion object {
        /** How often the local admin list is compared with the last announced one. */
        const val ADMIN_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /** How long the boot waits for the control plane's website and API addresses before going on with the stored one. */
        const val URL_SYNC_TIMEOUT_MS = 5_000L

        /**
         * The website of the environment this instance lives in (`https://panomc.com`,
         * `https://dev.panomc.com`, `https://local.panomc.com:3003`), taken from the control plane's
         * `websiteUrl` or `manageUrl` ([url]): its scheme, host and port. Null unless that is an http(s)
         * URL with a host.
         */
        fun websiteOriginOf(url: String?): String? {
            val uri = runCatching { URI(url?.trim() ?: return null) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
            val host = uri.host?.takeIf { it.isNotEmpty() && uri.userInfo == null } ?: return null

            return "$scheme://$host" + (if (uri.port > 0) ":${uri.port}" else "")
        }
    }

    private val env get() = HostedEnvConfig.current

    val client: PanoHostClient? by lazy { PanoHostClient.fromEnv(vertx, env) { logger.info(it) } }

    private var announceJob: Job? = null

    /** Hash of the admin list the control plane last accepted; null = not announced yet. */
    @Volatile
    private var announcedAdminsHash: Int? = null

    private var adminCheckTimer: Long? = null

    val ssoEnabled get() = client != null

    private val setupDone get() = configManager.config.setup.step == 5

    /**
     * Points `pano-website-url` and `pano-api-url` at the website and API of this instance's
     * environment, so the panel's and the installer's store, add-on and account links (and the license
     * issuer derived from the website) go to dev.panomc.com or local.panomc.com:3003 instead of
     * panomc.com there. The API is only moved while no panomc.com account is connected: a stored
     * token belongs to the API that issued it. Called at boot before the UIs start, which read both
     * once; an unreachable control plane keeps the stored ones. True when something changed.
     */
    suspend fun syncPanoUrls(): Boolean {
        val client = client ?: return false

        val feed = try {
            withTimeoutOrNull(URL_SYNC_TIMEOUT_MS) { client.noticeFeed() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Pano Host: could not ask for the website address: {}", (e as? PanoHostClient.HostApiException)?.message ?: e.javaClass.simpleName)
            null
        } ?: return false

        val config = configManager.config
        val website = websiteOriginOf(feed.websiteUrl) ?: websiteOriginOf(feed.manageUrl)
        val api = feed.platformApiUrl?.let(PanoApiManager::normalizeApiUrl)
            ?.takeIf { config.panoAccount.platformId.isBlank() && config.panoAccount.accessToken.isBlank() }
        var changed = false

        if (website != null && config.panoWebsiteUrl.trimEnd('/') != website) {
            logger.info("Pano website URL set to {} for this Pano Host environment", website)
            config.panoWebsiteUrl = website
            changed = true
        }

        if (api != null && config.panoApiUrl.trimEnd('/') != api) {
            logger.info("Pano API URL set to {} for this Pano Host environment", api)
            config.panoApiUrl = api
            changed = true
        }

        if (changed) configManager.saveConfig()

        return changed
    }

    /**
     * (Re)starts the announce loop; a newer call replaces a pending one. Once setup is done it also
     * reports `setupCompleted`, which closes the control plane's first-boot bootstrap, and the local
     * admin usernames (the owner's choice of panel account on panomc.com). A light periodic check
     * re-announces when that admin list changes.
     */
    fun announceCapabilities() {
        val client = client ?: return
        val setupCompleted = setupDone

        announceJob?.cancel()
        announceJob = CoroutineScope(vertx.dispatcher()).launch {
            val admins = if (setupCompleted) runCatching { localAdmins() }
                .onFailure { logger.warn("Pano Host: could not list local admins: {}", it.message) }
                .getOrNull() else null

            val announced = client.announceWithRetry(ssoSupported = true, setupCompleted = setupCompleted, admins = admins)
            if (announced && admins != null) announcedAdminsHash = admins.hashCode()
        }

        if (adminCheckTimer == null) {
            adminCheckTimer = vertx.setPeriodic(ADMIN_CHECK_INTERVAL_MS) {
                CoroutineScope(vertx.dispatcher()).launch { checkAdminsChanged() }
            }
        }
    }

    private suspend fun checkAdminsChanged() {
        if (!setupDone || announceJob?.isActive == true) return

        val admins = runCatching { localAdmins() }.getOrNull() ?: return
        if (admins.hashCode() != announcedAdminsHash) announceCapabilities()
    }

    /** Usernames of the unbanned local admins, as [PanoHostClient.normalizeAdmins] reports them. */
    suspend fun localAdmins(): List<String> {
        val sqlClient = databaseManager.getSqlClient()
        val adminIds = permissionManager.getCachedUserIds().filter { authProvider.isUserAdmin(it) }
        if (adminIds.isEmpty()) return emptyList()

        val usernames = databaseManager.userDao.getAllByIds(adminIds, sqlClient)
            .filterNot(BanUtil::isBanned)
            .map { it.username }

        return PanoHostClient.normalizeAdmins(usernames)
    }

    /** The DB-backed store the SSO mapping (and the hosted first boot) creates local admins in. */
    fun userStore(sqlClient: SqlClient): HostSsoUserStore = DatabaseUserStore(sqlClient)

    /** Redeems [ticket] and returns the local user id to log in as. */
    suspend fun redeem(ticket: String): Long {
        val client = client ?: throw IllegalStateException("Pano Host SSO is not configured")
        val identity = client.redeemSso(ticket)

        if (env.workloadId != null && identity.workloadId != env.workloadId) {
            throw HostSsoUserMapper.Denied("ticket for another workload")
        }

        val sqlClient = databaseManager.getSqlClient()
        val mapping = HostSsoUserMapper(DatabaseUserStore(sqlClient), log = { logger.info(it) }).resolve(identity)

        logger.info(
            "Pano Host SSO: {} signed in as local user #{}{}",
            if (identity.isSupport) "support" else "account ${identity.accountId}",
            mapping.userId,
            when {
                mapping.created -> " (created)"
                mapping.chosen -> " (chosen admin account)"
                else -> ""
            }
        )

        return mapping.userId
    }

    private inner class DatabaseUserStore(private val sqlClient: SqlClient) : HostSsoUserStore {
        private val userDao get() = databaseManager.userDao
        private val properties get() = databaseManager.systemPropertyDao

        override suspend fun mappedUserId(key: String) = properties.getByOption(key, sqlClient)?.value?.toLongOrNull()

        override suspend fun setMapping(key: String, userId: Long) {
            if (properties.existsByOption(key, sqlClient)) properties.update(key, userId.toString(), sqlClient)
            else properties.add(SystemProperty(option = key, value = userId.toString()), sqlClient)
        }

        override suspend fun userExists(userId: Long) = userDao.existsById(userId, sqlClient)

        override suspend fun userIdByEmail(email: String) =
            userDao.getUserIdFromUsernameOrEmail(email, sqlClient)?.takeIf { userDao.getEmailFromUserId(it, sqlClient).equals(email, true) }

        override suspend fun userIdByUsername(username: String) = userDao.getUserIdFromUsername(username, sqlClient)

        override suspend fun isAdmin(userId: Long) = authProvider.isUserAdmin(userId)

        override suspend fun isBanned(userId: Long) = userDao.getById(userId, sqlClient)?.let(BanUtil::isBanned) ?: true

        override suspend fun usernameTaken(username: String) = userDao.existsByUsername(username, sqlClient)

        override suspend fun emailTaken(email: String) = userDao.isEmailExists(email, sqlClient)

        override suspend fun createUser(username: String, email: String, password: String): Long {
            val algorithm = PasswordHasher.Algorithm.fromString(configManager.config.auth.passwordHashAlgorithm)
            val user = User(
                username = username,
                email = email,
                registeredIp = "pano-host",
                emailVerified = true,
                mcUuid = RegisterUtil.generateOfflineUuid(username)
            )

            return userDao.add(user, passwordHasher.hash(password, algorithm), sqlClient, false)
        }

        override suspend fun grantAdmin(userId: Long) {
            databaseManager.permissionNodeDao.add(
                PermissionNode(holderType = HolderType.USER, holderId = userId, node = "group.admin", active = true),
                sqlClient
            )
            permissionManager.refresh()
        }
    }
}
