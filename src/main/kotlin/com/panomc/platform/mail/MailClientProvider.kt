package com.panomc.platform.mail

import com.panomc.platform.config.ConfigManager
import io.vertx.core.Vertx
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.StartTLSOptions

class MailClientProvider private constructor(vertx: Vertx, configManager: ConfigManager) {
    companion object {
        fun create(vertx: Vertx, configManager: ConfigManager) = MailClientProvider(vertx, configManager)
    }

    private val mailClient by lazy {
        val mailClientConfig = MailConfig()
        val emailConfig = configManager.getConfig().getJsonObject("email")

        mailClientConfig.hostname = emailConfig.getString("host")
        mailClientConfig.port = emailConfig.getInteger("port")

        if (emailConfig.getBoolean("SSL")) {
            mailClientConfig.isSsl = true
        }

        if (emailConfig.getBoolean("TLS")) {
            mailClientConfig.starttls = StartTLSOptions.REQUIRED
        }

        mailClientConfig.username = emailConfig.getString("username")
        mailClientConfig.password = emailConfig.getString("password")

        if (!emailConfig.getString("auth-method").isNullOrEmpty()) {
            mailClientConfig.authMethods = emailConfig.getString("auth-method")
        }

        MailClient.createShared(vertx, mailClientConfig, "mailClient")
    }

    fun provide(): MailClient = mailClient
}