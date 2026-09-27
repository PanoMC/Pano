package com.panomc.platform.hosted

import com.panomc.platform.PanoApiManager
import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.auth.panel.log.InstalledPlatformLog
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.PanoConfig
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.SystemProperty
import com.panomc.platform.error.PlatformAlreadyInstalled
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.RegisterUtil
import org.slf4j.Logger
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Runs [HostedAutoSetup] at boot (from `Main`, before the UI manager picks setup-ui or the
 * panel/theme) against the real config, database and Pano API connection. Inert unless the instance
 * is hosted with a configured control-plane client.
 */
@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class HostedAutoSetupRunner(
    private val configManager: ConfigManager,
    private val setupManager: SetupManager,
    private val databaseManager: DatabaseManager,
    private val panoHostManager: PanoHostManager,
    private val panoApiManager: PanoApiManager,
    private val permissionManager: PermissionManager,
    private val logger: Logger
) {
    /** True when setup was finished here (the boot then continues as an installed platform). */
    suspend fun run(): Boolean {
        if (!HostedEnvConfig.current.isHosted || setupManager.getCurrentStep() != 0) return false
        val client = panoHostManager.client ?: return false

        val outcome = HostedAutoSetup(client, Target(), HostedEnvConfig.current.workloadId, { logger.info(it) }).run()

        return outcome == HostedAutoSetup.Outcome.COMPLETED
    }

    private inner class Target : HostedSetupTarget {
        override fun currentStep() = setupManager.getCurrentStep()

        override fun updateConfig(change: (PanoConfig) -> Unit) {
            change(configManager.config)
            configManager.saveConfig()
        }

        override suspend fun initDatabase() {
            try {
                databaseManager.initDatabase(databaseManager.getSqlClient())
            } catch (_: PlatformAlreadyInstalled) {
                // An earlier first boot created the schema and stopped before finishing.
            }
        }

        override suspend fun userStore() = panoHostManager.userStore(databaseManager.getSqlClient())

        override suspend fun registerAdmin(username: String, email: String, password: String): Long {
            RegisterUtil.validateForm(username, email, password, password, true)

            return RegisterUtil.register(
                databaseManager,
                databaseManager.getSqlClient(),
                username,
                email,
                password,
                "pano-host",
                isAdmin = true,
                isSetup = true
            )
        }

        override suspend fun markInstaller(ownerId: Long, username: String) {
            val sqlClient = databaseManager.getSqlClient()
            val properties = databaseManager.systemPropertyDao
            val option = RegisterUtil.WHO_INSTALLED_USER_ID

            if (properties.existsByOption(option, sqlClient)) properties.update(option, ownerId.toString(), sqlClient)
            else properties.add(SystemProperty(option = option, value = ownerId.toString()), sqlClient)

            databaseManager.panelActivityLogDao.add(InstalledPlatformLog(ownerId, username), sqlClient)

            permissionManager.refresh()
        }

        override suspend fun connectPlatform(code: String, apiUrl: String?) {
            panoApiManager.connectWithHandoverCode(code, apiUrl)
        }

        override suspend fun finishSetup() = setupManager.finishSetup()
    }
}
