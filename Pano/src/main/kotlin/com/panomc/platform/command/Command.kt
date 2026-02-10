package com.panomc.platform.command

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(
    val name: String,
    val aliases: Array<String> = [],
    val description: String = "",
    val usage: String = ""
)
