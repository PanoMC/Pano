package com.panomc.platform.command

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ConsoleCommandSender(private val logger: Logger) : CommandSender {

    override fun sendMessage(message: String) {
        logger.info(message)
    }

    override fun getName(): String {
        return "CONSOLE"
    }
}
