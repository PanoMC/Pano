package com.panomc.platform.command.impl

import com.panomc.platform.Main
import com.panomc.platform.command.Command
import com.panomc.platform.command.CommandExecutor
import com.panomc.platform.command.CommandManager
import com.panomc.platform.command.CommandSender
import com.panomc.platform.config.ConfigManager
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class CoreCommands(
    private val main: Main,
    private val commandManager: CommandManager,
    private val configManager: ConfigManager
) : CommandExecutor {
    private val logger = LoggerFactory.getLogger(CoreCommands::class.java)

    @Command(name = "stop", aliases = ["exit", "quit"], description = "Stops the platform")
    fun onStopCommand(sender: CommandSender) {
        runBlocking { main.shutdown() }
    }

    @Command(name = "help", description = "Shows help for commands or a specific command")
    fun onHelpCommand(sender: CommandSender, args: Array<String>) {
        if (args.isNotEmpty()) {
            val cmdName = args[0]
            val command = commandManager.resolveCommand(cmdName)
            
            if (command != null) {
                // If it's a known command, try executing "cmd help"
                // Assuming the command handles "help" argument as we implemented for themes/plugins
                commandManager.executeCommand(sender, "$cmdName help")
                return
            } else {
                 sender.sendMessage("\u001B[31mUnknown command: $cmdName\u001B[0m")
                 // Continue to show general help
            }
        }

        sender.sendMessage("\u001B[36m--- Available Commands ---\u001B[0m")
        commandManager.getCommands().values.distinctBy { it.name }.sortedBy { it.name }.forEach { cmd ->
            val aliasesString = if (cmd.aliases.isNotEmpty()) " (Aliases: ${cmd.aliases.joinToString(", ")})" else ""
            sender.sendMessage("\u001B[33m${cmd.name}\u001B[0m$aliasesString - ${cmd.description}")
            if (cmd.usage.isNotEmpty()) {
                sender.sendMessage("  Usage: ${cmd.usage}")
            }
        }
        sender.sendMessage("\u001B[36mTip: Type 'help <command>' for more info on a specific command.\u001B[0m")
    }

    @Command(name = "version", aliases = ["ver"], description = "Shows platform version")
    fun onVersionCommand(sender: CommandSender) {
        sender.sendMessage("\u001B[32mPano Platform Version: \u001B[0m${Main.VERSION}")
        sender.sendMessage("\u001B[32mEnvironment: \u001B[0m${Main.ENVIRONMENT}")
        sender.sendMessage("\u001B[32mStage: \u001B[0m${Main.STAGE}")
    }

    @Command(name = "reload", description = "Reloads the configuration")
    fun onReloadCommand(sender: CommandSender) {
        sender.sendMessage("\u001B[33mReloading configuration...\u001B[0m")
        runBlocking { configManager.init() }
        sender.sendMessage("\u001B[32mConfiguration reloaded!\u001B[0m")
    }

    @Command(name = "clear-temp", description = "Clears the temporary files directory")
    fun onClearTempCommand(sender: CommandSender) {
        sender.sendMessage("\u001B[33mClearing temporary files...\u001B[0m")
        runBlocking { main.clearTempFiles() }
        sender.sendMessage("\u001B[32mTemporary files cleared!\u001B[0m")
    }
}
