package com.panomc.platform.plugin.leakfixture

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginEventListener
import io.vertx.core.json.JsonObject

/**
 * Fixture plugin of PluginClassLoaderLeakTest. Its class files are copied into the fixture jars, so each plugin's class
 * loader (plugin-first) defines its own copy of these classes, as with a real plugin jar.
 */
class LeakFixturePlugin : PanoPlugin() {
    override suspend fun onStart() {
        // What the payment add-ons do in onStart.
        register(LeakListener())

        // A plugin class through Vert.x's Jackson mapper both ways, as plugins do with JsonObject.mapFrom / mapTo.
        JsonObject.mapFrom(LeakPayload(pluginId, 1)).mapTo(LeakPayload::class.java)
    }
}

class LeakListener : PluginEventListener

data class LeakPayload(val name: String = "", val count: Int = 0)
