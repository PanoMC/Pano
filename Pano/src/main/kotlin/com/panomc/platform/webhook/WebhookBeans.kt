package com.panomc.platform.webhook

import com.panomc.platform.Main
import com.panomc.platform.PluginManager
import com.panomc.platform.api.webhook.WebhookPublisher
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.dao.WebhookDeliveryDao
import com.panomc.platform.db.dao.WebhookEndpointDao
import com.panomc.platform.hosted.HostedEnvConfig
import com.panomc.platform.plugin.PluginNamespace
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.SecretCipher
import com.panomc.platform.webhook.guard.TargetPolicy
import io.vertx.core.Vertx
import io.vertx.sqlclient.Pool
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import java.io.File

/**
 * Wires the webhook classes (plain Kotlin, no Spring inside them) into the application context. Every bean is
 * `@Lazy`: nothing is created, no key file is written and no timer runs until something asks for a webhook class.
 */
@Configuration
open class WebhookBeans {
    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun webhookRegistry(pluginManager: PluginManager): WebhookRegistry = WebhookRegistry(pluginManager)

    /** The AES key of webhook secrets: `webhook.key` beside `config.conf`, created on first use. */
    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun webhookSecretCipher(): SecretCipher {
        val configFile = File(System.getProperty("pano.configFile", "config.conf")).absoluteFile

        return SecretCipher.load(configFile.parentFile.toPath(), keyFile = SecretCipher.KEY_FILE)
    }

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun webhookService(
        vertx: Vertx,
        databaseManager: DatabaseManager,
        configManager: ConfigManager,
        pluginManager: PluginManager,
        endpoints: WebhookEndpointDao,
        deliveries: WebhookDeliveryDao,
        registry: WebhookRegistry,
        webhookSecretCipher: SecretCipher
    ): WebhookService {
        val allowPrivate = {
            TargetPolicy.effectiveAllowPrivate(
                configManager.config.effectiveWebhooks.allowPrivateTargets, HostedEnvConfig.current.isHosted
            )
        }
        // Main.VERSION reads the jar manifest, which a run from the IDE or a test does not have.
        val version = runCatching { Main.VERSION }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" } ?: "dev"
        val sender = WebhookSender(
            OutboundHttp.create(vertx, version), { webhookSecretCipher }, System::currentTimeMillis, version, allowPrivate
        )

        return WebhookService(
            pool = { databaseManager.getSqlClient() as Pool },
            endpoints = endpoints,
            deliveries = deliveries,
            registry = registry,
            cipher = { webhookSecretCipher },
            sender = sender,
            site = { SiteInfo(configManager.config.websiteName, configManager.config.websiteUrl) },
            activeSources = { pluginManager.getActivePanoPlugins().map { PluginNamespace.of(it) }.toSet() }
        )
    }

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun webhookDispatcher(vertx: Vertx, service: WebhookService, setupManager: SetupManager): WebhookDispatcher =
        WebhookDispatcher(vertx, service, ready = { setupManager.isSetupDone() })

    @Bean
    @Lazy
    @Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
    open fun webhookPublisher(service: WebhookService, registry: WebhookRegistry, dispatcher: WebhookDispatcher): WebhookPublisher =
        WebhookPublisherImpl(service, registry, dispatcher)
}
