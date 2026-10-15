package com.panomc.platform.util

/**
 * Turns a failed `listen()` into a line the site owner can act on: the bare reason ("Permission
 * denied", "Address already in use") does not say what to do about it.
 */
object ListenFailure {
    fun message(
        scheme: String,
        host: String,
        port: Int,
        cause: Throwable,
        os: OperatingSystem,
        portKey: String
    ): String {
        val reason = cause.message ?: cause.toString()

        return "Failed to listen on $scheme://$host:$port, reason: $reason. ${hint(port, reason, os, portKey)}"
    }

    internal fun hint(port: Int, reason: String, os: OperatingSystem, portKey: String): String {
        val example = if (port == 8088) 8089 else 8088
        val changePort = "set \"$portKey\" under \"server\" in config.conf to another port (for example $example)"

        return when {
            reason.contains("Permission denied", ignoreCase = true) || reason.contains("Access is denied", ignoreCase = true) -> {
                val elevated = when (os) {
                    OperatingSystem.WINDOWS -> "run Pano as administrator"
                    else -> "run Pano with sudo"
                }

                "Port $port needs elevated privileges on this system: $elevated, or $changePort. " +
                        "Pano keeps running; restart it after fixing this."
            }

            reason.contains("in use", ignoreCase = true) ->
                "Port $port is used by another program: stop that program, or $changePort. " +
                        "Pano keeps running; restart it after fixing this."

            else -> "Pano keeps running; fix the problem and restart it, or $changePort."
        }
    }
}
