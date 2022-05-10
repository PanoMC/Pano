package com.panomc.platform.config

abstract class ConfigMigration {
    abstract val FROM_VERSION: Int
    abstract val VERSION: Int
    abstract val VERSION_INFO: String

    fun isMigratable(version: Int) = version == FROM_VERSION

    abstract fun migrate(configManager: ConfigManager)
}