package com.panomc.platform.api

import com.panomc.platform.*
import com.panomc.platform.api.event.PluginEventListener
import io.vertx.core.Vertx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.pf4j.Plugin
import org.pf4j.PluginState
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.File

abstract class PanoPlugin : Plugin() {
    lateinit var pluginId: String
        internal set
    lateinit var vertx: Vertx
        internal set
    lateinit var pluginEventManager: PluginEventManager
        internal set
    lateinit var pluginUiManager: PluginUiManager
        internal set
    lateinit var environmentType: Main.Companion.EnvironmentType
        internal set
    lateinit var releaseStage: ReleaseStage
        internal set
    lateinit var pluginBeanContext: AnnotationConfigApplicationContext
        internal set
    lateinit var pluginGlobalBeanContext: AnnotationConfigApplicationContext
        internal set
    lateinit var pluginState: PluginState

    lateinit var applicationContext: AnnotationConfigApplicationContext
        internal set

    private val pluginManager by lazy {
        applicationContext.getBean(PluginManager::class.java)
    }
    private val pluginsFolder: String by lazy { pluginManager.pluginsRoot.toAbsolutePath().toString() }
    private val pluginsDataDir: String by lazy { System.getProperty("pano.pluginDataDir", pluginsFolder) }

    val pluginDataFolder: File by lazy {
        val folder = pluginsDataDir + File.separator + pluginId
        val file = File(folder)
        if (!file.exists()) {
            file.mkdirs()
        }
        file
    }

    val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val registeredBeans = mutableListOf<Any>()

    fun registerSingletonGlobal(bean: Any) {
        if (registeredBeans.contains(bean)) {
            return
        }

        pluginGlobalBeanContext.beanFactory.registerSingleton(bean.javaClass.name, bean)

        registeredBeans.add(bean)
    }

    fun register(eventListener: PluginEventListener) {
        pluginEventManager.register(this, eventListener)
    }

    fun unRegisterGlobal(bean: Any) {
        if (!registeredBeans.contains(bean)) {
            return
        }

        val registry = pluginGlobalBeanContext.beanFactory as DefaultListableBeanFactory

        registry.destroySingleton(bean.javaClass.name)

        registeredBeans.remove(bean)
    }

    fun unRegister(eventListener: PluginEventListener) {
        pluginEventManager.unRegister(this, eventListener)
    }

    fun registerCommands(obj: Any) {
        Main.commandManager.registerCommands(obj)
    }

    fun unRegisterCommands(obj: Any) {
        Main.commandManager.unregisterCommands(obj)
    }

    /**
     * Returns Pano's [com.panomc.platform.license.LicenseManager] so a premium plugin
     * can fetch a license JWT from panomc.com at startup. The plugin must verify the JWT
     * with its own embedded public key ([com.panomc.platform.license.SignedLicense.verifySignature])
     * using [getLicenseJwtIssuer] as the expected `iss` claim; the host only parses claims for
     * caching and panel UX.
     */
    fun getLicenseManager(): com.panomc.platform.license.LicenseManager =
        applicationContext.getBean(com.panomc.platform.license.LicenseManager::class.java)

    /**
     * JWT `iss` value to use when verifying license tokens ([com.panomc.platform.license.SignedLicense.verifySignature]).
     * Derived from `pano-website-url` hostname, or from `pano-api-url` when the website URL is empty; must match
     * `licensing.issuer` on the license API.
     */
    fun getLicenseJwtIssuer(): String =
        applicationContext.getBean(com.panomc.platform.config.ConfigManager::class.java)
            .config.resolvedLicenseJwtIssuer()

    /**
     * Returns the SHA-256 hash of the loaded plugin JAR, or null if it cannot be
     * determined (e.g. running from an IDE without a packaged JAR). Plugins use this
     * to cross-check the `hash` claim of their license token, defending against
     * tampered hosts that try to feed back a stale or forged JWT.
     */
    fun getOwnJarSha256(): String? {
        val pm = applicationContext.getBean(PluginManager::class.java)
        val wrapper = pm.getPlugin(pluginId) as? PanoPluginWrapper ?: return null
        return wrapper.hash.takeIf { it.isNotBlank() }?.lowercase()
    }

    @Deprecated("Use onStart method.")
    override fun start() {
        runBlocking {
            withContext(Dispatchers.IO) {
                onStart()
            }
        }
    }

    @Deprecated("Use onStop method.")
    override fun stop() {
        runBlocking {
            withContext(Dispatchers.IO) {
                onStop()
            }
        }
    }

    internal fun load() {
        val pluginBeanContext by lazy {
            val pluginBeanContext = AnnotationConfigApplicationContext()

            pluginBeanContext.setAllowBeanDefinitionOverriding(true)

            pluginBeanContext.parent = PluginManager.pluginGlobalBeanContext
            pluginBeanContext.classLoader = this.javaClass.classLoader
            pluginBeanContext.scan(this.javaClass.`package`.name)

            pluginBeanContext.beanFactory.registerSingleton(this.logger.javaClass.name, this.logger)
            pluginBeanContext.beanFactory.registerSingleton(pluginEventManager.javaClass.name, pluginEventManager)
            pluginBeanContext.beanFactory.registerSingleton(this.javaClass.name, this)

            pluginBeanContext.refresh()

            pluginBeanContext
        }

        this.pluginBeanContext = pluginBeanContext

        pluginEventManager.initializePlugin(this, pluginBeanContext)
        pluginUiManager.initializePlugin(this)
        runBlocking {
            PluginManager.lifecycleListeners.forEach { it.onPluginLoad(this@PanoPlugin) }
        }
    }

    internal fun unload() {
        val copyOfRegisteredBeans = registeredBeans.toList()

        copyOfRegisteredBeans.forEach {
            try {
                unRegisterGlobal(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        pluginEventManager.unregisterPlugin(this)
        pluginUiManager.unRegisterPlugin(this)
        runBlocking {
            PluginManager.lifecycleListeners.forEach { it.onPluginUnload(this@PanoPlugin) }
        }
    }

    open suspend fun onCreate() {}
    open suspend fun onEnable() {}
    open suspend fun onStart() {}
    open suspend fun onStop() {}
    open suspend fun onDisable() {}
    open suspend fun onUninstall() {}
}