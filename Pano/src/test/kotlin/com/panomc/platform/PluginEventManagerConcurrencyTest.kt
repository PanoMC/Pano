package com.panomc.platform

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.EventListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PluginEventManagerConcurrencyTest {
    private class TestPlugin : PanoPlugin()

    private fun context() = AnnotationConfigApplicationContext().also { it.refresh() }

    private class ListenerA : EventListener
    private class ListenerB : EventListener

    @Test
    fun `10000 lookups while another thread registers and unregisters throw nothing`() {
        val manager = PluginEventManager()
        val stable = TestPlugin()
        val churned = (1..20).map { TestPlugin() }
        val failure = AtomicReference<Throwable?>()
        val stop = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        manager.initializePlugin(stable, context())
        manager.register(stable, ListenerA())

        pool.submit {
            try {
                var i = 0
                while (stop.count > 0) {
                    val plugin = churned[i++ % churned.size]
                    manager.initializePlugin(plugin, context())
                    manager.register(plugin, ListenerA())
                    manager.register(plugin, ListenerB())
                    manager.register(stable, ListenerB())
                    manager.unregisterPlugin(plugin)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        val reader = pool.submit {
            try {
                repeat(10_000) {
                    PluginEventManager.getEventListeners().values.forEach { it.size }
                    PluginEventManager.getEventListeners<com.panomc.platform.api.event.PluginEventListener>()
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        reader.get(60, TimeUnit.SECONDS)
        stop.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        assertNull(failure.get(), "concurrent access failed: ${failure.get()}")
        manager.unregisterPlugin(stable)
        churned.forEach { manager.unregisterPlugin(it) }
    }

    @Test
    fun `register still de-duplicates by listener class`() {
        val manager = PluginEventManager()
        val plugin = TestPlugin()

        manager.initializePlugin(plugin, context())
        manager.register(plugin, ListenerA())
        manager.register(plugin, ListenerA())
        manager.register(plugin, ListenerB())

        assertEquals(2, PluginEventManager.getEventListeners().getValue(plugin).size)

        manager.unregisterPlugin(plugin)
    }
}
