package com.panomc.platform.server.alert

import com.github.jknack.handlebars.Handlebars

/**
 * What one alert says, kept as keys and values until the moment somebody is actually told.
 *
 * An alert is written down once and read by several people, who do not necessarily read the same
 * language. The `server_alert` row and the log line are the record and stay in [english]; the
 * e-mail is one per recipient, so it is rendered from [keys] and [variables] against whatever
 * locale that recipient reads Pano in.
 *
 * [keys] is a list rather than a single key because half of what an alert says is optional — an
 * exit code, the console line that explains a crash, the error a backup ended with — and a
 * sentence assembled by gluing an optional English tail onto a translated head only reads
 * correctly in the language it was written in. Each part is a whole sentence of its own and the
 * kind picks the ones it has.
 */
data class ServerAlertMessage(
    val kind: ServerAlertKind,
    val keys: List<String>,
    val variables: Map<String, Any?>,
    val english: String
) {
    /**
     * The headline and the message in whatever language [translate] answers in.
     *
     * [translate] is expected to fall back to en-US for a key its own locale file is missing, so
     * reaching [english] here means the key is missing everywhere. That is the only case worth a
     * whole-body fallback: a body half in the reader's language and half in English reads worse
     * than an English one, and an empty e-mail is worse than both.
     */
    suspend fun render(translate: suspend (key: String, variables: Map<String, Any?>) -> String?): Rendered {
        // These values pass through Handlebars twice: once here, once when the mail template is
        // rendered. Only the second pass may escape them, or a server called "R&D" reaches an
        // inbox as "R&amp;D".
        val safe = variables.mapValues { (_, value) -> if (value is String) Handlebars.SafeString(value) else value }

        val title = translated("$GROUP.${kind.id}.title", safe, translate) ?: defaultTitle()
        val parts = keys.map { translated("$GROUP.${kind.id}.$it", safe, translate) }

        val body = if (parts.isEmpty() || parts.any { it == null }) english else parts.joinToString(" ")

        return Rendered(title, body)
    }

    /** A headline made out of the id, for a build whose locale files do not even have the en-US key. */
    private fun defaultTitle() = kind.id.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

    private suspend fun translated(
        key: String,
        variables: Map<String, Any?>,
        translate: suspend (key: String, variables: Map<String, Any?>) -> String?
    ) = try {
        translate(key, variables)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** One alert as one reader will see it. */
    data class Rendered(val title: String, val body: String)

    companion object {
        /** Where every kind's e-mail text lives in the locale files. */
        const val GROUP = "mail.server-alert.kinds"

        /**
         * A managed server's process died on its own.
         *
         * [reason] is the console line that explains it and is already cleaned by the caller; it
         * gets its own sentence rather than being appended to the first one, because "Last console
         * line: …" is a label a translator has to be able to write themselves.
         */
        fun serverCrashed(serverName: String, exitCode: Int?, reason: String?) = ServerAlertMessage(
            kind = ServerAlertKind.SERVER_CRASHED,
            keys = listOfNotNull(
                if (exitCode == null) "message" else "message-with-exit-code",
                reason?.let { "reason" }
            ),
            variables = mapOf("serverName" to serverName, "exitCode" to exitCode, "reason" to reason),
            english = "\"$serverName\" stopped on its own" + (exitCode?.let { " (exit code $it)" } ?: "") + "." +
                (reason?.let { " $it" } ?: "")
        )

        /** A node stopped answering, so nothing it runs can be reached. */
        fun nodeOffline(nodeName: String, serverCount: Int) = ServerAlertMessage(
            kind = ServerAlertKind.NODE_OFFLINE,
            keys = listOf(if (serverCount > 0) "message-with-servers" else "message"),
            variables = mapOf("nodeName" to nodeName, "serverCount" to serverCount),
            english = "Node \"$nodeName\" stopped answering" +
                if (serverCount > 0) "; $serverCount server(s) are out of reach." else "."
        )

        /** A backup task ended in FAILED, which means tonight has no copy of that world. */
        fun backupFailed(serverName: String, error: String?) = ServerAlertMessage(
            kind = ServerAlertKind.BACKUP_FAILED,
            keys = listOfNotNull("message", error?.let { "error" }),
            variables = mapOf("serverName" to serverName, "error" to error),
            english = "A backup of \"$serverName\" failed" + (error?.let { ": $it" } ?: ".")
        )

        /** A node's data disk is nearly full. */
        fun diskLow(nodeName: String, percent: Int) = ServerAlertMessage(
            kind = ServerAlertKind.DISK_LOW,
            keys = listOf("message"),
            variables = mapOf("nodeName" to nodeName, "percent" to percent),
            english = "Node \"$nodeName\" is $percent% full."
        )

        /** A server has been running below playable tick rate for long enough to not be a blip. */
        fun tpsLow(serverName: String, tps: String) = ServerAlertMessage(
            kind = ServerAlertKind.TPS_LOW,
            keys = listOf("message"),
            variables = mapOf("serverName" to serverName, "tps" to tps),
            english = "\"$serverName\" has been running at $tps TPS."
        )

        /**
         * A server's plugins have newer builds waiting.
         *
         * [names] arrives already joined, because a list rendered by Handlebars is a Java
         * `toString` with brackets in it, and the separator between plugin names is a typographic
         * decision a translator should be able to make.
         */
        fun pluginUpdates(serverName: String, count: Int, names: List<String>) = ServerAlertMessage(
            kind = ServerAlertKind.PLUGIN_UPDATES,
            keys = listOf("message"),
            variables = mapOf(
                "serverName" to serverName,
                "count" to count,
                "names" to names.joinToString(", ")
            ),
            english = "$count plugin(s) on \"$serverName\" have a newer version: ${names.joinToString(", ")}."
        )

        /** A schedule ran and something in it did not work. */
        fun scheduleFailed(serverName: String, scheduleName: String, error: String?) = ServerAlertMessage(
            kind = ServerAlertKind.SCHEDULE_FAILED,
            keys = listOfNotNull("message", error?.let { "error" }),
            variables = mapOf("serverName" to serverName, "scheduleName" to scheduleName, "error" to error),
            english = "Schedule \"$scheduleName\" on \"$serverName\" failed" + (error?.let { ": $it" } ?: ".")
        )
    }
}
