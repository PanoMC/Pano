package com.panomc.platform.api.config

import io.vertx.core.json.JsonObject

abstract class PluginConfigMigration(val from: Int, val to: Int, val versionInfo: String) {
    fun isMigratable(version: Int) = version == from

    abstract fun migrate(config: JsonObject)
}