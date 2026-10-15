package com.panomc.platform.webhook

import com.panomc.platform.PluginManager
import com.panomc.platform.api.webhook.WebhookPublisher
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.implementation.WebhookDeliveryDaoImpl
import com.panomc.platform.db.implementation.WebhookEndpointDaoImpl
import com.panomc.platform.setup.SetupManager
import com.panomc.platform.util.SecretCipher
import io.vertx.core.Vertx
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.objenesis.ObjenesisStd
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Spring wiring of the webhook classes: `WebhookBeans` resolves every bean by type from the collaborators the
 * platform already has (stand-ins built without their constructors), nothing is created before it is asked for, and the
 * key file of the secrets lands beside `config.conf`.
 */
class WebhookBeansTest {
    private val objenesis = ObjenesisStd()
    private lateinit var vertx: Vertx
    private lateinit var context: AnnotationConfigApplicationContext
    private var previousConfigFile: String? = null

    @BeforeEach
    fun setUp(@TempDir dir: Path) {
        previousConfigFile = System.getProperty("pano.configFile")
        System.setProperty("pano.configFile", dir.resolve("config.conf").toString())

        vertx = Vertx.vertx()
        context = AnnotationConfigApplicationContext()
        context.beanFactory.registerSingleton("vertx", vertx)
        context.beanFactory.registerSingleton("databaseManager", objenesis.newInstance(DatabaseManager::class.java))
        context.beanFactory.registerSingleton("configManager", objenesis.newInstance(ConfigManager::class.java))
        context.beanFactory.registerSingleton("pluginManager", objenesis.newInstance(PluginManager::class.java))
        context.beanFactory.registerSingleton("setupManager", objenesis.newInstance(SetupManager::class.java))
        context.beanFactory.registerSingleton("endpointDao", WebhookEndpointDaoImpl())
        context.beanFactory.registerSingleton("deliveryDao", WebhookDeliveryDaoImpl())
        context.register(WebhookBeans::class.java)
        context.refresh()
    }

    @AfterEach
    fun tearDown() {
        context.close()
        vertx.close().toCompletionStage().toCompletableFuture().get()

        if (previousConfigFile == null) System.clearProperty("pano.configFile") else System.setProperty("pano.configFile", previousConfigFile!!)
    }

    @Test
    fun `the publisher resolves by type with its whole graph and is a singleton`(@TempDir unused: Path) {
        val publisher = context.getBean(WebhookPublisher::class.java)

        assertTrue(publisher is WebhookPublisherImpl)
        assertSame(publisher, context.getBean(WebhookPublisher::class.java))
        assertSame(context.getBean(WebhookService::class.java), context.getBean(WebhookService::class.java))
        assertFalse(context.getBean(WebhookDispatcher::class.java).isStarted, "the timer is armed by the first use, not by the wiring")
    }

    @Test
    fun `nothing is created and no key is written until a webhook class is asked for`() {
        val key = Path.of(System.getProperty("pano.configFile")).resolveSibling(SecretCipher.KEY_FILE)

        assertFalse(Files.exists(key))

        context.getBean(WebhookRegistry::class.java)

        assertFalse(Files.exists(key), "the registry needs no key")

        context.getBean(WebhookService::class.java)

        assertTrue(Files.exists(key))
        assertEquals("webhook.key", key.fileName.toString())
    }
}
