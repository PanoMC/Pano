package com.panomc.node.server

import java.io.File

/**
 * Puts the port Pano assigned a proxy into the proxy's own config file.
 *
 * A backend reads `server-port` from `server.properties`, which the node already writes. A proxy
 * reads none of that: Velocity binds what `velocity.toml` says and BungeeCord/Waterfall what the
 * first listener in `config.yml` says, so every proxy came up on its software's default port --
 * Velocity on 25565, on top of the node's first backend, and two BungeeCords on 25577 together --
 * while the panel showed the port it had assigned.
 *
 * Applied before every start rather than once at install, because the file does not exist until
 * the proxy has run once and because the port can be changed in the startup settings later. Only
 * the port is touched: the bind address and everything else in the file stay the admin's.
 */
object ProxyPortConfig {
    const val VELOCITY_FILE = "velocity.toml"
    const val BUNGEE_FILE = "config.yml"

    private val VELOCITY_BIND = Regex("""^(\s*bind\s*=\s*")([^"]*)(".*)$""")
    private val BUNGEE_HOST = Regex("""^(\s*-?\s*host:\s*)(\S+)(\s*)$""")
    private val BUNGEE_QUERY_PORT = Regex("""^(\s*-?\s*query_port:\s*)(\d+)(\s*)$""")

    /**
     * Writes [port] into the config of the proxy in [directory]. Returns the file it changed or
     * created, or null when nothing had to change (not a proxy, no port, already right).
     */
    fun apply(directory: File, software: String, port: Int): File? {
        if (port !in 1..65535) {
            return null
        }

        return when (software.lowercase()) {
            "velocity" -> applyVelocity(File(directory, VELOCITY_FILE), port)
            "bungeecord", "waterfall" -> applyBungee(File(directory, BUNGEE_FILE), port)
            else -> null
        }
    }

    private fun applyVelocity(file: File, port: Int): File? {
        if (!file.isFile) {
            // Velocity fills in every key it does not find, so the one line is a whole config.
            file.writeText("bind = \"0.0.0.0:$port\"\n")

            return file
        }

        var changed = false

        val lines = file.readLines().map { line ->
            val match = VELOCITY_BIND.matchEntire(line) ?: return@map line
            val rewritten = match.groupValues[1] + withPort(match.groupValues[2], port) + match.groupValues[3]

            if (rewritten != line) {
                changed = true
            }

            rewritten
        }

        return write(file, lines, changed)
    }

    private fun applyBungee(file: File, port: Int): File? {
        if (!file.isFile) {
            // BungeeCord completes a listener it is given and writes the rest of the file itself.
            file.writeText("listeners:\n- host: 0.0.0.0:$port\n  query_port: $port\n")

            return file
        }

        var changed = false
        var inListeners = false
        var hostDone = false
        var queryDone = false

        val lines = file.readLines().map { line ->
            // `listeners:` is a top-level key; the next top-level key ends it. Only the first
            // listener is Pano's -- a second one is something the admin added on purpose.
            if (line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("-")) {
                inListeners = line.trimEnd() == "listeners:"

                return@map line
            }

            if (!inListeners) {
                return@map line
            }

            val rewritten = when {
                !hostDone && BUNGEE_HOST.matches(line) -> {
                    hostDone = true

                    BUNGEE_HOST.matchEntire(line)!!.let { it.groupValues[1] + withPort(it.groupValues[2], port) + it.groupValues[3] }
                }

                !queryDone && BUNGEE_QUERY_PORT.matches(line) -> {
                    queryDone = true

                    BUNGEE_QUERY_PORT.matchEntire(line)!!.let { it.groupValues[1] + port + it.groupValues[3] }
                }

                else -> line
            }

            if (rewritten != line) {
                changed = true
            }

            rewritten
        }

        return write(file, lines, changed)
    }

    /** `host:port` with the port replaced; an address with no port gets one. */
    private fun withPort(address: String, port: Int): String {
        val host = address.substringBeforeLast(':', address).ifBlank { "0.0.0.0" }

        return "$host:$port"
    }

    private fun write(file: File, lines: List<String>, changed: Boolean): File? {
        if (!changed) {
            return null
        }

        file.writeText(lines.joinToString("\n", postfix = "\n"))

        return file
    }
}
