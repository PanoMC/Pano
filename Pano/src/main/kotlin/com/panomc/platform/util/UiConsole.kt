package com.panomc.platform.util

import com.panomc.platform.command.ConsoleInputReader
import com.panomc.platform.util.UiConsole.markReady
import kotlinx.coroutines.runBlocking
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.UIResource
import javax.swing.plaf.basic.BasicButtonUI
import javax.swing.plaf.basic.BasicTextAreaUI
import javax.swing.plaf.basic.BasicTextFieldUI
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * Dark-themed Swing console window with:
 *  - ANSI colors (basic 16, bold, underline)
 *  - Monospace font, anti-aliased
 *  - Command input with placeholder/prompt and lock states
 *  - Ctrl+C stops the app (window stays), prints guidance
 *  - System.out/err redirected (optional tee)
 *  - Uses system Look&Feel for the scroll bar and tooltips; the command row (field + Send button)
 *    paints itself from the palette below and never depends on the system theme
 *  - Loads app icon from classpath resource: /logo.png
 */
object UiConsole {

    private var frame: JFrame? = null
    private var textPane: JTextPane? = null
    private var doc: StyledDocument? = null
    private var inputField: JTextField? = null
    private var sendBtn: JButton? = null

    private val commandHistory = mutableListOf<String>()
    private var historyLimit = 50
    private var historyIndex = -1
    private var currentDraft = ""

    private var defaultAttr: SimpleAttributeSet = SimpleAttributeSet()
    private var currentAttr: SimpleAttributeSet = SimpleAttributeSet()

    private var interruptHandler: (suspend () -> Unit)? = null
    private var commandHandler: ((String) -> Unit)? = null

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null

    @Volatile
    private var stopped: Boolean = false
    @Volatile
    private var inputEnabled: Boolean = false
    @Volatile
    private var inputLockReason: String = "Starting…"

    private val bg = Color(0x1e1e1e)
    private val fg = Color(0xd4d4d4)
    private val fieldBg = Color(0x252526)
    private val borderTop = Color(0x2a2a2a)
    private val placeholderColor = Color(0x8a8a8a)
    private val caretDisabled = Color(0x5a5a5a)
    private val fieldDisabledFg = Color(0x6e6e6e)
    private val selectionBg = Color(0x264f78)
    private val selectionFg = Color(0xffffff)
    private val buttonBg = Color(0x2d2d30)
    private val buttonHoverBg = Color(0x3a3d41)
    private val buttonPressedBg = Color(0x45494e)
    private val buttonDisabledFg = Color(0x808080)

    private val placeholderText = "Enter command here..."

    // ---------- Public API ----------

    /** Append raw ANSI text directly to the console output pane. */
    fun appendToConsole(text: String) {
        parseAnsiAndAppend(text)
    }
    
    fun setHistoryLimit(limit: Int) {
        historyLimit = limit
        if (historyLimit <= 0) {
            commandHistory.clear()
        } else {
            while (commandHistory.size > historyLimit) {
                commandHistory.removeAt(0)
            }
        }
    }

    fun addToHistory(cmd: String) {
        if (historyLimit <= 0) return
        
        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
            commandHistory.add(cmd)
            while (commandHistory.size > historyLimit) {
                commandHistory.removeAt(0)
            }
        }
    }

    fun isGuiAvailable(): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("linux") || os.contains("bsd")) {
            val hasX = System.getenv("DISPLAY")?.isNotEmpty() == true
            val hasWayland = System.getenv("WAYLAND_DISPLAY")?.isNotEmpty() == true
            if (!hasX && !hasWayland) return false
        }
        return true
    }

    /** Set a Ctrl+C (interrupt) handler to gracefully stop your app. */
    fun setInterruptHandler(handler: (suspend () -> Unit)?) {
        interruptHandler = handler
    }

    /** Set a handler that receives commands typed in the input box. */
    fun setCommandHandler(handler: ((String) -> Unit)?) {
        commandHandler = handler
    }

    /**
     * Show the console window and redirect System.out/err to it.
     * Input starts LOCKED until you call [markReady].
     */
    fun showConsoleWindow(title: String = "Pano Console", teeToOriginal: Boolean = true, historyLimit: Int = 50) {
        this.historyLimit = historyLimit
        if (frame != null) {
            frame!!.toFront(); return
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
        }

        System.setProperty("awt.useSystemAAFontSettings", "on")
        System.setProperty("swing.aatext", "true")

        UIManager.put("TextPane.background", bg)
        UIManager.put("TextPane.foreground", fg)
        UIManager.put("ScrollBar.thumb", Color(0x3a3d41))
        UIManager.put("ScrollBar.track", Color(0x252526))

        val f = JFrame(title)
        applyAppIcon(f, "/logo.png")

        // Output area
        val pane = JTextPane().apply {
            isEditable = false
            background = bg
            foreground = fg
            margin = Insets(6, 8, 6, 8)
        }
        val scroll = JScrollPane(pane).apply { border = BorderFactory.createEmptyBorder() }

        // Input area
        val input = ConsoleTextField().apply {
            // 1) Don't take focus on startup
            isFocusable = false
            isRequestFocusEnabled = false
        }

        // Focus behavior
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (inputEnabled && !stopped) {
                    input.caretColor = fg
                }
            }
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                input.caretColor = if (inputEnabled && !stopped) fg else caretDisabled
            }
        })

        // History behavior
        input.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (commandHistory.isEmpty()) return
                
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_UP -> {
                        if (historyIndex == -1) {
                            currentDraft = input.text
                        }
                        if (historyIndex < commandHistory.size - 1) {
                            historyIndex++
                            input.text = commandHistory[commandHistory.size - 1 - historyIndex]
                        }
                        e.consume()
                    }
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        if (historyIndex > 0) {
                            historyIndex--
                            input.text = commandHistory[commandHistory.size - 1 - historyIndex]
                        } else if (historyIndex == 0) {
                            historyIndex = -1
                            input.text = currentDraft
                        }
                        e.consume()
                    }
                }
            }
        })

        // 2) "Send" button always dark (ConsoleButton paints itself, see below)
        val send = ConsoleButton("Send").apply {
            addActionListener { submitCommand() }
        }

        input.addActionListener { submitCommand() }

        val mono = pickMonospaceFont()
        pane.font = mono.deriveFont(14f)

        // Match JLine ANSI 93 `>` so the prompt is obvious next to the field (esp. vs log text).
        val promptYellow = Color(0xF5, 0xD7, 0x42)
        val promptLabel = JLabel(">").apply {
            foreground = promptYellow
            background = fieldBg
            isOpaque = true
            font = mono.deriveFont(Font.BOLD, 14f)
            horizontalAlignment = SwingConstants.TRAILING
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderTop),
                BorderFactory.createEmptyBorder(6, 10, 6, 4)
            )
        }

        val south = JPanel(BorderLayout()).apply {
            background = bg
            add(promptLabel, BorderLayout.WEST)
            add(input, BorderLayout.CENTER)
            add(send, BorderLayout.EAST)
        }

        f.layout = BorderLayout()
        f.add(scroll, BorderLayout.CENTER)
        f.add(south, BorderLayout.SOUTH)
        f.setSize(1000, 600)
        f.setLocationByPlatform(true)

        // 4) Stop first when closing via X
        f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        f.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent?) {
                // Run shutdown off the EDT so logs can still be painted
                Thread({
                    // Pano stopped on its own (a failed start keeps the window open to be read):
                    // nothing is left to shut down, closing the window ends the process.
                    val alreadyStopped = stopped
                    try {
                        if (!stopped) {
                            try {
                                runBlocking {
                                    interruptHandler?.invoke()
                                }
                            } catch (t: Throwable) {
                                System.err.println("\u001B[31mInterrupt handler error:\u001B[0m ${t.message}")
                            }
                        }
                    } finally {
                        restoreSystemStreams()
                        SwingUtilities.invokeLater { f.dispose() }
                        if (alreadyStopped) {
                            com.panomc.platform.Main.processExit.exit()
                        }
                    }
                }, "UiConsole-close").start()
            }
        })

        // Keep refs
        frame = f
        textPane = pane
        doc = pane.styledDocument
        inputField = input
        sendBtn = send

        // Text attrs
        StyleConstants.setFontFamily(defaultAttr, mono.family)
        StyleConstants.setFontSize(defaultAttr, 14)
        resetAttrs()
        installKeyBindings(pane)
        installSystemStreamsRedirect(teeToOriginal)

        // Start locked; unlock later via markReady()
        inputEnabled = false
        inputLockReason = "Starting…"
        applyInputLockUI()

        // On startup, focus the output area instead of the input
        f.isVisible = true
        SwingUtilities.invokeLater {
            pane.requestFocusInWindow()
            updateSendEnabled()
        }

        // 3) Listener for whitespace / empty-input checks
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateSendEnabled()
            override fun removeUpdate(e: DocumentEvent?) = updateSendEnabled()
            override fun changedUpdate(e: DocumentEvent?) = updateSendEnabled()
        })
    }

    /**
     * Modal yes / no question shown before the console window exists (the first-run confirmation).
     * Nothing is read from or written to disk, and it paints from the console palette like the
     * command row, so the desktop theme does not change how it looks. Blocks until answered; closing
     * the window, Esc and N answer no, Y answers yes, Enter presses the focused button (No at first).
     *
     * Call from any thread but the event dispatch thread. Throws when no window can be opened.
     *
     * [onCreated] gets the window before it is shown (on the event dispatch thread), so another thread
     * can end the question with [ConfirmDialog.close]; the result is then false and its caller ignores it.
     * [stillNeeded] is asked on the event dispatch thread before anything is built; when it says no (the
     * question was answered elsewhere while the display was slow) no window is built and the result is false.
     */
    fun confirmBeforeStart(
        title: String,
        header: String,
        message: String,
        question: String,
        stillNeeded: () -> Boolean = { true },
        onCreated: (ConfirmDialog) -> Unit = {}
    ): Boolean {
        var answer = false
        var failure: Throwable? = null

        SwingUtilities.invokeAndWait {
            try {
                if (!stillNeeded()) return@invokeAndWait

                val confirm = ConfirmDialog(title, header, message, question)
                onCreated(confirm)
                confirm.dialog.isVisible = true
                answer = confirm.answer
                confirm.dialog.dispose()
            } catch (t: Throwable) {
                failure = t
            }
        }

        failure?.let { throw it }

        return answer
    }

    /** Call when your app is fully ready: unlocks input and focuses the field. */
    fun markReady() {
        inputEnabled = true
        inputLockReason = ""
        applyInputLockUI()
        SwingUtilities.invokeLater {
            // Can take focus now
            inputField?.isFocusable = true
            inputField?.isRequestFocusEnabled = true
            inputField?.requestFocusInWindow()
            val field = inputField ?: return@invokeLater
            if (field.text.isBlank()) {
                field.text = ""
            }
            updateSendEnabled()
        }
    }

    /** Call when your app has stopped: lock input, keep window, print guidance. */
    fun markStopped() = markStoppedInternal()

    /** Optional manual toggle. */
    fun setInputEnabled(enabled: Boolean, reasonIfDisabled: String = "") {
        inputEnabled = enabled
        inputLockReason = if (enabled) "" else reasonIfDisabled
        applyInputLockUI()
        updateSendEnabled()
    }

    // ---------- Internals ----------

    private fun submitCommand() {
        val field = inputField ?: return
        val raw = field.text ?: return
        val cmd = raw.trim()
        if (cmd.isEmpty()) return

        if (!inputEnabled) {
            println("\u001B[90m(input is locked) $inputLockReason\u001B[0m")
            return
        }

        // Clear input immediately on the EDT
        field.text = ""
        updateSendEnabled()

        // Echo in the Swing pane (classic `> cmd` line).
        val echoPane = "\u001B[90m>\u001B[0m $cmd\n"
        parseAnsiAndAppend(echoPane)

        // Mirror the command on the original stdout exactly like a typed terminal entry: `> cmd`.
        // Real TTYs use printAbove so JLine can keep the user's in-progress buffer; dumb TTYs
        // (IntelliJ Run, pipes) use a manual rewrite to avoid printAbove's blank-line redraw bug.
        val termEcho = ConsoleInputReader.terminalStyleCommandLine(cmd)
        val jlr = ConsoleInputReader.reader
        try {
            when {
                jlr != null && ConsoleInputReader.ansiCapableTerminal -> jlr.printAbove(termEcho)
                jlr != null -> ConsoleInputReader.emitWithPromptRefresh(termEcho)
                else -> {
                    originalOut?.print(termEcho)
                    originalOut?.flush()
                }
            }
        } catch (_: Throwable) {
            originalOut?.print(termEcho)
            originalOut?.flush()
        }

        addToHistory(cmd)
        historyIndex = -1

        // Run the command handler off the EDT so blocking commands (like "stop")
        // don't freeze the GUI and prevent log output from being painted.
        val handler = commandHandler
        Thread({
            try {
                handler?.invoke(cmd) ?: run {
                    println("\u001B[33m(no command handler wired; command ignored)\u001B[0m")
                }
            } catch (t: Throwable) {
                System.err.println("\u001B[31mCommand error:\u001B[0m ${t.message}")
            }
        }, "UiConsole-cmd").start()
    }

    private fun updateSendEnabled() {
        val field = inputField ?: return
        val btn = sendBtn ?: return

        val textOk = field.text != null &&
                field.text.trim().isNotEmpty()

        val enabled = inputEnabled && !stopped && textOk
        btn.isEnabled = enabled
    }

    private fun applyInputLockUI() {
        val field = inputField ?: return
        val btn = sendBtn

        val hardDisabled = stopped
        if (hardDisabled) {
            field.isEnabled = false
            field.isEditable = false
            field.isFocusable = false
            field.isRequestFocusEnabled = false
            field.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
            field.text = ""
            field.caretColor = caretDisabled
            btn?.isEnabled = false
            field.toolTipText = "Pano is stopped"
            field.transferFocusUpCycle()
            return
        }

        val editable = inputEnabled
        field.isEnabled = true
        // Keep focus disabled initially; markReady() enables it
        field.isFocusable = editable
        field.isRequestFocusEnabled = editable
        field.isEditable = editable
        field.cursor = if (editable) Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

        if (editable) {
            field.toolTipText = null
        } else {
            if (field.text.isBlank()) field.text = ""
            field.caretColor = caretDisabled
            field.toolTipText = inputLockReason.ifBlank { "Input is locked" }
        }

        // Button enable state should also respect whitespace checks
        btn?.isEnabled = editable && !stopped
        updateSendEnabled()
    }

    private fun markStoppedInternal() {
        if (stopped) return
        stopped = true
        inputEnabled = false
        inputLockReason = "Pano is stopped"
        applyInputLockUI()
        SwingUtilities.invokeLater { inputField?.transferFocusUpCycle() }
        // Only the GUI has a window to close; a terminal just gets its prompt back.
        if (frame != null) {
            println("\u001B[33mPano stopped. Commands are disabled. To close this window, click the window's Close (X) button.\u001B[0m")
        }
    }

    private fun installSystemStreamsRedirect(teeToOriginal: Boolean) {
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err

        class ConsoleOutputStream(private val isErr: Boolean) : OutputStream() {
            private val buffer = java.io.ByteArrayOutputStream()

            override fun write(b: Int) {
                buffer.write(b)
                if (b == '\n'.code) {
                    flush()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                buffer.write(b, off, len)
                if (b.sliceArray(off until off + len).contains('\n'.code.toByte())) {
                    flush()
                }
            }

            /**
             * Drain the in-memory buffer to both the Swing pane and the original stream.
             *
             * Only complete lines (terminated by `\n`) are forwarded to the tee path. Any trailing
             * partial line stays buffered until the next `\n` arrives. This prevents JLine's
             * `printAbove` from being called twice for one logical log line — which is what caused
             * the "ekstra boş satırlar" symptom in IntelliJ Run consoles when log4j or any consumer
             * happened to flush mid-line.
             */
            override fun flush() {
                val data = buffer.toByteArray()
                if (data.isEmpty()) return

                val s = String(data, Charsets.UTF_8)
                parseAnsiAndAppend(s)

                if (teeToOriginal) {
                    val lastNl = s.lastIndexOf('\n')
                    val complete = if (lastNl >= 0) s.substring(0, lastNl + 1) else ""
                    val partial = if (lastNl >= 0) s.substring(lastNl + 1) else s

                    if (complete.isNotEmpty()) {
                        val jlr = ConsoleInputReader.reader
                        try {
                            when {
                                jlr != null && ConsoleInputReader.ansiCapableTerminal -> jlr.printAbove(complete)
                                jlr != null -> ConsoleInputReader.emitWithPromptRefresh(complete)
                                else -> {
                                    if (isErr) originalErr?.print(complete) else originalOut?.print(complete)
                                    if (isErr) originalErr?.flush() else originalOut?.flush()
                                }
                            }
                        } catch (_: Throwable) {
                            if (isErr) originalErr?.print(complete) else originalOut?.print(complete)
                            if (isErr) originalErr?.flush() else originalOut?.flush()
                        }
                    }

                    buffer.reset()
                    if (partial.isNotEmpty()) {
                        buffer.write(partial.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    buffer.reset()
                }
            }
        }

        System.setOut(PrintStream(ConsoleOutputStream(false), true))
        System.setErr(PrintStream(ConsoleOutputStream(true), true))
    }

    private fun restoreSystemStreams() {
        try {
            originalOut?.let { System.setOut(it) }
            originalErr?.let { System.setErr(it) }
        } catch (_: Exception) {
        }
    }

    private fun installKeyBindings(pane: JTextPane) {
        // Ctrl+C -> stop app, keep window
        pane.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke("control C"), "interrupt")
        pane.actionMap.put("interrupt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (!stopped) {
                    // Run off the EDT so the GUI stays responsive during shutdown
                    Thread({
                        try {
                            runBlocking {
                                interruptHandler?.invoke()
                            }
                        } catch (t: Throwable) {
                            System.err.println("\u001B[31mInterrupt handler error:\u001B[0m ${t.message}")
                        } finally {
                            markStoppedInternal()
                        }
                    }, "UiConsole-interrupt").start()
                } else {
                    println("\u001B[90mPano is already stopped. Close this window with the X button.\u001B[0m")
                }
            }
        })

        // Ctrl+Shift+C -> copy selection
        pane.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke("control shift C"), "copySel")
        pane.actionMap.put("copySel", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val sel = pane.selectedText ?: return
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sel), null)
            }
        })
    }

    private fun resetAttrs() {
        currentAttr = SimpleAttributeSet(defaultAttr.copyAttributes() as AttributeSet)
        StyleConstants.setBold(currentAttr, false)
        StyleConstants.setUnderline(currentAttr, false)
        StyleConstants.setForeground(currentAttr, fg)
        StyleConstants.setBackground(currentAttr, bg)
    }

    private fun parseAnsiAndAppend(s: String) {
        if (textPane == null || doc == null) return
        if (SwingUtilities.isEventDispatchThread()) {
            parseAnsiAndAppendEDT(s)
        } else {
            SwingUtilities.invokeLater { parseAnsiAndAppendEDT(s) }
        }
    }

    private fun parseAnsiAndAppendEDT(s: String) {
        var i = 0
        val n = s.length
        val ESC = '\u001B'
        while (i < n) {
            val c = s[i]
            if (c == ESC && i + 1 < n && s[i + 1] == '[') {
                i += 2
                val start = i
                while (i < n && s[i] != 'm') i++
                if (i < n && s[i] == 'm') {
                    applySgr(s.substring(start, i))
                    i++
                    continue
                }
            }
            val j = s.indexOf(ESC, i).let { if (it == -1) n else it }
            appendChunk(s.substring(i, j))
            i = j
        }
    }

    private fun appendChunk(chunk: String) {
        doc!!.insertString(doc!!.length, chunk, currentAttr)
        textPane!!.caretPosition = doc!!.length
    }

    private fun applySgr(paramStr: String) {
        if (paramStr.isEmpty()) {
            resetAttrs(); return
        }
        val params = paramStr.split(';').mapNotNull { it.toIntOrNull() }
        if (params.isEmpty()) {
            resetAttrs(); return
        }
        params.forEach { p ->
            when (p) {
                0 -> resetAttrs()
                1 -> StyleConstants.setBold(currentAttr, true)
                4 -> StyleConstants.setUnderline(currentAttr, true)
                in 30..37 -> StyleConstants.setForeground(currentAttr, ansiColor(p - 30, bright = false))
                in 40..47 -> StyleConstants.setBackground(currentAttr, ansiColor(p - 40, bright = false))
                in 90..97 -> StyleConstants.setForeground(currentAttr, ansiColor(p - 90, bright = true))
                in 100..107 -> StyleConstants.setBackground(currentAttr, ansiColor(p - 100, bright = true))
            }
        }
    }

    private fun ansiColor(idx: Int, bright: Boolean): Color {
        val base = arrayOf(
            Color(12, 12, 12),
            Color(197, 15, 31),
            Color(19, 161, 14),
            Color(193, 156, 0),
            Color(0, 55, 218),
            Color(136, 23, 152),
            Color(58, 150, 221),
            Color(204, 204, 204)
        )
        val brightSet = arrayOf(
            Color(118, 118, 118),
            Color(231, 72, 86),
            Color(22, 198, 12),
            Color(249, 241, 165),
            Color(59, 120, 255),
            Color(180, 0, 158),
            Color(97, 214, 214),
            Color(242, 242, 242)
        )
        val palette = if (bright) brightSet else base
        return palette[idx.coerceIn(0, 7)]
    }

    private fun pickMonospaceFont(): Font {
        val candidates = listOf(
            "JetBrains Mono", "Cascadia Mono", "Fira Code",
            "Consolas", "DejaVu Sans Mono", "Menlo", "Monaco", "Liberation Mono",
            "Monospaced"
        )
        val avail = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val chosen = candidates.firstOrNull { it in avail } ?: "Monospaced"
        return Font(chosen, Font.PLAIN, 14)
    }

    // ---------- Command row components ----------

    /**
     * Command field that takes every colour, border and font from the console palette.
     *
     * The system Look&Feel is not trusted here: GTK (Synth) paints the field from the desktop theme
     * (white box, light text on light), so the field installs the plain Basic delegate and sets every
     * property the delegate would otherwise read from UIManager. [updateUI] re-applies all of it, so
     * a later Look&Feel switch cannot bring the system theme back.
     */
    private class ConsoleTextField : JTextField() {

        override fun updateUI() {
            setUI(BasicTextFieldUI())

            isOpaque = true
            background = fieldBg
            foreground = fg
            disabledTextColor = fieldDisabledFg
            selectionColor = selectionBg
            selectedTextColor = selectionFg
            // The caret colour follows focus / lock state, keep it once we have set it
            if (caretColor == null || caretColor is UIResource) caretColor = caretDisabled
            caret?.blinkRate = 500
            margin = Insets(0, 0, 0, 0)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderTop),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            font = pickMonospaceFont().deriveFont(14f)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty()) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = placeholderColor
                val fm = g2.fontMetrics
                val x = insets.left
                val y = (height + fm.ascent - fm.descent) / 2
                g2.drawString(placeholderText, x, y)
            }
        }
    }

    /** Send button that paints its own background and text from the console palette. */
    private class ConsoleButton(text: String) : JButton(text) {

        override fun updateUI() {
            setUI(ConsoleButtonUI())

            isOpaque = true
            isContentAreaFilled = true
            isFocusPainted = false
            isBorderPainted = false
            isRolloverEnabled = true
            background = buttonBg
            foreground = fg
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            // The look and feel's own control font, regular weight; Dialog 12 where it has none.
            font = UIManager.getFont("Button.font")?.deriveFont(Font.PLAIN) ?: Font(Font.DIALOG, Font.PLAIN, 12)
        }
    }

    /**
     * Basic delegate with the system colours removed: the fill depends on the button state, the text
     * is the palette foreground (dimmed but readable when disabled), nothing is drawn for focus or
     * the pressed state beyond the fill.
     */
    private class ConsoleButtonUI : BasicButtonUI() {

        override fun update(g: Graphics, c: JComponent) {
            val model = (c as AbstractButton).model
            g.color = when {
                !model.isEnabled -> buttonBg
                model.isArmed && model.isPressed -> buttonPressedBg
                model.isRollover -> buttonHoverBg
                else -> buttonBg
            }
            g.fillRect(0, 0, c.width, c.height)
            paint(g, c)
        }

        override fun paintText(g: Graphics, c: JComponent, textRect: Rectangle, text: String) {
            val b = c as AbstractButton
            val g2 = g as Graphics2D
            // The desktop's own text settings (ClearType, LCD), as BasicButtonUI would have used them.
            (Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>)
                ?.let { g2.addRenderingHints(it) }
            g2.font = b.font
            g2.color = if (b.model.isEnabled) fg else buttonDisabledFg
            g2.drawString(text, textRect.x, textRect.y + g2.fontMetrics.ascent)
        }

        override fun paintFocus(g: Graphics, b: AbstractButton, viewRect: Rectangle, textRect: Rectangle, iconRect: Rectangle) {}

        override fun paintButtonPressed(g: Graphics, b: AbstractButton) {}
    }

    // ---------- Confirmation dialog ----------

    /**
     * The wrapped, read-only message of the confirmation window. A text area that is packed before it
     * has a width reports the height of its unwrapped lines, and the wrapped text then needs more rows
     * than it was given (the end of the message is cut off, there is no scroll pane). So it is given
     * its preferred width first, which makes its preferred height the wrapped one.
     */
    internal fun messageArea(message: String): JTextArea = JTextArea(message).apply {
        setUI(BasicTextAreaUI())
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = fg
        font = pickMonospaceFont().deriveFont(13f)
        border = BorderFactory.createEmptyBorder()
        columns = 56

        setSize(Dimension(preferredSize.width, Short.MAX_VALUE.toInt()))
    }

    /** The window behind [confirmBeforeStart]: dark, with a No button focused first. [answer] is false until Yes is chosen. */
    class ConfirmDialog(title: String, header: String, message: String, question: String) {
        @Volatile
        var answer = false
            private set

        // Unowned (a null Frame would get Swing's hidden shared owner, and with it no taskbar button).
        val dialog = JDialog(null as Window?, title, Dialog.ModalityType.APPLICATION_MODAL)

        private val yesBtn = ConsoleButton("Continue")
        private val noBtn = ConsoleButton("Exit")

        init {
            val promptYellow = Color(0xF5, 0xD7, 0x42)
            val sans = Font(Font.DIALOG, Font.PLAIN, 13)

            val headerLabel = JLabel(header).apply {
                foreground = promptYellow
                font = Font(Font.DIALOG, Font.BOLD, 15)
                border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            }

            val text = messageArea(message)

            val questionLabel = JLabel(question).apply {
                foreground = fg
                font = sans.deriveFont(Font.BOLD)
                border = BorderFactory.createEmptyBorder(14, 0, 0, 0)
            }

            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
                add(noBtn)
                add(yesBtn)
            }

            listOf(yesBtn, noBtn).forEach { b ->
                // The shared button paints no focus; the dialog is keyboard-first, so it outlines the focused one.
                b.isBorderPainted = true
                val idle = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(buttonBg, 1), BorderFactory.createEmptyBorder(5, 11, 5, 11)
                )
                val focused = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0x569cd6), 1), BorderFactory.createEmptyBorder(5, 11, 5, 11)
                )
                b.border = idle
                b.addFocusListener(object : java.awt.event.FocusAdapter() {
                    override fun focusGained(e: java.awt.event.FocusEvent?) { b.border = focused }
                    override fun focusLost(e: java.awt.event.FocusEvent?) { b.border = idle }
                })
            }

            val body = JPanel(BorderLayout()).apply {
                background = bg
                isOpaque = true
                border = BorderFactory.createEmptyBorder(18, 20, 16, 20)
                add(headerLabel, BorderLayout.NORTH)
                add(text, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(questionLabel, BorderLayout.NORTH)
                    add(buttons, BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }

            dialog.contentPane = body
            dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            dialog.isAlwaysOnTop = true

            applyAppIcon(dialog, "/logo.png")

            yesBtn.addActionListener { choose(true) }
            noBtn.addActionListener { choose(false) }

            dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosing(e: java.awt.event.WindowEvent?) = choose(false)
                override fun windowOpened(e: java.awt.event.WindowEvent?) {
                    noBtn.requestFocusInWindow()
                }
            })

            bindKey("ESCAPE") { choose(false) }
            bindKey("N") { choose(false) }
            bindKey("Y") { choose(true) }
            bindKey("ENTER") { choose(dialog.focusOwner === yesBtn) }

            dialog.pack()
            dialog.setLocationRelativeTo(null)
        }

        private fun choose(yes: Boolean) {
            answer = yes
            dialog.isVisible = false
        }

        /**
         * Hides the window without an answer, from any thread: the hiding runs on the event dispatch thread,
         * where [confirmBeforeStart] then disposes it. Safe to call before the window is shown (it then never
         * stays up) and more than once.
         */
        fun close() {
            SwingUtilities.invokeLater { dialog.isVisible = false }
        }

        private fun bindKey(key: String, action: () -> Unit) {
            val root = dialog.rootPane
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), "confirm-$key")
            root.actionMap.put("confirm-$key", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = action()
            })
        }
    }

    // ---------- Icon helpers ----------

    /**
     * Loads /logo.png (or any given resourcePath) and sets:
     *  - window icon images at multiple sizes
     *  - Taskbar/Dock icon (Java 9+ Taskbar API)
     */
    private fun applyAppIcon(f: Window, resourcePath: String) {
        val url = javaClass.getResource(resourcePath) ?: return
        val baseImg = ImageIcon(url).image ?: return

        val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
        val images = sizes.map { scaleImageSmooth(baseImg, it, it) }

        try {
            f.iconImages = images
        } catch (_: Exception) {
            f.setIconImage(baseImg)
        }

        try {
            val taskbar = Taskbar.getTaskbar()
            taskbar.iconImage = images.lastOrNull() ?: baseImg
        } catch (_: Exception) {
        }
    }

    private fun scaleImageSmooth(src: Image, w: Int, h: Int): Image {
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = scaled.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.drawImage(src, 0, 0, w, h, null)
        g2.dispose()
        return scaled
    }
}