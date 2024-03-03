package com.panomc.platform

import com.panomc.platform.api.EventListener
import com.panomc.platform.api.PanoEventListener
import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.PluginEventListener
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class PluginEventManager {
    companion object {
        private val eventListeners = mutableMapOf<PanoPlugin, MutableList<EventListener>>()

        fun getEventListeners() = eventListeners.toMap()

        internal inline fun <reified T : PanoEventListener> getPanoEventListeners() =
            eventListeners.flatMap { it.value }.filterIsInstance<T>()


        inline fun <reified T : PluginEventListener> getEventListeners() =
            getEventListeners().flatMap { it.value }.filter { it !is PanoEventListener }.filterIsInstance<T>()
    }

    internal fun initializePlugin(plugin: PanoPlugin, pluginBeanContext: AnnotationConfigApplicationContext) {
        if (eventListeners[plugin] == null) {
            eventListeners[plugin] = pluginBeanContext
                .getBeansWithAnnotation(com.panomc.platform.api.annotation.EventListener::class.java)
                .map { it.value as EventListener }
                .toMutableList()
        }
    }

    internal fun unregisterPlugin(plugin: PanoPlugin) {
        eventListeners.remove(plugin)
    }

    fun register(plugin: PanoPlugin, eventListener: EventListener) {
        if (eventListeners[plugin]!!.none { it::class == eventListener::class }) {
            eventListeners[plugin]!!.add(eventListener)
        }
    }

    fun unRegister(plugin: PanoPlugin, eventListener: EventListener) {
        eventListeners[plugin]?.remove(eventListener)
    }
}