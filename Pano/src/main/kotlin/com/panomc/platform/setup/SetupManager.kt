package com.panomc.platform.setup

import com.panomc.platform.PanoApiManager
import com.panomc.platform.PluginEventManager
import com.panomc.platform.api.event.SetupEventListener
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.MariaDBManager
import com.panomc.platform.hosted.HostedEnvConfig
import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.InetAddress

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class SetupManager(private val configManager: ConfigManager, applicationContext: ApplicationContext) {
    private val panoApiManager by lazy {
        applicationContext.getBean(PanoApiManager::class.java)
    }

    private val mariaDBManager by lazy {
        applicationContext.getBean(MariaDBManager::class.java)
    }

    /** Replaceable in tests; the process environment otherwise. */
    internal var envConfig: HostedEnvConfig = HostedEnvConfig.current

    /**
     * On Pano Host the database comes from the container env (re-applied every boot), so the wizard
     * skips its DB step and never accepts or returns DB credentials.
     */
    fun isDatabaseManaged() = envConfig.isHosted && envConfig.databaseManaged

    fun isSetupDone() = getCurrentStep() == 5

    fun getCurrentStepData(): JsonObject {
        val data = JsonObject()
        val step = getCurrentStep()

        data.put("step", step)
        data.put("locale", configManager.config.locale)
        data.put("usageMode", configManager.config.effectiveUsageMode.name)

        if (step == 1 || step == 4) {
            data.put("websiteName", configManager.config.websiteName)
            data.put("websiteDescription", configManager.config.websiteDescription)
            data.put("websiteUrl", configManager.config.websiteUrl)
        }

        if (step == 2) {
            val databaseConfig = configManager.config.database
            val managed = isDatabaseManaged()

            data.put("databaseManaged", managed)
            data.put("dbType", databaseConfig.type)
            data.put("installed", mariaDBManager.isInstalled())
            data.put("installProgress", mariaDBManager.installProgress)
            data.put("portableDatabaseSupported", mariaDBManager.isSupported())
            data.put("supportedSystems", listOf("Windows (x64, ARM64)"))

            data.put(
                "database", mapOf(
                    "host" to databaseConfig.host,
                    "dbName" to databaseConfig.name,
                    "username" to databaseConfig.username,
                    "password" to if (managed) "" else databaseConfig.password,
                    "prefix" to databaseConfig.prefix
                )
            )
        }

        if (step == 3) {
            val mailConfig = configManager.config.email

            data.put("email", mailConfig)
        }

        if (step == 4) {
            val localHost = InetAddress.getLocalHost()
            val panoAccountConfig = configManager.config.panoAccount

            data.put("host", localHost.hostName)
            data.put("ip", localHost.hostAddress)

            if (panoApiManager.isConnected()) {
                val panoAccount = JsonObject()
                panoAccount.put("platformId", panoAccountConfig.platformId)
                panoAccount.put("username", panoAccountConfig.username)
                panoAccount.put("email", panoAccountConfig.email)

                data.put("panoAccount", panoAccount)
            }
        }

        return data
    }

    fun goStep(step: Int) {
        val currentStep = getCurrentStep()

        if (currentStep == step || step > 4 || step > currentStep)
            return
        else if (step < 0)
            updateStep(0)
        else if (step == 2 && isDatabaseManaged())
            updateStep(1)
        else
            updateStep(step)
    }

    fun backStep() {
        val currentStep = getCurrentStep()

        if (currentStep - 1 < 0)
            updateStep(0)
        else if (currentStep - 1 == 2 && isDatabaseManaged())
            updateStep(1)
        else
            updateStep(currentStep - 1)
    }

    fun nextStep() {
        val currentStep = getCurrentStep()

        if (currentStep + 1 > 4)
            updateStep(4)
        else if (currentStep + 1 == 2 && isDatabaseManaged())
            updateStep(3)
        else
            updateStep(currentStep + 1)
    }

    suspend fun finishSetup() {
        updateStep(5)

        PluginEventManager.getPanoEventListeners<SetupEventListener>().forEach { eventHandler ->
            eventHandler.onSetupFinished()
        }
    }

    fun getCurrentStep() = configManager.config.setup.step

    private fun updateStep(step: Int) {
        configManager.config.setup.step = step

        configManager.saveConfig()
    }
}