package com.panomc.platform.util

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
import javax.swing.plaf.basic.BasicButtonUI
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
 *  - Uses system Look&Feel for native window chrome
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

    private val placeholderText = "Enter command here..."

    // ---------- Public API ----------
    
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
        val input = object : JTextField() {
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
        }.apply {
            background = fieldBg
            foreground = fg
            caretColor = caretDisabled
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderTop),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            font = pickMonospaceFont().deriveFont(14f)
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

        // 2) Keep "Send" button always dark
        val send = JButton("Send").apply {
            background = Color(0x2d2d30)
            foreground = fg
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            isFocusPainted = false
            isOpaque = true
            isContentAreaFilled = true
            // Use Basic UI to minimize LAF disabled-gray painting
            setUI(BasicButtonUI())
            addActionListener { submitCommand() }
        }

        input.addActionListener { submitCommand() }

        val mono = pickMonospaceFont()
        pane.font = mono.deriveFont(14f)

        val promptLabel = JLabel("> ").apply {
            foreground = fg
            background = fieldBg
            isOpaque = true
            font = mono.deriveFont(14f)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderTop),
                BorderFactory.createEmptyBorder(6, 8, 6, 0)
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
                    // If you want to restore the streams to their previous state:
                    restoreSystemStreams()
                    f.dispose()
                }
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

        // We don't print the '>' here anymore because it's handled by the redirection
        // or we want it to be consistent with ConsoleInputReader
        println("\u001B[90m>\u001B[0m $cmd")

        try {
            addToHistory(cmd)
            historyIndex = -1
            
            commandHandler?.invoke(cmd) ?: run {
                println("\u001B[33m(no command handler wired; command ignored)\u001B[0m")
            }
        } catch (t: Throwable) {
            System.err.println("\u001B[31mCommand error:\u001B[0m ${t.message}")
        } finally {
            field.text = ""
            updateSendEnabled()
        }
    }

    private fun updateSendEnabled() {
        val field = inputField ?: return
        val btn = sendBtn ?: return

        val textOk = field.text != null &&
                field.text.trim().isNotEmpty()

        val enabled = inputEnabled && !stopped && textOk
        btn.isEnabled = enabled
        // 2) Arka plan koyu kalmaya devam etsin
        btn.background = Color(0x2d2d30)
        // Keep it readable even when disabled
        btn.foreground = fg
        btn.isOpaque = true
        btn.isContentAreaFilled = true
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
        println("\u001B[33mPano stopped. Commands are disabled. To close this window, click the window's Close (X) button.\u001B[0m")
    }

    private fun installSystemStreamsRedirect(teeToOriginal: Boolean) {
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err

        class ConsoleOutputStream(private val isErr: Boolean) : OutputStream() {
            private val buffer = java.io.ByteArrayOutputStream()

            override fun write(b: Int) {
                buffer.write(b)
                if (b == '\n'.toInt()) {
                    flush()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                buffer.write(b, off, len)
                if (b.sliceArray(off until off + len).contains('\n'.toByte())) {
                    flush()
                }
            }

            override fun flush() {
                val data = buffer.toByteArray()
                if (data.isEmpty()) return
                
                val s = String(data, Charsets.UTF_8)
                parseAnsiAndAppend(s)
                
                if (teeToOriginal) {
                    val jlineReader = com.panomc.platform.command.ConsoleInputReader.reader
                    if (jlineReader != null) {
                        // Use JLine's printAbove to keep the prompt at the bottom
                        jlineReader.printAbove(s)
                    } else {
                        // Fallback to direct output
                        if (isErr) originalErr?.print(s) else originalOut?.print(s)
                        if (isErr) originalErr?.flush() else originalOut?.flush()
                    }
                }
                
                buffer.reset()
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
                    try {
                        runBlocking {
                            interruptHandler?.invoke()
                        }
                    } catch (t: Throwable) {
                        System.err.println("\u001B[31mInterrupt handler error:\u001B[0m ${t.message}")
                    } finally {
                        markStoppedInternal()
                    }
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

    // ---------- Icon helpers ----------

    /**
     * Loads /logo.png (or any given resourcePath) and sets:
     *  - frame icon images at multiple sizes
     *  - Taskbar/Dock icon (Java 9+ Taskbar API)
     */
    private fun applyAppIcon(f: JFrame, resourcePath: String) {
        val url = javaClass.getResource(resourcePath) ?: return
        val baseImg = ImageIcon(url).image ?: return

        val sizes = listOf(16, 24, 32, 48, 64, 128, 256, 512)
        val images = sizes.map { scaleImageSmooth(baseImg, it, it) }

        try {
            f.iconImages = images
        } catch (_: Exception) {
            f.iconImage = baseImg
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