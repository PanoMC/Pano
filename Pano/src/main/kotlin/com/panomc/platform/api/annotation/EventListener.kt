package com.panomc.platform.api.annotation

import com.panomc.platform.api.condition.CheckClassesExists
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
@Conditional(CheckClassesExists::class)
annotation class EventListener(
    val value: String = ""
)
