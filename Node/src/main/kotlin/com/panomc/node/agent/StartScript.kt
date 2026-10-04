package com.panomc.node.agent

import java.io.File

/**
 * The start script a server folder already has, read for the defaults of a Pano Agent's first
 * run (SM-76): the jar it starts, its `-Xmx`, and the Java flags around them. The admin replaces
 * that script's server jar with the agent, and the agent should keep running the server the way
 * the script did.
 *
 * Best effort by design. The first line of the first script that has `java` and `-jar` on it is
 * read; anything this cannot make sense of -- an open quote, `-jar` before `java`, no `-jar` at
 * all (Forge's `@unix_args.txt` scripts) -- means the script is ignored, never guessed at. Plain JDK.
 */
object StartScript {
    /** The scripts looked for, in this order. */
    val NAMES = listOf("start.sh", "run.sh", "start.command", "start.bat", "run.bat", "start.cmd")

    /** What a script says. [jar] is null when it names the jar through a variable. */
    data class Found(
        /** The script's file name, for the "Found start.sh" line. */
        val file: String,
        val jar: String?,
        val memoryMb: Int?,
        /** The flags between `java` and `-jar`, without `-Xms`/`-Xmx` and without variables. */
        val jvmArgs: List<String>
    )

    /** The first script in [directory] that reads, or null when none does. */
    fun find(directory: File): Found? = NAMES.asSequence()
        .map { File(directory, it) }
        .filter { it.isFile && it.length() <= MAX_SCRIPT_BYTES }
        .mapNotNull { script ->
            val text = try {
                script.readText(Charsets.UTF_8)
            } catch (_: Exception) {
                return@mapNotNull null
            }

            parse(text, windows = isWindowsScript(script.name))?.copy(file = script.name)
        }
        .firstOrNull()

    /** Whether [name] is a `cmd.exe` script, where a backslash is a path separator. */
    fun isWindowsScript(name: String): Boolean =
        name.lowercase().let { it.endsWith(".bat") || it.endsWith(".cmd") }

    /** The first line of [text] that starts a jar with Java, read; null when there is none. */
    fun parse(text: String, windows: Boolean): Found? {
        val line = joinContinuations(text, windows).firstOrNull { line ->
            val lower = line.trim().lowercase()

            !isComment(lower, windows) && lower.contains("java") && lower.contains("-jar")
        } ?: return null

        return parseLine(line, windows)
    }

    /** One command line, read; null when it is not `<java> [flags] -jar <jar> …`. */
    fun parseLine(line: String, windows: Boolean): Found? {
        val tokens = JvmArgs.split(separateOperators(line), windows) ?: return null

        // The command this line runs is the part around java: `cd x && java …`, `exec java …`,
        // `java … | tee log`, `java … & pause`.
        val javaAt = tokens.indexOfFirst { isJava(it) }.takeIf { it >= 0 } ?: return null
        val end = (javaAt + 1 until tokens.size).firstOrNull { tokens[it] in OPERATORS } ?: tokens.size
        val command = tokens.subList(javaAt + 1, end)

        val jarAt = command.indexOf("-jar").takeIf { it >= 0 } ?: return null
        val flags = command.subList(0, jarAt)
        val jar = command.getOrNull(jarAt + 1)?.takeIf { it.isNotBlank() && !isVariable(it) } ?: return null

        return Found(
            file = "",
            jar = jar.removePrefix("./").removePrefix(".\\").takeIf { !it.startsWith("-") },
            memoryMb = JvmArgs.heapMbOf(flags),
            jvmArgs = flags.filterNot { JvmArgs.isHeapFlag(it) || isVariable(it) }
        )
    }

    /** Whether [token] is the Java executable: `java`, a path to one, or a variable naming it. */
    fun isJava(token: String): Boolean {
        val name = token.substringAfterLast('/').substringAfterLast('\\').lowercase()

        return Regex("^javaw?(\\.exe)?$").matches(name) || (isVariable(token) && token.lowercase().contains("java"))
    }

    /** `$JAVA`, `${JAVA_HOME}/bin/java`, `%JAVA%`: something a shell would have expanded. */
    private fun isVariable(token: String): Boolean =
        token.contains('$') || Regex("%[A-Za-z0-9_~]+%").containsMatchIn(token) || token.contains("{{")

    private fun isComment(lower: String, windows: Boolean): Boolean =
        lower.startsWith("#") || (windows && (lower.startsWith("rem ") || lower == "rem" || lower.startsWith("::") || lower.startsWith("@rem")))

    /** The script's lines, with `\` (or `^` for cmd.exe) continuations joined into one. */
    private fun joinContinuations(text: String, windows: Boolean): List<String> {
        val marker = if (windows) '^' else '\\'
        val lines = mutableListOf<String>()
        val current = StringBuilder()

        text.lines().forEach { raw ->
            val line = raw.trimEnd('\r')

            if (line.trimEnd().endsWith(marker)) {
                current.append(line.trimEnd().dropLast(1)).append(' ')
            } else {
                current.append(line)
                lines.add(current.toString())
                current.setLength(0)
            }
        }

        if (current.isNotEmpty()) {
            lines.add(current.toString())
        }

        return lines
    }

    /**
     * [line] with the shell's command separators (`;`, `&`, `&&`, `|`, `||`, `>`, `<`) outside
     * quotes set apart by spaces, so they come out of [JvmArgs.split] as tokens of their own.
     */
    private fun separateOperators(line: String): String {
        val result = StringBuilder()
        var quote: Char? = null

        line.forEach { char ->
            when {
                quote != null -> {
                    if (char == quote) quote = null
                    result.append(char)
                }

                char == '"' || char == '\'' -> {
                    quote = char
                    result.append(char)
                }

                char in ";&|<>" -> result.append(' ').append(char).append(' ')

                else -> result.append(char)
            }
        }

        // `&&` became `& &` above; put the pairs back together so they read as one operator.
        return result.toString()
            .replace("&  &", "&&")
            .replace("|  |", "||")
            .replace(">  >", ">>")
    }

    private val OPERATORS = setOf(";", "&", "&&", "|", "||", ">", ">>", "<")

    /** Anything bigger is not a start script. */
    private const val MAX_SCRIPT_BYTES = 64 * 1024L
}
