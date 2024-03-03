package com.panomc.platform.api.annotation

import org.springframework.stereotype.Component

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class EventListener(
    val value: String = ""
)
