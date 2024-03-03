package com.panomc.platform.annotation

import org.springframework.stereotype.Component

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class Dao(
    val value: String = ""
)
