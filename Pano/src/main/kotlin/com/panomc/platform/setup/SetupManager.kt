package com.panomc.platform.setup

import com.panomc.platform.PanoApiManager
import com.panomc.platform.config.ConfigManager
import io.vertx.core.json.JsonObject
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.InetAddress

@Lazy
@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class SetupManager(private val configManager: ConfigManager, private val panoApiManager: PanoApiManager) {

    fun isSetupDone() = getCurrentStep() == 5

    fun getCurrentStepData(): JsonObject {
        val data = JsonObject()
        val step = getCurrentStep()

        data.put("step", step)

        if (step == 1 || step == 4) {
            data.put("websiteName", configManager.config.websiteName)
            data.put("websiteDescription", configManager.config.websiteDescription)
        }

        if (step == 2) {
            val databaseConfig = configManager.config.database

            data.put(
                "database", mapOf(
                    "host" to databaseConfig.host,
                    "dbName" to databaseConfig.name,
                    "username" to databaseConfig.username,
                    "password" to databaseConfig.password,
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
        else
            updateStep(step)
    }

    fun backStep() {
        val currentStep = getCurrentStep()

        if (currentStep - 1 < 0)
            updateStep(0)
        else
            updateStep(currentStep - 1)
    }

    fun nextStep() {
        val currentStep = getCurrentStep()

        if (currentStep + 1 > 4)
            updateStep(4)
        else
            updateStep(currentStep + 1)
    }

    fun finishSetup() {
        updateStep(5)
    }

    fun getCurrentStep() = configManager.config.setup.step

    private fun updateStep(step: Int) {
        configManager.config.setup.step = step

        configManager.saveConfig()
    }
}