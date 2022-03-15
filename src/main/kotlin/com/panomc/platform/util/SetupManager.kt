package com.panomc.platform.util

import com.beust.klaxon.JsonObject
import com.panomc.platform.config.ConfigManager
import java.net.InetAddress

class SetupManager(private val mConfigManager: ConfigManager) {

    fun isSetupDone() = getStep() == 4

    fun getCurrentStepData(): JsonObject {
        val data = JsonObject()
        val step = getStep()

        data["step"] = step

        if (step == 1 || step == 3) {
            data["websiteName"] = mConfigManager.getConfig().string("website-name")
            data["websiteDescription"] = mConfigManager.getConfig().string("website-description")
        } else if (step == 2) {
            data["db"] = mapOf(
                "host" to ((mConfigManager.getConfig()["database"] as Map<*, *>)["host"] as String),
                "dbName" to ((mConfigManager.getConfig()["database"] as Map<*, *>)["name"] as String),
                "username" to ((mConfigManager.getConfig()["database"] as Map<*, *>)["username"] as String),
                "password" to ((mConfigManager.getConfig()["database"] as Map<*, *>)["password"] as String),
                "prefix" to ((mConfigManager.getConfig()["database"] as Map<*, *>)["prefix"] as String)
            )
        }

        if (step == 3) {
            val localHost = InetAddress.getLocalHost()

            data["host"] = localHost.hostName
            data["ip"] = localHost.hostAddress

            data["panoAccount"] = mapOf(
                "username" to ((mConfigManager.getConfig()["pano-account"] as Map<*, *>)["username"] as String),
                "email" to ((mConfigManager.getConfig()["pano-account"] as Map<*, *>)["email"] as String),
                "accessToken" to ((mConfigManager.getConfig()["pano-account"] as Map<*, *>)["access-token"] as String)
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

        if (currentStep + 1 > 3)
            setStep(3)
        else
            setStep(currentStep + 1)
    }

    fun finishSetup() {
        setStep(4)
    }

    fun getStep() = (mConfigManager.getConfig()["setup"] as Map<*, *>)["step"] as Int

    private fun setStep(step: Int) {
        @Suppress("UNCHECKED_CAST")
        (mConfigManager.getConfig()["setup"] as MutableMap<String, Any>).replace("step", step)

        mConfigManager.saveConfig()
    }
}