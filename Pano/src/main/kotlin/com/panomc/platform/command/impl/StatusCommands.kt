package com.panomc.platform.command.impl

import com.panomc.platform.Main
import com.panomc.platform.UIManager
import com.panomc.platform.command.Command
import com.panomc.platform.command.CommandExecutor
import com.panomc.platform.command.CommandSender
import com.panomc.platform.config.ConfigManager
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.text.CharacterIterator
import java.text.StringCharacterIterator

@Component
class StatusCommands(
    private val configManager: ConfigManager,
    private val uiManager: UIManager
) : CommandExecutor {
    @Command(name = "status", description = "Shows system status and information")
    fun onStatusCommand(sender: CommandSender) {
        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024
        
        val uptime = (System.currentTimeMillis() - Main.START_TIME) / 1000
        val hours = uptime / 3600
        val minutes = (uptime % 3600) / 60
        val seconds = uptime % 60
        val uptimeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        sender.sendMessage("\u001B[36m--- System Status ---\u001B[0m")
        sender.sendMessage("\u001B[32mOS: \u001B[0m${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        sender.sendMessage("\u001B[32mJava Version: \u001B[0m${System.getProperty("java.version")}")
        sender.sendMessage("\u001B[32mUptime: \u001B[0m$uptimeStr")
        sender.sendMessage("\u001B[32mActive Theme: \u001B[0m${configManager.config.currentTheme}")
        
        val uiVersions = uiManager.getEmbeddedUIVersions()
        sender.sendMessage("\u001B[36m--- UI Versions ---\u001B[0m")
        uiVersions.forEach { (id, version) ->
            sender.sendMessage("\u001B[32m$id: \u001B[0m$version")
        }

        sender.sendMessage("\u001B[36m--- Memory Usage ---\u001B[0m")
        sender.sendMessage("\u001B[32mPano Platform: \u001B[0m${(runtime.totalMemory() - runtime.freeMemory()) / mb}MB / ${runtime.totalMemory() / mb}MB (Max: ${runtime.maxMemory() / mb}MB)")
        
        uiManager.getStartedUIs().forEach { ui ->
            sender.sendMessage("\u001B[32mUI Process (${ui.id}): \u001B[0mProcess ID: ${ui.process.pid()}")
        }
    }

    @Command(name = "gc", aliases = ["mem", "memory"], description = "Triggers garbage collection and shows memory info")
    fun onGcCommand(sender: CommandSender) {
        val runtime = Runtime.getRuntime()
        val mb = 1024 * 1024
        val before = (runtime.totalMemory() - runtime.freeMemory()) / mb
        sender.sendMessage("\u001B[33mRunning Garbage Collector...\u001B[0m")
        System.gc()
        val after = (runtime.totalMemory() - runtime.freeMemory()) / mb
        sender.sendMessage("\u001B[32mMemory: \u001B[0m${after}MB (Down from ${before}MB)")
    }

    @Command(name = "storage", description = "Shows disk storage usage information")
    fun onStorageCommand(sender: CommandSender) {
        sender.sendMessage("\u001B[36m--- Disk Storage Usage ---\u001B[0m")
        
        // Calculate JAR size
        var jarSize = 0L
        try {
            val location = Main::class.java.protectionDomain.codeSource?.location
            if (location != null) {
                val jarFile = File(location.toURI())
                if (jarFile.isFile) {
                    jarSize = jarFile.length()
                }
            }
        } catch (e: Exception) {
            // Ignore if running from IDE or cannot determine JAR location
        }
        sender.sendMessage("\u001B[32mPlatform (JAR): \u001B[0m${humanReadableByteCountBin(jarSize)}")

        val paths = mapOf(
            "Themes" to File("themes"),
            "Plugins" to File("plugins"),
            "Libraries" to File("libraries"),
            "Data" to File("data"),
            "Uploads" to File(configManager.config.fileUploadsFolder)
        )

        paths.forEach { (name, file) ->
            val size = if (file.exists()) getFolderSize(file) else 0L
            sender.sendMessage("\u001B[32m$name: \u001B[0m${humanReadableByteCountBin(size)}")
        }

        // Calculate total size of working directory
        val totalSize = getFolderSize(File("."))

        sender.sendMessage("\u001B[36m--------------------------\u001B[0m")
        sender.sendMessage("\u001B[32mTotal Pano Usage: \u001B[0m${humanReadableByteCountBin(totalSize)}")
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        
        return try {
            Files.walk(file.toPath())
                .filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
        } catch (e: Exception) {
            0L
        }
    }

    private fun humanReadableByteCountBin(bytes: Long): String {
        var b = bytes
        if (-1024 < b && b < 1024) {
            return "$b B"
        }
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        while (b <= -999950 || b >= 999950) {
            b /= 1024
            ci.next()
        }
        return String.format("%.1f %ciB", b / 1024.0, ci.current())
    }
}
