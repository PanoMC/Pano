package com.panomc.platform.annotation

import org.springframework.stereotype.Component

/**
 * Marks a [com.panomc.platform.frontend.FallbackPage] as a bean, like [Endpoint] does for a route. Core
 * pages are found in the platform's own context, a plugin's in the plugin's bean context (doc 05 section 10.3).
 */
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class FallbackPageDefinition(
    val value: String = ""
)
