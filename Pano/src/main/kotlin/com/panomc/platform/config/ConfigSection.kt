package com.panomc.platform.config

/**
 * Marks a field as the start of a visual section. [HoconWriter] emits a blank line and a banner
 * with [title] immediately before the field. Useful for grouping related top-level keys.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigSection(val title: String)
