package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.config.ConfigMigration
import io.vertx.ext.mail.StartTLSOptions

@Migration
class ConfigMigration4To5 : ConfigMigration(4, 5, "Change email option names") {
    override fun migrate(configManager: ConfigManager) {
        val emailConfig = configManager.getConfig().getJsonObject("email")

        emailConfig.put("hostname", emailConfig.getString("host"))
        emailConfig.put("ssl", emailConfig.getBoolean("SSL"))
        emailConfig.put(
            "starttls", if (emailConfig.getBoolean("TLS"))
                StartTLSOptions.REQUIRED.name
            else
                StartTLSOptions.DISABLED.name
        )
        emailConfig.put("authMethods", emailConfig.getString("auth-method"))
        emailConfig.put("sender", emailConfig.getString("address"))

        emailConfig.remove("host")
        emailConfig.remove("SSL")
        emailConfig.remove("TLS")
        emailConfig.remove("auth-method")
        emailConfig.remove("address")
    }
}