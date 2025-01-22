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
            data.put("websiteName", configManager.getConfig().getString("website-name"))
            data.put("websiteDescription", configManager.getConfig().getString("website-description"))
        }

        if (step == 2) {
            val databaseConfig = configManager.getConfig().getJsonObject("database")

            data.put(
                "database", mapOf(
                    "host" to databaseConfig.getString("host"),
                    "dbName" to databaseConfig.getString("name"),
                    "username" to databaseConfig.getString("username"),
                    "password" to databaseConfig.getString("password"),
                    "prefix" to databaseConfig.getString("prefix")
                )
            )
        }

        if (step == 3) {
            val mailConfig = configManager.getConfig().getJsonObject("email")

            data.put("email", mailConfig)
        }

        if (step == 4) {
            val localHost = InetAddress.getLocalHost()
            val panoAccountConfig = configManager.getConfig().getJsonObject("pano-account")

            data.put("host", localHost.hostName)
            data.put("ip", localHost.hostAddress)

            if (panoApiManager.isConnected()) {
                val panoAccount = JsonObject()
                panoAccount.put("platformId", panoAccountConfig.getString("platform-id"))
                panoAccount.put("username", panoAccountConfig.getString("username"))
                panoAccount.put("email", panoAccountConfig.getString("email"))

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

    fun getCurrentStep() = configManager.getConfig().getJsonObject("setup").getInteger("step")

    private fun updateStep(step: Int) {
        configManager.getConfig().getJsonObject("setup").put("step", step)

        configManager.saveConfig()
    }
}