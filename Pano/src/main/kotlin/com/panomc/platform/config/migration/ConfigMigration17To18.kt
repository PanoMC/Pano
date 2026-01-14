package com.panomc.platform.config.migration

import com.panomc.platform.annotation.Migration
import com.panomc.platform.config.ConfigMigration
import io.vertx.core.json.JsonObject

@Migration
class ConfigMigration17To18 : ConfigMigration(17, 18, "Add SSL settings and separate HTTP/HTTPS ports.") {
    override fun migrate(config: JsonObject) {
        val server = config.getJsonObject("server") ?: JsonObject()
        
        if (!server.containsKey("http-port")) {
            val oldPort = server.getInteger("port") ?: 8088
            server.put("http-port", oldPort)
        }
        
        if (!server.containsKey("https-port")) {
            server.put("https-port", 8443)
        }
        
        if (!server.containsKey("ssl-mode")) {
            server.put("ssl-mode", "DISABLED")
        }

        server.remove("port")
        
        config.put("server", server)
    }
}
