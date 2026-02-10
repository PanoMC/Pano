package com.panomc.platform.command

import com.panomc.platform.config.ConfigManager
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import kotlin.concurrent.thread

class ConsoleInputReader(
    private val commandManager: CommandManager,
    private val configManager: ConfigManager
) {
    private val consoleSender = ConsoleCommandSender()

    companion object {
        @Volatile
        var reader: LineReader? = null
            private set

        @Volatile
        private var running = true

    }

    fun start() {
        System.setProperty("org.jline.utils.Log.level", "ERROR")
        
        thread(name = "ConsoleInputReader", isDaemon = true) {
            try {
                val terminal = TerminalBuilder.builder()
                    .system(true)
                    .signalHandler(org.jline.terminal.Terminal.SignalHandler.SIG_IGN) // Signal hatalarını da susturur
                    .build()

                val builder = LineReaderBuilder.builder()
                    .terminal(terminal)

                if (configManager.config.consoleHistoryLimit > 0) {
                    builder.variable(LineReader.HISTORY_FILE, java.io.File(".console_history").absolutePath)
                    builder.variable(LineReader.HISTORY_SIZE, configManager.config.consoleHistoryLimit)
                    builder.variable(LineReader.HISTORY_FILE_SIZE, configManager.config.consoleHistoryLimit)
                }

                val lineReader = builder.build()

                reader = lineReader

                // Spigot-style bright yellow prompt
                val prompt = "\u001B[93m>\u001B[0m "

                while (running) {
                    val line = try {
                        lineReader.readLine(prompt)
                    } catch (e: UserInterruptException) {
                        // Handle Ctrl+C if needed, otherwise continue
                        null
                    } catch (e: EndOfFileException) {
                        break
                    } ?: continue

                    if (line.isNotBlank() && running) {
                        // Log the command so it appears in the GUI console as well
                        println("\u001B[90m>\u001B[0m $line")
                        
                        // Add to GUI history
                        com.panomc.platform.util.UiConsole.addToHistory(line)
                        
                        commandManager.executeCommand(consoleSender, line)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
