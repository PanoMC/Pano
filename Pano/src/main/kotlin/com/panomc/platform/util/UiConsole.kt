package com.panomc.platform.util

import com.panomc.platform.util.UiConsole.markReady
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.OutputStream
import java.io.PrintStream
import javax.swing.*
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

object UiConsole {

    private var frame: JFrame? = null
    private var textPane: JTextPane? = null
    private var doc: StyledDocument? = null
    private var inputField: JTextField? = null
    private var sendBtn: JButton? = null

    private var defaultAttr: SimpleAttributeSet = SimpleAttributeSet()
    private var currentAttr: SimpleAttributeSet = SimpleAttributeSet()

    private var interruptHandler: (() -> Unit)? = null
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
    private val placeholderColor = Color(0x8a8a8a) // dim gray
    private val caretDisabled = Color(0x5a5a5a)

    private val placeholderText = "> Enter command here..."

    // ---------- Public API ----------

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

    fun setInterruptHandler(handler: (() -> Unit)?) {
        interruptHandler = handler
    }

    fun setCommandHandler(handler: ((String) -> Unit)?) {
        commandHandler = handler
    }

    /**
     * Input starts LOCKED until you call [markReady].
     */
    fun showConsoleWindow(title: String = "Pano Console", teeToOriginal: Boolean = true) {
        if (frame != null) {
            frame!!.toFront(); return
        }

        System.setProperty("awt.useSystemAAFontSettings", "on")
        System.setProperty("swing.aatext", "true")

        UIManager.put("TextPane.background", bg)
        UIManager.put("TextPane.foreground", fg)
        UIManager.put("ScrollBar.thumb", Color(0x3a3d41))
        UIManager.put("ScrollBar.track", Color(0x252526))

        val f = JFrame(title)

        // Output
        val pane = JTextPane().apply {
            isEditable = false
            background = bg
            foreground = fg
            margin = Insets(6, 8, 6, 8)
        }
        val scroll = JScrollPane(pane).apply { border = BorderFactory.createEmptyBorder() }

        // Input
        val input = JTextField().apply {
            background = fieldBg
            foreground = placeholderColor
            caretColor = caretDisabled
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, borderTop),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
            )
            font = pickMonospaceFont().deriveFont(14f)
            text = placeholderText
        }
        // Placeholder behavior
        input.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (input.text == placeholderText) {
                    input.text = ""
                }
                if (inputEnabled && !stopped) {
                    input.foreground = fg
                    input.caretColor = fg
                }
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (input.text.isBlank()) {
                    input.text = placeholderText
                    input.foreground = placeholderColor
                    input.caretColor = if (inputEnabled && !stopped) fg else caretDisabled
                }
            }
        })

        val send = JButton("Send").apply {
            background = Color(0x2d2d30)
            foreground = fg
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            isFocusPainted = false
            addActionListener { submitCommand() }
        }
        input.addActionListener { submitCommand() }

        val south = JPanel(BorderLayout()).apply {
            background = bg
            add(input, BorderLayout.CENTER)
            add(send, BorderLayout.EAST)
        }

        val mono = pickMonospaceFont()
        pane.font = mono.deriveFont(14f)

        f.layout = BorderLayout()
        f.add(scroll, BorderLayout.CENTER)
        f.add(south, BorderLayout.SOUTH)
        f.setSize(1000, 600)
        f.setLocationByPlatform(true)
        f.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

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

        // Start locked (editable=false to preserve colors)
        inputEnabled = false
        inputLockReason = "Starting…"
        applyInputLockUI()

        f.isVisible = true
    }

    /** App fully ready -> unlock input */
    fun markReady() {
        inputEnabled = true
        inputLockReason = ""
        applyInputLockUI()
        SwingUtilities.invokeLater {
            inputField?.requestFocusInWindow()
            // show prompt style if field is empty
            val field = inputField ?: return@invokeLater
            if (field.text.isBlank()) {
                field.text = ""
                field.foreground = fg
                field.caretColor = fg
            }
        }
    }

    /** App stopped -> lock input, keep window, print guidance */
    fun markStopped() {
        if (stopped) return
        stopped = true
        inputEnabled = false
        inputLockReason = "Pano is stopped"
        applyInputLockUI()
        // ensure focus leaves the (now disabled) field
        SwingUtilities.invokeLater { inputField?.transferFocusUpCycle() }
        println("\u001B[33mPano stopped. Commands are disabled. To close this window, click the window's Close (X) button.\u001B[0m")
    }

    /** Optional manual toggle */
    fun setInputEnabled(enabled: Boolean, reasonIfDisabled: String = "") {
        inputEnabled = enabled
        inputLockReason = if (enabled) "" else reasonIfDisabled
        applyInputLockUI()
    }

    // ---------- Internals ----------

    private fun submitCommand() {
        val field = inputField ?: return
        val raw = field.text ?: return
        val cmd = raw.trim()
        if (cmd.isEmpty() || cmd == placeholderText) return

        if (!inputEnabled) {
            println("\u001B[90m(input is locked) $inputLockReason\u001B[0m")
            return
        }

        println("\u001B[90m>\u001B[0m $cmd")

        try {
            commandHandler?.invoke(cmd) ?: run {
                println("\u001B[33m(no command handler wired; command ignored)\u001B[0m")
            }
        } catch (t: Throwable) {
            System.err.println("\u001B[31mCommand error:\u001B[0m ${t.message}")
        } finally {
            field.text = ""
        }
    }

    private fun applyInputLockUI() {
        val field = inputField ?: return
        val btn = sendBtn

        // Two levels:
        //  - starting lock  -> editable=false, enabled=true (keeps colors; can still focus)
        //  - stopped (hard) -> enabled=false (cannot focus / interact)
        val hardDisabled = stopped

        if (hardDisabled) {
            // HARD DISABLE (after stop): no focus, no typing, no send
            field.isEnabled = false
            field.isEditable = false
            field.isFocusable = false
            field.isRequestFocusEnabled = false
            field.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

            // Show placeholder in dim color
            field.text = placeholderText
            field.foreground = placeholderColor
            field.caretColor = caretDisabled

            btn?.isEnabled = false
            field.toolTipText = "Pano is stopped"
            // Optionally move focus away if it was here
            field.transferFocusUpCycle()
            return
        }

        // STARTING or READY state
        val editable = inputEnabled
        // Keep enabled to preserve dark colors and allow focus when ready
        field.isEnabled = true
        field.isFocusable = true
        field.isRequestFocusEnabled = true
        field.isEditable = editable
        field.cursor = if (editable)
            Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        else
            Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)

        // Caret / text color & placeholder
        if (editable) {
            if (field.text == placeholderText) field.text = ""
            field.foreground = fg
            field.caretColor = fg
            field.toolTipText = null
        } else {
            // starting lock
            if (field.text.isBlank()) field.text = placeholderText
            field.foreground = placeholderColor
            field.caretColor = caretDisabled
            field.toolTipText = inputLockReason.ifBlank { "Input is locked" }
        }

        btn?.isEnabled = editable
    }

    private fun installSystemStreamsRedirect(teeToOriginal: Boolean) {
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err

        val out = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                parseAnsiAndAppend(String(byteArrayOf(b.toByte())))
                if (teeToOriginal) originalOut?.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                parseAnsiAndAppend(s)
                if (teeToOriginal) originalOut?.write(b, off, len)
            }

            override fun flush() {
                if (teeToOriginal) originalOut?.flush()
            }
        }, true)

        val err = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                parseAnsiAndAppend(String(byteArrayOf(b.toByte())))
                if (teeToOriginal) originalErr?.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                val s = String(b, off, len)
                parseAnsiAndAppend(s)
                if (teeToOriginal) originalErr?.write(b, off, len)
            }

            override fun flush() {
                if (teeToOriginal) originalErr?.flush()
            }
        }, true)

        System.setOut(out)
        System.setErr(err)
    }

    private fun installKeyBindings(pane: JTextPane) {
        // Ctrl+C -> stop app, keep window
        pane.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke("control C"), "interrupt")
        pane.actionMap.put("interrupt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (!stopped) {
                    try {
                        interruptHandler?.invoke()
                    } catch (t: Throwable) {
                        System.err.println("\u001B[31mInterrupt handler error:\u001B[0m ${t.message}")
                    }
                } else {
                    println("\u001B[90mPano is already stopped. Close this window with the X button.\u001B[0m")
                }
            }
        })

        // Ctrl+Shift+C -> copy
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
}
