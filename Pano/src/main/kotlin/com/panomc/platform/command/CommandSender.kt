package com.panomc.platform.command

interface CommandSender {
    fun sendMessage(message: String)
    fun getName(): String
}
