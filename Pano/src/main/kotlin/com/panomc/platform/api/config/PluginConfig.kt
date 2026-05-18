package com.panomc.platform.api.config

/**
 * Base class for plugin configurations.
 *
 * Plugins can annotate their own fields with [ConfigComment] for inline documentation and
 * [ConfigSection] to group related keys with a visual banner. Annotations are applied when
 * Pano writes the plugin's `config.conf` (initial creation, after a migration, or any explicit
 * save). Plugin authors are free to extend this class with their own fields:
 *
 * ```kotlin
 * class MyPluginConfig(
 *     @ConfigComment("Public URL the plugin reports to clients.")
 *     val publicUrl: String = "",
 *
 *     @ConfigSection("API Credentials")
 *     @ConfigComment("Issued by the dashboard; rotate every 90 days.")
 *     val apiKey: String = ""
 * ) : PluginConfig()
 * ```
 */
open class PluginConfig(
    @ConfigComment("Plugin config version used for migrations (DO NOT manually change).")
    val version: Int = 1
)
