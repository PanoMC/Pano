package com.panomc.platform.plugin.leakfixture

import com.panomc.platform.api.PanoPlugin
import com.panomc.platform.api.event.PluginEventListener
import com.panomc.platform.db.DBEntity
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

/**
 * Fixture plugin of PluginClassLoaderLeakTest. Its class files are copied into the fixture jars, so each plugin's class
 * loader (plugin-first) defines its own copy of these classes, as with a real plugin jar.
 */
class LeakFixturePlugin : PanoPlugin() {
    private val api = LeakApi(this)

    /** The handler the test mounts for this plugin's endpoints, as RouterProvider mounts an @Endpoint's handler. */
    fun routeHandler(): Handler<RoutingContext> = Handler { context -> api.handle(context) }

    override suspend fun onStart() {
        // What the payment add-ons do in onStart.
        register(LeakListener())

        // A plugin class through Vert.x's Jackson mapper both ways, as plugins do with JsonObject.mapFrom / mapTo.
        JsonObject.mapFrom(LeakPayload(pluginId, 1)).mapTo(LeakPayload::class.java)

        // A plugin database model through core's shared Gson, as every plugin DAO does.
        LeakModel(pluginId, 1).toJson()
    }
}

class LeakApi(private val plugin: PanoPlugin) {
    fun handle(context: RoutingContext) {
        context.response().end(plugin.pluginId)
    }
}

class LeakListener : PluginEventListener

data class LeakPayload(val name: String = "", val count: Int = 0)

data class LeakModel(val name: String = "", val count: Int = 0) : DBEntity()
