package com.panomc.node.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * How the admin said their server runs, from a Pano Agent's first run (SM-76):
 * `<server>/.pano-agent/launch.json` = `{ "jar": "...", "memoryMb": 4096, "jvmArgs": ["..."] }`.
 *
 * The launcher writes it before the worker first pairs; the worker announces it in its hello
 * (`agentLaunch`) while it has no server yet, so Pano creates the server's row with that memory and
 * those flags, and adopts the folder with that jar. After the adoption the file is only a record:
 * the server's startup settings live in Pano from then on, and nothing reads it for an adopted
 * server again.
 *
 * Written by hand, in plain JDK, because the launcher writes it; the worker reads it with
 * [AgentLaunchReader].
 */
data class AgentLaunch(
    /** The jar to run, a file name in the server folder; null when there was none to name. */
    val jar: String?,
    val memoryMb: Int,
    val jvmArgs: List<String>
) {
    fun toJson(): String = buildString {
        append("{")

        jar?.let { append("\"jar\": ").append(quote(it)).append(", ") }

        append("\"memoryMb\": ").append(memoryMb).append(", ")
        append("\"jvmArgs\": [").append(jvmArgs.joinToString(", ") { quote(it) }).append("]")
        append("}\n")
    }

    companion object {
        const val FILE_NAME = "launch.json"

        fun file(dataDir: File) = File(dataDir, FILE_NAME)

        /** Writes [launch] into [dataDir], whole or not at all. */
        fun write(dataDir: File, launch: AgentLaunch) {
            dataDir.mkdirs()

            val target = file(dataDir)
            val temporary = File(dataDir, "$FILE_NAME.tmp")

            temporary.writeText(launch.toJson(), Charsets.UTF_8)

            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** [value] as a JSON string. */
        fun quote(value: String): String = buildString {
            append('"')

            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char == '\n' -> append("\\n")
                    char == '\r' -> append("\\r")
                    char == '\t' -> append("\\t")
                    char < ' ' -> append("\\u").append(String.format("%04x", char.code))
                    else -> append(char)
                }
            }

            append('"')
        }
    }
}
