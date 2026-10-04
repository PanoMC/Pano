package com.panomc.platform.node.ssh

/**
 * The single command Pano runs on a host it is turning into a node.
 *
 * The install script is not downloaded by the remote host: Pano already has it, and piping it
 * into `sh -s` over the same channel means the target needs no outbound access to Pano and no
 * curl, and that the script the machine runs is byte for byte the one Pano intended rather than
 * whatever a proxy in between served.
 *
 * `sudo -n` never prompts. A host whose sudo wants a password fails immediately with a message
 * saying so, which is a far better outcome than a session that hangs on an invisible prompt until
 * the ten-minute cap.
 */
object SshBootstrapCommand {
    fun build(panoUrl: String, code: String, sudo: Boolean, name: String? = null): String {
        val command = buildString {
            append("sh -s -- --pano ")
            append(quote(panoUrl))
            append(" --code ")
            append(quote(code))

            if (!name.isNullOrBlank()) {
                append(" --name ")
                append(quote(name))
            }
        }

        return if (sudo) "sudo -n $command" else command
    }

    /**
     * Wraps a value in single quotes, which is the only quoting a POSIX shell does not interpret.
     *
     * Everything here comes from a form somebody filled in, so it is treated as hostile: the
     * closing quote is the one character that could end the argument, and `'\''` is how a single
     * quote survives inside single quotes.
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
