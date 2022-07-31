package com.panomc.platform.util

import com.panomc.platform.config.ConfigManager
import io.vertx.core.json.JsonObject
import java.net.InetAddress

class SetupManager(private val mConfigManager: ConfigManager) {

    fun isSetupDone() = getStep() == 5

    fun getCurrentStepData(): JsonObject {
        val data = JsonObject()
        val step = getStep()

        data.put("step", step)

        if (step == 1 || step == 4) {
            data.put("websiteName", mConfigManager.getConfig().getString("website-name"))
            data.put("websiteDescription", mConfigManager.getConfig().getString("website-description"))
        }

        if (step == 2) {
            val databaseConfig = mConfigManager.getConfig().getJsonObject("database")

            data.put(
                "db", mapOf(
                    "host" to databaseConfig.getString("host"),
                    "dbName" to databaseConfig.getString("name"),
                    "username" to databaseConfig.getString("username"),
                    "password" to databaseConfig.getString("password"),
                    "prefix" to databaseConfig.getString("prefix")
                )
            )
        }

        if (step == 3) {
            val mailConfig = mConfigManager.getConfig().getJsonObject("email")

            data.put("address", mailConfig.getString("address"))
            data.put("host", mailConfig.getString("host"))
            data.put("port", mailConfig.getInteger("port"))
            data.put("username", mailConfig.getString("username"))
            data.put("password", mailConfig.getString("password"))
            data.put("useSSL", mailConfig.getBoolean("SSL"))
        }

        if (step == 4) {
            val localHost = InetAddress.getLocalHost()
            val panoAccountConfig = mConfigManager.getConfig().getJsonObject("pano-account")

            data.put("host", localHost.hostName)
            data.put("ip", localHost.hostAddress)

            data.put(
                "panoAccount", mapOf(
                    "username" to panoAccountConfig.getString("username"),
                    "email" to panoAccountConfig.getString("email"),
                    "accessToken" to panoAccountConfig.getString("access-token")
                )
            )
        }

        return data
    }

    fun backStep() {
        val currentStep = getStep()

        if (currentStep - 1 < 0)
            setStep(0)
        else
            setStep(currentStep - 1)
    }

    fun nextStep() {
        val currentStep = getStep()

        if (currentStep + 1 > 4)
            setStep(4)
        else
            setStep(currentStep + 1)
    }

    fun finishSetup() {
        setStep(5)
    }

    fun getStep() = mConfigManager.getConfig().getJsonObject("setup").getInteger("step")

    private fun setStep(step: Int) {
        mConfigManager.getConfig().getJsonObject("setup").put("step", step)

        mConfigManager.saveConfig()
    }
}