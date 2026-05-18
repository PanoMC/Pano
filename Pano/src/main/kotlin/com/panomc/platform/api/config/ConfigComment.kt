package com.panomc.platform.api.config

/**
 * Renders as one or more `#` comment lines immediately above the field in the saved config file.
 * Each vararg entry becomes its own line. Usable both on Pano's own config and on plugin configs
 * that extend [PluginConfig].
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigComment(vararg val lines: String)
