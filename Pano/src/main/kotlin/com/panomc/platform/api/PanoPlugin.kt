package com.panomc.platform.api

import com.panomc.platform.*
import com.panomc.platform.api.event.PluginEventListener
import io.vertx.core.Vertx
import org.pf4j.Plugin
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationConfigApplicationContext

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

    internal lateinit var applicationContext: AnnotationConfigApplicationContext

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

    @Deprecated("Use onStart method.")
    override fun start() {
    }

    @Deprecated("Use onStop method.")
    override fun stop() {
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
    }

    open suspend fun onCreate() {}
    open suspend fun onEnable() {}
    open suspend fun onDisable() {}
    open suspend fun onStart() {}
    open suspend fun onStop() {}
}