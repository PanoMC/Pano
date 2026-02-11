package com.panomc.platform.command.impl

import com.panomc.platform.PluginManager
import com.panomc.platform.command.Command
import com.panomc.platform.command.CommandExecutor
import com.panomc.platform.command.CommandSender
import org.pf4j.PluginState
import org.springframework.stereotype.Component

@Component
class PluginCommands(
    private val pluginManager: PluginManager
) : CommandExecutor {
    @Command(name = "plugins", aliases = ["pl"], description = "Plugin management commands")
    fun onPluginsCommand(sender: CommandSender, args: Array<String>) {
        if (args.isEmpty() || args[0].lowercase() == "help") {
            sender.sendMessage("\u001B[36m--- Plugin Commands ---\u001B[0m")
            sender.sendMessage("\u001B[33mpl list\u001B[0m - List all installed plugins")
            sender.sendMessage("\u001B[33mpl enable <plugin>\u001B[0m - Enable a plugin")
            sender.sendMessage("\u001B[33mpl disable <plugin>\u001B[0m - Disable a plugin")
            sender.sendMessage("\u001B[33mpl start <plugin>\u001B[0m - Start a plugin")
            sender.sendMessage("\u001B[33mpl stop <plugin>\u001B[0m - Stop a plugin")
            sender.sendMessage("\u001B[33mpl reload <plugin>\u001B[0m - Reload a plugin")
            sender.sendMessage("\u001B[33mpl uninstall <plugin>\u001B[0m - Unload a plugin from memory")
            return
        }

        if (args[0].lowercase() == "list") {
            val wrappers = pluginManager.getPluginWrappers()
            sender.sendMessage("\u001B[36m--- Plugins (${wrappers.size}) ---\u001B[0m")
            wrappers.forEach { wrapper ->
                val color = when (wrapper.pluginState) {
                    PluginState.STARTED -> "\u001B[32m"
                    PluginState.DISABLED -> "\u001B[31m"
                    else -> "\u001B[33m"
                }
                sender.sendMessage("$color${wrapper.pluginId}\u001B[0m v${wrapper.descriptor.version} - ${wrapper.pluginState}")
            }
            return
        }

        val sub = args[0].lowercase()
        if (args.size < 2) {
            sender.sendMessage("\u001B[31mUsage: plugins $sub <pluginId>\u001B[0m")
            return
        }
        val pluginId = args[1]
        val wrapper = pluginManager.getPlugin(pluginId)
        
        if (wrapper == null) {
            sender.sendMessage("\u001B[31mPlugin '$pluginId' not found.\u001B[0m")
            return
        }

        when (sub) {
            "enable" -> {
                if (pluginManager.enablePlugin(pluginId)) sender.sendMessage("\u001B[32mEnabled '$pluginId'.\u001B[0m")
                else sender.sendMessage("\u001B[31mFailed to enable '$pluginId'.\u001B[0m")
            }
            "disable" -> {
                if (pluginManager.disablePlugin(pluginId)) sender.sendMessage("\u001B[32mDisabled '$pluginId'.\u001B[0m")
                else sender.sendMessage("\u001B[31mFailed to disable '$pluginId'.\u001B[0m")
            }
            "start" -> {
                if (wrapper.pluginState == PluginState.STARTED) {
                    sender.sendMessage("\u001B[31mPlugin '$pluginId' is already started.\u001B[0m")
                } else {
                    pluginManager.startPlugin(pluginId)
                    sender.sendMessage("\u001B[32mStarted '$pluginId'.\u001B[0m")
                }
            }
            "stop" -> {
                if (wrapper.pluginState != PluginState.STARTED) {
                    sender.sendMessage("\u001B[31mPlugin '$pluginId' is not started.\u001B[0m")
                } else {
                    pluginManager.stopPlugin(pluginId)
                    sender.sendMessage("\u001B[32mStopped '$pluginId'.\u001B[0m")
                }
            }
            "reload" -> {
                sender.sendMessage("\u001B[33mReloading '$pluginId'...\u001B[0m")
                pluginManager.reloadPlugin(pluginId)
                sender.sendMessage("\u001B[32mReloaded '$pluginId'.\u001B[0m")
            }
            "uninstall" -> {
                pluginManager.unloadPlugin(pluginId)
                sender.sendMessage("\u001B[32mUnloaded '$pluginId'. To fully remove it, please delete the file from the plugins folder.\u001B[0m")
            }
            else -> sender.sendMessage("\u001B[31mUnknown plugin subcommand: $sub\u001B[0m")
        }
    }
}
