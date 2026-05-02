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
            if (!startWithJLine()) {
                Main.logger.warn("JLine console is not available (likely not a real terminal). Falling back to basic console reader.")
                startWithBasicReader()
            }
        }
    }

    /**
     * Tries to start console input reading with JLine.
     * Returns true if the loop ran and exited normally, false if JLine is not usable
     * (e.g. on Windows without a real console handle).
     */
    private fun startWithJLine(): Boolean {
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
                } catch (e: java.io.IOError) {
                    // JLine may throw IOError (not IOException) when stdin / PTY breaks:
                    // Docker without TTY, IDE run, systemd, closed pipe, etc.
                    try {
                        terminal.close()
                    } catch (_: Exception) {
                    }
                    return false
                } catch (e: java.io.IOException) {
                    // On Windows, JLine can throw "The handle is invalid" when stdin
                    // is not a real console (e.g. IDE, service, redirected input).
                    // Fall back to basic reader.
                    try { terminal.close() } catch (_: Exception) {}
                    return false
                } ?: continue

                if (line.isNotBlank() && running) {
                    if (Main.IS_GUI) {
                        // JLine already echoes on the terminal; show in GUI too
                        com.panomc.platform.util.UiConsole.appendToConsole("\u001B[90m>\u001B[0m $line\n")
                    } else {
                        Main.logger.info("\u001B[90m>\u001B[0m $line")
                    }
                    
                    // Add to GUI history
                    com.panomc.platform.util.UiConsole.addToHistory(line)
                    
                    commandManager.executeCommand(consoleSender, line)
                }
            }
        } catch (e: java.io.IOError) {
            return false
        } catch (e: java.io.IOException) {
            // Terminal creation itself failed — not usable
            return false
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    /**
     * Fallback console reader using plain BufferedReader on System.in.
     * No line editing or history, but works on any platform/environment.
     */
    private fun startWithBasicReader() {
        try {
            val br = java.io.BufferedReader(java.io.InputStreamReader(System.`in`))

            while (running) {
                if (Main.isStopping()) {
                    Thread.sleep(50)
                    continue
                }

                // Check if input is available to avoid blocking forever when stdin is gone
                if (!br.ready()) {
                    Thread.sleep(100)
                    continue
                }

                val line = br.readLine() ?: break // EOF

                if (line.isNotBlank() && running) {
                    if (Main.IS_GUI) {
                        com.panomc.platform.util.UiConsole.appendToConsole("\u001B[90m>\u001B[0m $line\n")
                    } else {
                        Main.logger.info("\u001B[90m>\u001B[0m $line")
                    }

                    // Add to GUI history
                    com.panomc.platform.util.UiConsole.addToHistory(line)

                    commandManager.executeCommand(consoleSender, line)
                }
            }
        } catch (e: java.io.IOError) {
            Main.logger.warn("Console input is not available (I/O error). Console commands are disabled.")
        } catch (e: java.io.IOException) {
            // stdin is not available at all (e.g. running as a Windows service)
            Main.logger.warn("Console input is not available. Console commands are disabled.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
