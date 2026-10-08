package com.panomc.platform.util

import com.panomc.platform.hosted.ContainerMode
import java.io.Console
import java.io.File
import java.util.Locale
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
     * Paths (relative to the working directory) that only a Pano that has started there leaves behind.
     * Any one of them means Pano has run in this directory before, so it is never asked about again.
     * `config.conf` is not listed: its location can be moved with `-Dpano.configFile`, see [hasRunBefore].
     * `plugins` is not listed either: an operator may put plugin jars there before the first start.
     */
    val RUN_MARKERS = listOf(
        "logs",
        ".console_history",
        ".config_history",
        ".temp",
        "libraries",
        "themes",
        "setup-ui",
        "panel-ui",
        "file-uploads",
        "certificates",
        "node-data",
        "data/db",
        "pano-updater.jar"
    )

    /**
     * Files that sit in a fresh install's directory without being anything else's: what the release
     * ships next to the Pano jar (`pano-node.jar`, the checksum files, `LICENSE`), compared without
     * regard to case. The Pano jar itself is passed in separately because it can be named anything.
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
        "desktop.ini"
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
     * trailing `/`. Empty when [dir] cannot be listed.
     */
    fun foreignEntries(dir: File, runningJarName: String?): List<String> =
        (dir.listFiles() ?: emptyArray())
            .filterNot { isIgnored(it.name, runningJarName) }
            .map { if (it.isDirectory) it.name + "/" else it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** `y` or `yes` in any case, surrounding spaces ignored. Empty, anything else and EOF (null) are no. */
    fun parseAnswer(line: String?): Boolean =
        line?.trim()?.lowercase(Locale.ROOT) in YES_ANSWERS

    /**
     * Whether a person can answer in the terminal: a [Console] exists and, where the JDK can tell
     * ([isTerminal], Java 22+), it is a real terminal. Without a console (Docker, systemd, a pipe) nobody can.
     */
    fun isInteractive(hasConsole: Boolean, isTerminal: Boolean? = null): Boolean =
        hasConsole && (isTerminal ?: true)

    sealed class Decision {
        /** Start without a word. */
        object Proceed : Decision()

        /** Ask first; [viaGui] picks the dialog over the terminal. */
        data class Ask(val entries: List<String>, val viaGui: Boolean) : Decision()

        /** Nobody can answer: print the warning and start. */
        data class WarnAndContinue(val entries: List<String>) : Decision()
    }

    fun decide(
        skipRequested: Boolean,
        hasRunBefore: Boolean,
        entries: List<String>,
        gui: Boolean,
        interactive: Boolean
    ): Decision = when {
        skipRequested || hasRunBefore || entries.isEmpty() -> Decision.Proceed
        gui -> Decision.Ask(entries, viaGui = true)
        interactive -> Decision.Ask(entries, viaGui = false)
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

    /**
     * Returns when Pano may start. Ends the process with exit code 1 when the answer is no.
     *
     * @param gui the Swing console is going to open, so the question is a dialog
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

            FirstRunPolicy.foreignEntries(dir, ContainerMode.detectRunningJar()?.name)
        } catch (_: Exception) {
            // A directory that cannot be read is no reason to refuse to start.
            return
        }

        val interactive = isTerminalInteractive()
        var decision = FirstRunPolicy.decide(false, false, entries, gui, interactive)
        val warning = FirstRunPolicy.warningLines(dir, entries)

        if (decision is FirstRunPolicy.Decision.Ask && decision.viaGui) {
            val answer = askInDialog(warning)

            if (answer != null) {
                if (!answer) abort(dir)
                return
            }

            // The dialog could not be shown (a display that does not answer): the terminal, if there is one.
            decision = FirstRunPolicy.decide(false, false, entries, gui = false, interactive = interactive)
        }

        when (decision) {
            is FirstRunPolicy.Decision.WarnAndContinue -> {
                warning.forEach { System.err.println(it) }
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

    /** Anything but y / yes, and a closed input, is a no. */
    private fun askInTerminal(warning: List<String>): Boolean {
        val console = System.console() ?: return false

        warning.forEach { console.printf("%s%n", it) }

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
