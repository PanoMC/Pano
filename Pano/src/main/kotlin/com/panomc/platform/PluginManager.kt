package com.panomc.platform

import com.panomc.platform.SpringConfig.Companion.pluginEventManager
import com.panomc.platform.SpringConfig.Companion.pluginUiManager
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginLifecycleListener
import com.panomc.platform.license.LicenseManager
import com.panomc.platform.license.findLicenseRequiredInCauseChain
import kotlinx.coroutines.runBlocking
import org.pf4j.*
import org.springframework.beans.BeansException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

@Component
class PluginManager(importPaths: List<Path> = listOf(Paths.get(System.getProperty("pf4j.pluginsDir", "./plugins")))) :
    DefaultPluginManager(importPaths) {
    companion object {
        internal val pluginGlobalBeanContext by lazy {
            val pluginGlobalBeanContext = AnnotationConfigApplicationContext()

            pluginGlobalBeanContext.setAllowBeanDefinitionOverriding(true)

            pluginGlobalBeanContext.beanFactory.registerSingleton(SpringConfig.vertx.javaClass.name, SpringConfig.vertx)

            pluginGlobalBeanContext.refresh()

            pluginGlobalBeanContext
        }

        internal val lifecycleListeners = mutableSetOf<PluginLifecycleListener>()
    }

    private val log = LoggerFactory.getLogger(PluginManager::class.java)

    fun addLifecycleListener(listener: PluginLifecycleListener) {
        lifecycleListeners.add(listener)
    }

    override fun createPluginRepository(): PluginRepository {
        return CompoundPluginRepository()
            .add(DevelopmentPluginRepository(getPluginsRoots())) { this.isDevelopment }
            .add(JarPluginRepository(getPluginsRoots())) { this.isNotDevelopment }
    }

    override fun createPluginDescriptorFinder(): CompoundPluginDescriptorFinder {
        return CompoundPluginDescriptorFinder()
            .add(PanoManifestPluginDescriptorFinder())
    }

    override fun createPluginFactory(): PluginFactory {
        return PluginFactory(pluginEventManager, pluginUiManager)
    }

    override fun createPluginLoader(): PluginLoader {
        return CompoundPluginLoader()
            .add(PanoPluginLoader(this)) { this.isNotDevelopment }
    }

    fun getActivePanoPlugins(): List<PanoPlugin> = getPlugins(PluginState.STARTED).mapNotNull { plugin ->
        runCatching {
            val pluginWrapper = plugin as PanoPluginWrapper
            pluginWrapper.plugin as PanoPlugin
        }.getOrNull()
    }

    fun getPluginWrappers() = plugins.values.map { it as PanoPluginWrapper }

    override fun createPluginWrapper(
        pluginDescriptor: PluginDescriptor,
        pluginPath: Path,
        pluginClassLoader: ClassLoader
    ): PluginWrapper {
        val pluginWrapper = PanoPluginWrapper(this, pluginDescriptor, pluginPath, pluginClassLoader)

        pluginWrapper.setPluginFactory(getPluginFactory())

        return pluginWrapper
    }


    override fun enablePlugin(pluginId: String): Boolean {
        val wrapper = getPlugin(pluginId)
        val stateBefore = wrapper.pluginState

        /**
         * PF4J returns `true` from [AbstractPluginManager.enablePlugin] whenever the plugin is
         * **not** [PluginState.DISABLED] — including [PluginState.FAILED] and [PluginState.STOPPED]
         * — without unloading or resetting the extension instance.
         *
         * Our host hooks ([PanoPlugin.load], [PanoPlugin.onEnable]) must never run again without a
         * prior [disablePlugin] unload cycle; otherwise Spring/plugin contexts corrupt and retries
         * can stall or crash the Vert.x worker (operators see the panel “splash” / disconnect).
         */
        if (stateBefore == PluginState.FAILED || stateBefore == PluginState.STOPPED) {
            disablePlugin(pluginId)
        }

        val result = super.enablePlugin(pluginId)

        val plugin = getPlugin(pluginId)?.plugin as PanoPlugin?

        if (result) {
            plugin?.let {
                try {
                    runBlocking {
                        it.load()

                        lifecycleListeners.forEach { listener ->
                            listener.onPluginEnable(it)
                        }

                        it.onEnable()
                    }
                } catch (e: Exception) {
                    log.error(
                        "Plugin '{}' host enable hooks failed during load/onEnable: {}",
                        pluginId,
                        e.message,
                        e,
                    )
                }
            }
        } else {
            log.warn("Plugin '{}' super.enablePlugin returned false (pf4j did not enable)", pluginId)
        }

        return result
    }

    override fun startPlugin(pluginId: String?): PluginState? {
        val state = super.startPlugin(pluginId)
        if (pluginId != null) {
            val wrapper = getPlugin(pluginId)
            val licenseException = wrapper?.failedException.findLicenseRequiredInCauseChain()
            if (licenseException != null) {
                wrapper?.pluginState = PluginState.FAILED
                log.warn(
                    "Plugin '{}' startup blocked by license: {}",
                    pluginId,
                    licenseException.message,
                )
                return PluginState.FAILED
            }
        }

        if (pluginId != null && state == PluginState.FAILED) {
            val fe = getPlugin(pluginId)?.failedException
            if (fe != null) {
                val licenseException = fe.findLicenseRequiredInCauseChain()
                if (licenseException != null) {
                    log.warn(
                        "Plugin '{}' startup blocked by license: {}",
                        pluginId,
                        licenseException.message,
                    )
                } else {
                    log.warn(
                        "Plugin '{}' PF4J start failed — {}",
                        pluginId,
                        fe.message ?: fe.javaClass.simpleName,
                        fe,
                    )
                }
            } else {
                log.warn(
                    "Plugin '{}' PF4J start failed (FAILED state, no failedException on wrapper)",
                    pluginId,
                )
            }
            captureLicenseFailureIfAny(pluginId)
        }
        return state
    }

    /**
     * Look at the failed plugin's exception chain. If any link is a [LicenseRequiredException]
     * (raised by the plugin's own onStart while calling LicenseManager), record it on the
     * host so the panel UI can surface it.
     *
     * Best-effort: we deliberately swallow any exception here because failure-capture must
     * never itself break plugin loading.
     */
    private fun captureLicenseFailureIfAny(pluginId: String) {
        try {
            val wrapper = getPlugin(pluginId) ?: return
            val ex = wrapper.failedException ?: return
            val licEx = ex.findLicenseRequiredInCauseChain() ?: return
            val licenseManager = try {
                Main.applicationContext.getBean(LicenseManager::class.java)
            } catch (_: BeansException) {
                return
            } catch (_: Throwable) {
                return
            }
            licenseManager.recordFailure(pluginId, licEx)
        } catch (_: Throwable) {
            // never fail plugin loading because of bookkeeping
        }
    }

    override fun stopPlugin(pluginId: String?): PluginState? {
        return super.stopPlugin(pluginId)
    }

    fun reloadPlugin(pluginId: String): PluginState? {
        stopPlugin(pluginId)
        return startPlugin(pluginId)
    }

    override fun disablePlugin(pluginId: String): Boolean {
        val plugin = getPlugin(pluginId).plugin as PanoPlugin

        runBlocking {
            lifecycleListeners.forEach { listener ->
                listener.onPluginDisable(plugin)
            }

            plugin.onDisable()
            plugin.unload()
        }

        return super.disablePlugin(pluginId)
    }
}