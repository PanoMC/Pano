package com.panomc.platform.util

import com.panomc.platform.hosted.ContainerMode
import java.io.Console
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The decisions behind the first-run confirmation, free of any I/O beyond reading the directory
 * listing, so they can be tested on their own. [FirstRunGuard] does the asking.
 */
object FirstRunPolicy {

    /** Command-line flag that skips the question (and the warning). Same style as `--dev` / `--demo`. */
    const val SKIP_FLAG = "--allow-non-empty-dir"

    /** Environment variable that skips the question, for containers and service managers. */
    const val SKIP_ENV = "PANO_ALLOW_NON_EMPTY_DIR"

    /** How many entries of the directory the warning lists before it says "and N more". */
    const val LISTED_ENTRIES = 5

    const val QUESTION = "Continue?"

    /**
     * Paths (relative to the working directory) that nothing but a Pano that has started there leaves
     * behind. Any one of them means Pano has run in this directory before, so it is never asked about
     * again. Generic folder names are deliberately absent (`logs`, `libraries`, `themes`,
     * `certificates`, `file-uploads`, `.temp`, `data/db`): a Minecraft server, a database or any other
     * application has them too, and this is the folder an operator picks by mistake. `config.conf` is
     * not listed either: its location can be moved with `-Dpano.configFile`, see [hasRunBefore]; and
     * `plugins`, because an operator may put plugin jars there before the first start.
     */
    val RUN_MARKERS = listOf(
        ".console_history",
        ".config_history",
        "setup-ui",
        "panel-ui",
        "node-data",
        "pano-updater.jar"
    )

    /**
     * Files that sit in a fresh install's directory without being anything else's: what the release
     * ships next to the Pano jar (`pano-node.jar`, the checksum files, `LICENSE`), compared without
     * regard to case, plus what a volume or a launcher script puts next to it (`lost+found`, `start.sh`).
     * The Pano jar itself is passed in separately because it can be named anything.
     * Hidden files are NOT ignored in general (a `.git` or `.env` says the directory belongs to
     * something else); only the junk file managers and operating systems leave in every folder is.
     */
    private val IGNORED_NAMES = setOf(
        "pano-node.jar",
        "pano-node.jar.sha256",
        "license",
        ".ds_store",
        ".localized",
        ".directory",
        "thumbs.db",
        "desktop.ini",
        "lost+found",
        "system volume information",
        "\$recycle.bin",
        "start.sh",
        "start.bat",
        "start.cmd",
        "run.sh",
        "run.bat",
        "run.cmd"
    )

    private val IGNORED_PREFIXES = listOf("._", ".trash-", ".fuse_hidden")

    private val YES_ANSWERS = setOf("y", "yes")

    private val TRUTHY = setOf("1", "true", "yes", "on")

    /** True when `--allow-non-empty-dir` is among [args] or [env] has `PANO_ALLOW_NON_EMPTY_DIR` set to 1 / true / yes / on. */
    fun isSkipRequested(args: Array<String>, env: Map<String, String>): Boolean =
        Args.hasFlag(args, SKIP_FLAG) ||
                env[SKIP_ENV]?.trim()?.lowercase(Locale.ROOT) in TRUTHY

    /**
     * Whether Pano has started in [dir] before: the config file exists ([configFile] is resolved
     * against [dir] when relative) or any of [RUN_MARKERS] does.
     */
    fun hasRunBefore(dir: File, configFile: File = File("config.conf")): Boolean {
        val config = if (configFile.isAbsolute) configFile else File(dir, configFile.path)

        return config.exists() || RUN_MARKERS.any { File(dir, it).exists() }
    }

    /**
     * Whether [name] belongs to a normal fresh install and so does not count as "something else":
     * the running Pano jar ([runningJarName], any name), its `.sha256` file, and the other names in
     * [IGNORED_NAMES] / [IGNORED_PREFIXES].
     */
    fun isIgnored(name: String, runningJarName: String?): Boolean {
        val lower = name.lowercase(Locale.ROOT)

        if (runningJarName != null) {
            val jar = runningJarName.lowercase(Locale.ROOT)

            if (lower == jar || lower == "$jar.sha256") return true
        }

        return lower in IGNORED_NAMES || IGNORED_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * What is in [dir] besides the ignored files: names sorted case-insensitively, folders with a
     * trailing `/`. Empty when [dir] cannot be listed. A symlink that resolves to [runningJar] is
     * ignored too: a start through `Pano.jar -> Pano-1.0.0.jar` reports the target as the jar.
     */
    fun foreignEntries(dir: File, runningJarName: String?, runningJar: File? = null): List<String> {
        val jarPath = runningJar?.let { canonicalOrNull(it) }

        return (dir.listFiles() ?: emptyArray())
            .filterNot {
                isIgnored(it.name, runningJarName) ||
                        (jarPath != null && Files.isSymbolicLink(it.toPath()) && canonicalOrNull(it) == jarPath)
            }
            .map { if (it.isDirectory) it.name + "/" else it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun canonicalOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (_: Exception) {
        null
    }

    /** `y` or `yes` in any case, surrounding spaces ignored. Empty, anything else and EOF (null) are no. */
    fun parseAnswer(line: String?): Boolean =
        line?.trim()?.lowercase(Locale.ROOT) in YES_ANSWERS

    /**
     * Whether a person can answer in the terminal: a [Console] exists and, where the JDK can tell
     * ([isTerminal], Java 22+), it is a real terminal. Without a console (Docker, systemd, a pipe) nobody can.
     */
    fun isInteractive(hasConsole: Boolean, isTerminal: Boolean? = null): Boolean =
        hasConsole && (isTerminal ?: true)

    /** Where a question is asked. */
    enum class Channel {
        /** Only the dialog, before the console window: a start with the GUI and no terminal. */
        DIALOG,

        /** Only the terminal: no GUI. */
        TERMINAL,

        /** Both at once, the first answer counts: a start with the GUI from an interactive terminal. */
        BOTH
    }

    sealed class Decision {
        /** Start without a word. */
        object Proceed : Decision()

        /** Ask first, in [channel]. */
        data class Ask(val entries: List<String>, val channel: Channel) : Decision()

        /** Nobody can answer: print the warning and start. */
        data class WarnAndContinue(val entries: List<String>) : Decision()
    }

    fun decide(
        skipRequested: Boolean,
        hasRunBefore: Boolean,
        entries: List<String>,
        gui: Boolean,
        interactive: Boolean,
        pollableTerminal: Boolean = true
    ): Decision = when {
        skipRequested || hasRunBefore || entries.isEmpty() -> Decision.Proceed
        // The two-place question polls the terminal; a Windows console cannot be polled reliably, so a
        // GUI start there asks in the dialog alone.
        gui && interactive && pollableTerminal -> Decision.Ask(entries, Channel.BOTH)
        gui -> Decision.Ask(entries, Channel.DIALOG)
        interactive -> Decision.Ask(entries, Channel.TERMINAL)
        else -> Decision.WarnAndContinue(entries)
    }

    /** The warning, one line per element. Lists the first [LISTED_ENTRIES] of [entries], then "and N more". */
    fun warningLines(dir: File, entries: List<String>): List<String> {
        val lines = mutableListOf(
            "This directory is not empty, and Pano has never been started in it before:",
            "  ${dir.path}",
            if (entries.size == 1) "It contains 1 entry:"
            else if (entries.size <= LISTED_ENTRIES) "It contains ${entries.size} entries:"
            else "It contains ${entries.size} entries, for example:"
        )

        entries.take(LISTED_ENTRIES).forEach { lines += "  $it" }

        if (entries.size > LISTED_ENTRIES) lines += "  ... and ${entries.size - LISTED_ENTRIES} more"

        lines += "Pano will create its own files and folders here (config.conf, logs, themes, file-uploads and more), next to these."

        return lines
    }

    /** Tells whoever looks at the terminal (or the service log) how to get past the question without answering it. */
    fun skipHintLine(): String = "To skip this question, pass $SKIP_FLAG or set $SKIP_ENV=1."

    /** Printed to stderr while the dialog is open: a dialog nobody sees would otherwise be a silent hang. */
    fun waitingInDialogLine(): String = "Waiting for your answer in the Pano window. ${skipHintLine()}"

    /** Printed above the terminal question while the dialog is open too. */
    fun answerInEitherPlaceLine(): String =
        "Answer here or in the Pano window, the first answer counts. ${skipHintLine()}"

    /** Printed in the terminal when the dialog was answered first. */
    fun answeredInWindowLine(): String = "Answered in the Pano window."

    /** Printed in the terminal when its input ended while the window can still be answered. */
    fun terminalClosedLine(): String = "The terminal is closed. Waiting for your answer in the Pano window."

    /** The line printed instead of a question when nobody can answer. */
    fun continueWithoutAskingLine(): String =
        "No terminal is attached to answer, so Pano continues. To skip this check, pass $SKIP_FLAG or set $SKIP_ENV=1."

    /** The prompt of the terminal question. */
    fun terminalPrompt(): String = "$QUESTION [y/N] "
}

/**
 * Asks before Pano starts for the first time in a directory that holds other things. Runs first in
 * `Main.main`, before the console window, the logger or any file of Pano's own exists, so an answer
 * of no leaves the directory exactly as it was.
 */
object FirstRunGuard {

    /** How often the terminal is looked at while the dialog is open too. */
    private const val POLL_MS = 40L

    /** How long a terminal answer waits for the dialog to disappear. */
    private const val DIALOG_CLOSE_WAIT_MS = 3000L

    /**
     * Returns when Pano may start. Ends the process with exit code 1 when the answer is no.
     *
     * @param gui the Swing console is going to open, so the question is a dialog shown before it
     * @param alreadyHandled nothing to ask: a container (its data directory is a managed volume) or the
     *   detached copy of a `-bg` start whose launching process has already asked
     */
    fun confirmOrExit(args: Array<String>, gui: Boolean, alreadyHandled: Boolean) {
        if (alreadyHandled) return

        val dir = File("").absoluteFile

        val entries = try {
            if (FirstRunPolicy.isSkipRequested(args, System.getenv())) return

            val configFile = File(System.getProperty("pano.configFile", "config.conf"))

            if (FirstRunPolicy.hasRunBefore(dir, configFile)) return

            val runningJar = ContainerMode.detectRunningJar()

            FirstRunPolicy.foreignEntries(dir, runningJar?.name, runningJar)
        } catch (_: Exception) {
            // A directory that cannot be read is no reason to refuse to start.
            return
        }

        val interactive = isTerminalInteractive()
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        var decision = FirstRunPolicy.decide(false, false, entries, gui, interactive, pollableTerminal = !windows)
        val warning = FirstRunPolicy.warningLines(dir, entries)
        var warningPrinted = false

        if (decision is FirstRunPolicy.Decision.Ask && decision.channel == FirstRunPolicy.Channel.BOTH) {
            if (askInBoth(dir, warning)) return

            abort(dir)
        }

        if (decision is FirstRunPolicy.Decision.Ask && decision.channel == FirstRunPolicy.Channel.DIALOG) {
            // The dialog may open where nobody looks (a service's desktop, another workspace), and no log
            // exists yet: say on stderr what is going on and how to get past it.
            warning.forEach { System.err.println(it) }
            warningPrinted = true
            System.err.println(FirstRunPolicy.waitingInDialogLine())

            val answer = askInDialog(warning + FirstRunPolicy.skipHintLine())

            if (answer != null) {
                if (!answer) abort(dir)
                return
            }

            // The dialog could not be shown (a display that does not answer): the terminal, if there is one.
            decision = FirstRunPolicy.decide(false, false, entries, gui = false, interactive = interactive)
        }

        when (decision) {
            is FirstRunPolicy.Decision.WarnAndContinue -> {
                if (!warningPrinted) warning.forEach { System.err.println(it) }
                System.err.println(FirstRunPolicy.continueWithoutAskingLine())
            }

            is FirstRunPolicy.Decision.Ask -> if (!askInTerminal(warning)) abort(dir)

            FirstRunPolicy.Decision.Proceed -> Unit
        }
    }

    private fun abort(dir: File): Nothing {
        System.err.println("Pano was not started. Nothing was created or changed in ${dir.path}.")
        exitProcess(1)
    }

    /** True / false for the person's answer, null when the dialog could not be shown (the terminal asks instead). */
    private fun askInDialog(warning: List<String>): Boolean? = try {
        UiConsole.confirmBeforeStart(
            title = "Pano - directory is not empty",
            header = "Warning",
            message = warning.joinToString("\n"),
            question = FirstRunPolicy.QUESTION
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * The dialog and the terminal ask at the same time; the first answer wins and the other side is
     * ended: a terminal answer closes the dialog, a dialog answer stops the terminal's polling and says so.
     * Returns whether Pano may start.
     *
     * The terminal is read without blocking ([PolledLine] over the raw descriptor, polled every
     * [POLL_MS]), so when the dialog answers no thread stays inside a read of standard input to swallow
     * the first console command. Terminal modes are not touched. The question is printed through the
     * console (standard output), the stream the "is a terminal" check looked at, not through standard
     * error, which may be redirected.
     *
     * A closed terminal (end of input, Ctrl+D) is not an answer while the window can still be answered:
     * the terminal is dropped and the window decides. It counts as a no only when the window is gone
     * without an answer (it could not be shown).
     */
    private fun askInBoth(dir: File, warning: List<String>): Boolean {
        val console = System.console()
        val first = FirstAnswer()
        val dialogRef = AtomicReference<UiConsole.ConfirmDialog?>(null)

        fun say(line: String) {
            if (console != null) {
                console.printf("%s%n", line)
                console.flush()
            } else {
                System.err.println(line)
            }
        }

        warning.forEach { say(it) }
        say(FirstRunPolicy.answerInEitherPlaceLine())

        // The window is shown by a thread of its own: it blocks until the window is gone.
        val dialogThread = thread(name = "FirstRunDialog", isDaemon = true) {
            try {
                val yes = UiConsole.confirmBeforeStart(
                    title = "Pano - directory is not empty",
                    header = "Warning",
                    message = (warning + FirstRunPolicy.skipHintLine()).joinToString("\n"),
                    question = FirstRunPolicy.QUESTION,
                    // The terminal may have answered while the display was still starting up.
                    stillNeeded = { first.answer == null }
                ) { shown ->
                    dialogRef.set(shown)

                    // ... or while the window was being built.
                    if (first.answer != null) shown.close()
                }

                first.offer(yes, AnswerSource.DIALOG)
            } catch (_: Throwable) {
                // No window could be shown (a display that does not answer): the terminal alone decides.
            }
        }

        val prompt = FirstRunPolicy.terminalPrompt()

        if (console != null) {
            console.printf("%s", prompt)
            console.flush()
        } else {
            System.err.print(prompt)
            System.err.flush()
        }

        val line = PolledLine(FileInputStream(FileDescriptor.`in`))
        var terminalOpen = true

        while (first.answer == null) {
            if (terminalOpen) {
                when (val result = line.poll()) {
                    is PolledLine.Result.Line -> first.offer(FirstRunPolicy.parseAnswer(result.text), AnswerSource.TERMINAL)

                    PolledLine.Result.EndOfInput -> {
                        terminalOpen = false
                        say("")
                        say(FirstRunPolicy.terminalClosedLine())
                    }

                    PolledLine.Result.Pending -> first.await(POLL_MS)
                }
            } else {
                first.await(POLL_MS)
            }

            // Nobody can answer any more: the terminal is gone and the window ended without an answer.
            if (!terminalOpen && first.answer == null && !dialogThread.isAlive) {
                first.offer(false, AnswerSource.TERMINAL)
            }
        }

        val answer = first.answer!!

        if (answer.source == AnswerSource.TERMINAL) {
            // The answer was typed here; take the window down (on the event dispatch thread) and wait for it,
            // so it is gone before the console window opens. After a yes the wait has no limit: boot goes on
            // into Swing next, and building two windows at once breaks the look and feel (on a slow display
            // the window may not even exist yet; it is then never built). After a no the process ends anyway.
            dialogRef.get()?.close()

            if (answer.yes) dialogThread.join() else dialogThread.join(DIALOG_CLOSE_WAIT_MS)
        } else {
            // The prompt line is still open on the terminal; end it so the terminal does not look stuck.
            say("")
            say(FirstRunPolicy.answeredInWindowLine())
        }

        return answer.yes
    }

    /** Anything but y / yes, and a closed input, is a no. */
    private fun askInTerminal(warning: List<String>): Boolean {
        val console = System.console() ?: return false

        warning.forEach { console.printf("%s%n", it) }
        console.printf("%s%n", FirstRunPolicy.skipHintLine())

        return FirstRunPolicy.parseAnswer(console.readLine("%s", FirstRunPolicy.terminalPrompt()))
    }

    private fun isTerminalInteractive(): Boolean {
        val console: Console? = System.console()

        // Console.isTerminal() exists from Java 22, where System.console() is non-null even when piped.
        val isTerminal = console?.let {
            try {
                Console::class.java.getMethod("isTerminal").invoke(it) as? Boolean
            } catch (_: Exception) {
                null
            }
        }

        return FirstRunPolicy.isInteractive(console != null, isTerminal)
    }
}
