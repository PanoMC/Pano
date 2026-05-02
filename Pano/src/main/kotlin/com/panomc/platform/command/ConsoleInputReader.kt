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
        /**
         * Live prompt waiting for input. Plain default-foreground (rendered white in most dark
         * IntelliJ Run themes) — colored escape codes here have shown to bleed into typed
         * characters on some PTY emulators, so we keep this one neutral.
         */
        const val JLINE_PROMPT = "\u001B[0m> "

        /**
         * Echoed command in “history” (already entered, scrolled away from the live prompt).
         * Bright black/grey to distinguish past entries from the active white `> ` prompt.
         */
        fun terminalStyleCommandLine(cmd: String): String = "\u001B[90m>\u001B[0m $cmd\n"

        @Volatile
        var reader: LineReader? = null
            private set

        /**
         * `true` when the JLine terminal supports cursor / line-edit ANSI escapes; `false` for the
         * dumb fallback used in IntelliJ Run / pipes / services.
         *
         * This drives how we interleave logs with the live prompt: real TTYs use [LineReader.printAbove]
         * (preserves the user's in-progress typed buffer); dumb TTYs use a manual erase+write+redraw
         * because printAbove's display-redraw produces extra blank rows in IDE consoles.
         */
        @Volatile
        var ansiCapableTerminal: Boolean = false
            private set

        /**
         * `true` only while [LineReader.readLine] is actively waiting for the user. Used by
         * [emitWithPromptRefresh] to decide whether it must repaint the prompt itself: if we
         * are between readLine calls, JLine's next readLine will paint a fresh prompt — drawing
         * one ourselves would result in the classic `> >` doubling on dumb consoles.
         */
        @Volatile
        private var inReadLine: Boolean = false

        /**
         * Emit a fully-formed log/echo line on a dumb terminal. We `\r` + `\u001B[K` to overwrite
         * any prompt JLine has painted on the current line, write the content, and only re-paint
         * the live prompt when JLine is mid-`readLine` (otherwise JLine itself will redraw).
         */
        fun emitWithPromptRefresh(text: String) {
            val r = reader ?: return
            val msg = if (text.endsWith("\n")) text else "$text\n"
            val w = r.terminal.writer()
            w.write("\r\u001B[K")
            w.write(msg)
            if (inReadLine) {
                w.write(JLINE_PROMPT)
            }
            w.flush()
        }

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
            ansiCapableTerminal = terminal.type != "dumb"

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

            while (running) {
                if (Main.isStopping()) {
                    Thread.sleep(50)
                    continue
                }
                inReadLine = true
                val line = try {
                    lineReader.readLine(JLINE_PROMPT)
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
                } finally {
                    inReadLine = false
                } ?: continue

                if (line.isNotBlank() && running) {
                    if (Main.IS_GUI) {
                        // Best-effort: try to repaint the just-typed terminal line in grey so it
                        // matches the GUI submitCommand history color. This works on real ANSI TTYs
                        // and on IntelliJ Run when "Emulate terminal in output console" is enabled.
                        // Pure-text consoles ignore the cursor escapes — there we leave the original
                        // (white live-prompt) line untouched rather than risk a duplicate row.
                        if (ansiCapableTerminal) {
                            val w = lineReader.terminal.writer()
                            w.write("\u001B[A\r\u001B[K\u001B[90m>\u001B[0m $line\n")
                            w.flush()
                        }

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
