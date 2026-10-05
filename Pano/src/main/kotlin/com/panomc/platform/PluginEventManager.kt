package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.EventListener
import com.panomc.platform.api.event.PanoEventListener
import com.panomc.platform.api.event.PluginEventListener
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PluginEventManager {
    companion object {
        // Read on event-loop threads while plugins start/stop on worker threads: must not throw CME.
        private val eventListeners = ConcurrentHashMap<PanoPlugin, CopyOnWriteArrayList<EventListener>>()

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
                .let { CopyOnWriteArrayList(it) }
        }
    }

    internal fun unregisterPlugin(plugin: PanoPlugin) {
        eventListeners.remove(plugin)
    }

    fun register(plugin: PanoPlugin, eventListener: EventListener) {
        val listeners = eventListeners[plugin]!!

        synchronized(listeners) {
            if (listeners.none { it::class == eventListener::class }) {
                listeners.add(eventListener)
            }
        }
    }

    fun unRegister(plugin: PanoPlugin, eventListener: EventListener) {
        eventListeners[plugin]?.remove(eventListener)
    }
}