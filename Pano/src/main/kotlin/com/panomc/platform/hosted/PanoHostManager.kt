package com.panomc.platform.hosted

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.Logger
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
    private val env get() = HostedEnvConfig.current

    val client: PanoHostClient? by lazy { PanoHostClient.fromEnv(vertx, env) { logger.info(it) } }

    private var announceJob: Job? = null

    val ssoEnabled get() = client != null

    /** (Re)starts the announce loop; a newer call replaces a pending one. */
    fun announceCapabilities() {
        val client = client ?: return

        announceJob?.cancel()
        announceJob = CoroutineScope(vertx.dispatcher()).launch {
            client.announceWithRetry(ssoSupported = true)
        }
    }

    /** Redeems [ticket] and returns the local user id to log in as. */
    suspend fun redeem(ticket: String): Long {
        val client = client ?: throw IllegalStateException("Pano Host SSO is not configured")
        val identity = client.redeemSso(ticket)

        if (env.workloadId != null && identity.workloadId != env.workloadId) {
            throw HostSsoUserMapper.Denied("ticket for another workload")
        }

        val sqlClient = databaseManager.getSqlClient()
        val mapping = HostSsoUserMapper(DatabaseUserStore(sqlClient)).resolve(identity)

        logger.info(
            "Pano Host SSO: {} signed in as local user #{}{}",
            if (identity.isSupport) "support" else "account ${identity.accountId}",
            mapping.userId,
            if (mapping.created) " (created)" else ""
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
