package com.panomc.platform.api.config

/**
 * Marks a field as the start of a visual section. Emits a blank line and a banner with [title]
 * immediately before the field in the saved config file. Useful for grouping related top-level
 * keys. Usable both on Pano's own config and on plugin configs that extend [PluginConfig].
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigSection(val title: String)
