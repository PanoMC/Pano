package com.panomc.platform.config

abstract class ConfigMigration(
    val from: Int,
    val to: Int,
    val versionInfo: String
) {

    fun isMigratable(version: Int) = version == from

    abstract fun migrate(configManager: ConfigManager)
}