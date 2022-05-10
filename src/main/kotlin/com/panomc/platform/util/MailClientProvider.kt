package com.panomc.platform.util

import com.panomc.platform.config.ConfigManager
import io.vertx.core.Vertx
import io.vertx.ext.mail.MailClient
import io.vertx.ext.mail.MailConfig
import io.vertx.ext.mail.StartTLSOptions

class MailClientProvider private constructor(vertx: Vertx, configManager: ConfigManager) {
    companion object {
        fun create(vertx: Vertx, configManager: ConfigManager) = MailClientProvider(vertx, configManager)
    }

    private val mailClientConfig = MailConfig()
    private val emailConfig = configManager.getConfig().getJsonObject("email")

    private val mailClient by lazy {
        MailClient.createShared(vertx, mailClientConfig, "mailClient")
    }

    init {
        mailClientConfig.hostname = emailConfig.getString("host")
        mailClientConfig.port = emailConfig.getInteger("port")

        if (emailConfig.getBoolean("SSL")) {
            mailClientConfig.starttls = StartTLSOptions.REQUIRED
            mailClientConfig.isSsl = true
        }

        mailClientConfig.username = emailConfig.getString("username")
        mailClientConfig.password = emailConfig.getString("password")

        mailClientConfig.authMethods = "PLAIN"
    }

    fun provide(): MailClient = mailClient
}