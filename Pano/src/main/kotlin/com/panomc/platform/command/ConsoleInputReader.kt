package com.panomc.platform.command

import com.panomc.platform.Main
import com.panomc.platform.config.ConfigManager
import kotlinx.coroutines.runBlocking
import org.jline.keymap.KeyMap
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder
import kotlin.concurrent.thread

class ConsoleInputReader(
    private val main: Main,
    private val commandManager: CommandManager,
    private val configManager: ConfigManager
) {
    private val consoleSender = ConsoleCommandSender(Main.logger)

    companion object {
        @Volatile
        var reader: LineReader? = null
            private set

        @Volatile
        var running = true
            private set

        /**
         * Stops the console input reader and optionally closes the terminal.
         */
        fun stop(closeTerminal: Boolean = true) {
            running = false
            if (closeTerminal) {
                reader?.terminal?.close()
            }
        }
    }

    fun start() {
        // Double-check silencing JLine warnings
        System.setProperty("org.jline.utils.Log.level", "ERROR")
        java.util.logging.Logger.getLogger("org.jline").level = java.util.logging.Level.OFF
        
        thread(name = "ConsoleInputReader", isDaemon = true) {
            try {
                // Try to create a system terminal; if it fails, it will fallback to a dumb terminal.
                // We set dumb(true) to indicate that falling back to a dumb terminal is expected/allowed,
                // which can sometimes suppress the warning in some JLine versions.
                val terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .signalHandler(org.jline.terminal.Terminal.SignalHandler.SIG_IGN)
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

                // Bind Ctrl+C to a custom widget that triggers shutdown *inside* the readLine scope
                // This prevents JLine from throwing UserInterruptException and resetting the terminal
                val stopWidget = Widget {
                    runBlocking {
                        main.shutdown()
                    }
                    true
                }
                lineReader.widgets["graceful-stop"] = stopWidget
                lineReader.keyMaps[LineReader.MAIN]!!.bind(
                    Reference("graceful-stop"),
                    KeyMap.ctrl('c')
                )

                // Spigot-style bright yellow prompt
                val prompt = "\u001B[93m>\u001B[0m "

                while (running) {
                    if (Main.isStopping()) {
                        Thread.sleep(50)
                        continue
                    }
                    val line = try {
                        lineReader.readLine(prompt)
                    } catch (e: UserInterruptException) {
                        // Handle Ctrl+C: stop the platform gracefully
                        runBlocking {
                            main.shutdown()
                        }
                        null
                    } catch (e: EndOfFileException) {
                        break
                    } ?: continue

                    if (line.isNotBlank() && running) {
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
