package com.panomc.platform.config

/**
 * Renders as one or more `#` comment lines immediately above the field in the saved config file.
 * Each vararg entry becomes its own line. Read by [HoconWriter] via reflection.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigComment(vararg val lines: String)
