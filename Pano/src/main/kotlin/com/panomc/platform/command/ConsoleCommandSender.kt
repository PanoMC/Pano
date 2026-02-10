package com.panomc.platform.command

class ConsoleCommandSender : CommandSender {
    override fun sendMessage(message: String) {
        println(message)
    }

    override fun getName(): String {
        return "CONSOLE"
    }
}
