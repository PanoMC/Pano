package com.panomc.platform.command

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions

class CommandManager {
    private val logger = LoggerFactory.getLogger(CommandManager::class.java)
    private val commands = ConcurrentHashMap<String, RegisteredCommand>()
    private val aliases = ConcurrentHashMap<String, String>()

    data class RegisteredCommand(
        val name: String,
        val aliases: List<String>,
        val description: String,
        val usage: String,
        val executor: (CommandSender, Array<String>) -> Unit
    )

    fun registerCommand(
        name: String,
        description: String = "",
        usage: String = "",
        aliases: List<String> = emptyList(),
        executor: (CommandSender, Array<String>) -> Unit
    ) {
        val registeredCommand = RegisteredCommand(name, aliases, description, usage, executor)
        commands[name.lowercase()] = registeredCommand
        aliases.forEach { this.aliases[it.lowercase()] = name.lowercase() }
        logger.debug("Registered command: $name")
    }

    fun registerCommands(obj: Any) {
        obj::class.functions.forEach { function ->
            val annotation = function.findAnnotation<Command>() ?: return@forEach
            val name = annotation.name
            val description = annotation.description
            val usage = annotation.usage
            val aliases = annotation.aliases.toList()

            registerCommand(name, description, usage, aliases) { sender, args ->
                try {
                    val params = function.parameters
                    when {
                        params.size == 2 && params[1].type.classifier == CommandSender::class -> {
                            function.call(obj, sender)
                        }
                        params.size == 3 && params[1].type.classifier == CommandSender::class && params[2].type.classifier == Array<String>::class -> {
                            function.call(obj, sender, args)
                        }
                        else -> {
                            logger.error("Invalid command handler signature for ${function.name}. Expected (CommandSender) or (CommandSender, Array<String>)")
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Error executing command $name", e)
                    sender.sendMessage("\u001B[31mAn error occurred while executing the command.\u001B[0m")
                }
            }
        }
    }

    fun unregisterCommands(obj: Any) {
        val namesToRemove = mutableListOf<String>()
        obj::class.functions.forEach { function ->
            val annotation = function.findAnnotation<Command>() ?: return@forEach
            namesToRemove.add(annotation.name.lowercase())
        }

        namesToRemove.forEach { name ->
            val cmd = commands.remove(name)
            cmd?.aliases?.forEach { aliases.remove(it.lowercase()) }
        }
    }

    fun executeCommand(sender: CommandSender, input: String) {
        val parts = input.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return

        val label = parts[0].lowercase()
        val args = parts.drop(1).toTypedArray()

        val commandName = aliases[label] ?: label
        val command = commands[commandName]

        if (command != null) {
            command.executor(sender, args)
        } else {
            sender.sendMessage("\u001B[33mUnknown command: $label. Type 'help' for a list of commands.\u001B[0m")
        }
    }

    fun getCommands(): Map<String, RegisteredCommand> = commands
}
