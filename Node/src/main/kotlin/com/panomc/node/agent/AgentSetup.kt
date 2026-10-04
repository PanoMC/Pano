package com.panomc.node.agent

import com.panomc.node.task.ServerJars
import java.io.File
import java.io.PrintStream

/**
 * The questions a Pano Agent asks the first time it runs (SM-76): the Pano address, how the server
 * is run -- its jar, its memory, its Java arguments -- and last the pairing code, so the code is
 * fresh when the worker pairs with it (agent codes last a minute).
 *
 * Every question is one line with its `[default]`, and Enter keeps it. The defaults are what the
 * folder already says: a start script's jar, `-Xmx` and flags ([StartScript]), the launcher's own
 * `-Xmx` (the admin swapped the server jar for the agent in a script that had one), and the jar an
 * adoption would pick ([ServerJars]). `q`, or the end of input (Ctrl+D), stops without writing
 * anything.
 *
 * In a terminal the answer is typed after the question on the same line; anywhere else -- a hosting
 * panel's web console piped into stdin -- each question is a line of its own, answered with the
 * next line typed into the console.
 *
 * Reads and writes only through [readLine] and [out], so the whole dialogue is tested without a
 * terminal. Plain JDK: the launcher asks.
 */
class AgentSetup(
    private val serverDir: File,
    /** The next line typed, or null at the end of input. */
    private val readLine: () -> String?,
    private val out: PrintStream,
    /** Whether a person types at a terminal (the answer then goes after the question). */
    private val terminal: Boolean,
    /** The agent's own jar, which is never offered as the server's. */
    private val agentJar: File?,
    /** The launcher's `-Xmx` in megabytes, when it was started with one. */
    private val launcherMemoryMb: Int? = null,
    private val checkAddress: (String) -> AgentAddress.Check = { AgentAddress.check(it) },
    /** Whether the terminal can show an em dash and an arrow; ASCII stand-ins otherwise. */
    unicode: Boolean = true
) {
    /** What the first run settled on. [panoUrl] and [code] are null only in [defaults] without them. */
    data class Answers(
        val panoUrl: String?,
        val code: String?,
        val jar: String?,
        val memoryMb: Int,
        val jvmArgs: List<String>
    ) {
        val launch: AgentLaunch get() = AgentLaunch(jar, memoryMb, jvmArgs)
    }

    /** How the questions ended. */
    sealed class Outcome {
        data class Link(val answers: Answers) : Outcome()

        /** Stopped: `q`, Ctrl+D, or a folder with no jar to run. Nothing was written. */
        data class Quit(val exitCode: Int) : Outcome()

        /**
         * Input ended before anything was typed, and not at a terminal: nobody is there to answer,
         * so the agent goes on as if it had not asked.
         */
        object NoInput : Outcome()
    }

    private class Stop(val outcome: Outcome) : RuntimeException(null, null, false, false)

    private val dash = if (unicode) "—" else "-"

    private val arrow = if (unicode) "→" else "->"

    private val codeQuestion = "Pairing code (Pano panel $arrow Add Server $arrow Link with the Pano Agent)"

    private var answered = 0

    private val script: StartScript.Found? by lazy { StartScript.find(serverDir) }

    /** What the questions offer, and what a run that asks nothing uses. */
    fun defaults(panoUrl: String? = null, code: String? = null): Answers {
        val candidates = ServerJars.candidates(serverDir, agentJar)

        val jar = script?.jar?.let { named -> candidates.firstOrNull { it.equals(named, ignoreCase = true) } }
            ?: ServerJars.pick(serverDir, agentJar)
            ?: candidates.firstOrNull()

        val memory = launcherMemoryMb?.takeIf { it >= JvmArgs.MIN_MEMORY_MB }
            ?: script?.memoryMb
            ?: JvmArgs.DEFAULT_MEMORY_MB

        return Answers(
            panoUrl = panoUrl,
            code = code,
            jar = jar,
            memoryMb = memory.coerceIn(JvmArgs.MIN_MEMORY_MB, JvmArgs.MAX_MEMORY_MB),
            jvmArgs = script?.jvmArgs.orEmpty()
        )
    }

    /** The start script the defaults come from, if one was found. */
    fun scriptName(): String? = script?.file

    /**
     * Asks what was not given. [givenUrl] and [givenCode] (`--pano`/`--code`, or `PANO_URL` and
     * `PANO_PAIR_CODE`) are not asked for, unless the admin says no to the summary: then every
     * question comes again, with what was given and answered as the defaults.
     */
    fun run(givenUrl: String? = null, givenCode: String? = null): Outcome = try {
        ask(givenUrl, givenCode)
    } catch (stop: Stop) {
        stop.outcome
    }

    private fun ask(givenUrl: String?, givenCode: String?): Outcome {
        line("Pano Agent $dash linking ${serverDir.path} to Pano. Press Enter to keep a [default].")

        scriptName()?.let { line("Found $it $dash using its settings as defaults.") }

        var current = defaults(givenUrl, givenCode)
        var askUrl = givenUrl == null
        var askCode = givenCode == null

        while (true) {
            val url = if (askUrl) askAddress(current.panoUrl) else current.panoUrl!!
            val jar = askJar(current.jar)
            val memory = askMemory(current.memoryMb)
            val jvmArgs = askJvmArgs(current.jvmArgs)

            line("")
            line("  Pano address:    $url")
            line("  Server folder:   ${serverDir.path}")
            line("  Server jar:      $jar")
            line("  Memory:          ${JvmArgs.formatMemory(memory)}")
            line("  Java arguments:  ${describe(jvmArgs)}")

            if (!confirm("Link this folder to Pano? [Y/n]")) {
                current = Answers(url, current.code, jar, memory, jvmArgs)
                askUrl = true
                askCode = true

                continue
            }

            val code = if (askCode) askCode(current.code) else current.code!!

            return Outcome.Link(Answers(url, code, jar, memory, jvmArgs))
        }
    }

    /**
     * After Pano refused the code: says so and asks for a new one. Null when the admin gave none
     * (Enter, `q`, Ctrl+D), which ends the agent.
     */
    fun askCodeAgain(reason: String): String? = try {
        line(reason)

        unquote(prompt(question(codeQuestion, null))).ifEmpty { null }
    } catch (_: Stop) {
        null
    }

    private fun askAddress(default: String?): String {
        var shown = default

        while (true) {
            val typed = prompt(question("Pano address (your website, e.g. https://example.com)", shown)).ifEmpty { shown.orEmpty() }

            if (typed.isEmpty()) {
                continue
            }

            val url = AgentAddress.normalise(typed)

            if (url == null) {
                line("That is not a web address; type it like https://example.com.")

                continue
            }

            line("Checking $url ...")

            when (val check = checkAddress(url)) {
                is AgentAddress.Check.Ok -> {
                    if (check.url != url) {
                        line("Using ${check.url}: $url sends there.")
                    }

                    return check.url
                }

                else -> {
                    line(AgentAddress.message(url, check, schemeTyped = typed.contains("://")))

                    shown = typed
                }
            }
        }
    }

    private fun askJar(default: String?): String {
        val candidates = ServerJars.candidates(serverDir, agentJar)

        if (candidates.isEmpty()) {
            line("There is no server jar in ${serverDir.path}. Run the agent in your server's folder, next to the server's jar.")

            throw Stop(Outcome.Quit(NO_SERVER_JAR_EXIT_CODE))
        }

        val shown = default?.let { name -> candidates.firstOrNull { it.equals(name, ignoreCase = true) } }
            ?: ServerJars.pick(serverDir, agentJar)
            ?: candidates.first()

        if (candidates.size > 1) {
            candidates.forEachIndexed { index, name -> line("  ${index + 1}) $name") }
        }

        while (true) {
            val typed = prompt(question("Server jar", shown)).ifEmpty { shown }

            val chosen = typed.toIntOrNull()?.let { candidates.getOrNull(it - 1) }
                ?: candidates.firstOrNull { it == typed }
                ?: candidates.firstOrNull { it.equals(typed, ignoreCase = true) }

            if (chosen != null) {
                return chosen
            }

            line(
                when {
                    AgentLayout.isAgentJarName(typed) || AgentFiles.isOwnJar(serverDir, typed, agentJar) ->
                        "That is the Pano Agent itself; pick your server's jar."

                    candidates.size > 1 -> "There is no $typed in this folder. Type its number or its name."

                    else -> "There is no $typed in this folder."
                }
            )
        }
    }

    private fun askMemory(default: Int): Int {
        val shown = JvmArgs.formatMemory(default)

        while (true) {
            val typed = prompt(question("Memory for the server", shown)).ifEmpty { shown }
            val mb = JvmArgs.memoryAnswerMb(typed)

            when {
                mb == null -> line("Type an amount like 4G or 4096M.")
                mb < JvmArgs.MIN_MEMORY_MB -> line("A server needs at least ${JvmArgs.formatMemory(JvmArgs.MIN_MEMORY_MB)}.")
                mb > JvmArgs.MAX_MEMORY_MB -> line("That is more than Pano allows (${JvmArgs.formatMemory(JvmArgs.MAX_MEMORY_MB)}).")
                else -> return mb
            }
        }
    }

    private fun askJvmArgs(default: List<String>): List<String> {
        while (true) {
            val typed = prompt(question("Extra Java arguments", describe(default)))

            val args = when {
                typed.isEmpty() -> default
                typed == "-" || typed.equals("none", ignoreCase = true) -> emptyList()
                else -> JvmArgs.split(typed) ?: run {
                    line("A quote is left open; type them again.")

                    null
                }
            } ?: continue

            val (kept, dropped) = JvmArgs.withoutLaunchFlags(args)

            if (dropped.isNotEmpty()) {
                line("Left out ${JvmArgs.join(dropped)}: the memory and the jar are asked separately.")
            }

            return kept
        }
    }

    private fun askCode(default: String?): String {
        while (true) {
            val code = unquote(prompt(question(codeQuestion, default))).ifEmpty { default.orEmpty() }

            if (code.isNotEmpty()) {
                return code
            }
        }
    }

    private fun confirm(text: String): Boolean {
        while (true) {
            when (prompt(text).lowercase()) {
                "", "y", "yes" -> return true
                "n", "no" -> return false
                else -> line("Type y or n.")
            }
        }
    }

    /**
     * Shows [text] and returns what was typed, trimmed. `q` and the end of input stop the questions;
     * the end of input before any answer, away from a terminal, means nobody is there to ask.
     */
    private fun prompt(text: String): String {
        if (terminal) {
            out.print("$text ")
        } else {
            out.println(text)
        }

        out.flush()

        val typed = readLine()

        if (typed == null) {
            if (terminal) {
                out.println()
            }

            if (!terminal && answered == 0) {
                throw Stop(Outcome.NoInput)
            }

            stopped()
        }

        answered++

        val trimmed = typed.trim()

        if (trimmed.equals("q", ignoreCase = true)) {
            stopped()
        }

        return trimmed
    }

    private fun stopped(): Nothing {
        line("Stopped; nothing was saved. Run the agent again to link this folder.")

        throw Stop(Outcome.Quit(0))
    }

    private fun question(text: String, default: String?): String = if (default.isNullOrEmpty()) "$text:" else "$text [$default]:"

    private fun describe(args: List<String>): String = if (args.isEmpty()) "none" else JvmArgs.join(args)

    private fun line(text: String) {
        out.println(text)
        out.flush()
    }

    companion object {
        /** A folder with no jar but the agent's own: nothing to run, and nothing to ask again. */
        const val NO_SERVER_JAR_EXIT_CODE = 2

        /**
         * The line shown when the worker could not pair with the code the admin gave. [reason] is
         * what the worker wrote down; Pano's refusal of the code itself reads as the likely cause.
         */
        fun codeRejected(reason: String?, unicode: Boolean = true): String {
            val dash = if (unicode) "—" else "-"
            val known = reason?.trim()?.takeIf { it.isNotEmpty() && !it.contains(REFUSED_CODE) }

            return if (known == null) {
                "Pano did not accept that code (it may have expired $dash codes last a minute). Copy the current one from the panel."
            } else {
                "Pano did not accept that code ($known). Copy the current one from the panel."
            }
        }

        /** Pano's error code for a pairing code it does not know (expired, used, or mistyped). */
        const val REFUSED_CODE = "INVALID_NODE_PAIRING_CODE"

        private fun unquote(value: String): String {
            val trimmed = value.trim()

            return if (trimmed.length >= 2 && (trimmed.first() == '\'' || trimmed.first() == '"') && trimmed.last() == trimmed.first()) {
                trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                trimmed
            }
        }
    }
}
