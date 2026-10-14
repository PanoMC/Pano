package com.panomc.platform.server.alert

/**
 * What one alert says, kept as values until the moment somebody is actually told.
 *
 * An alert is written down once and read by several people, who do not necessarily read the same
 * language. The `server_alert` row and the log line are the record and stay in [english]; the
 * e-mail is one per recipient, so it is rendered from [variables] by the `server-alert` mail template
 * of the kind (variant = [ServerAlertKind.id]) in whatever language that recipient reads Pano in.
 * Optional values (an exit code, the console line that explains a crash, the error a backup ended
 * with) are null when absent, and the template leaves their sentence out.
 */
data class ServerAlertMessage(
    val kind: ServerAlertKind,
    val variables: Map<String, Any?>,
    val english: String
) {
    companion object {
        /**
         * A managed server's process died on its own.
         *
         * [reason] is the console line that explains it and is already cleaned by the caller; it
         * gets its own sentence rather than being appended to the first one, because "Last console
         * line: …" is a label a translator has to be able to write themselves.
         */
        fun serverCrashed(serverName: String, exitCode: Int?, reason: String?) = ServerAlertMessage(
            kind = ServerAlertKind.SERVER_CRASHED,
            variables = mapOf("serverName" to serverName, "exitCode" to exitCode, "reason" to reason),
            english = "\"$serverName\" stopped on its own" + (exitCode?.let { " (exit code $it)" } ?: "") + "." +
                (reason?.let { " $it" } ?: "")
        )

        /** A node stopped answering, so nothing it runs can be reached. */
        fun nodeOffline(nodeName: String, serverCount: Int) = ServerAlertMessage(
            kind = ServerAlertKind.NODE_OFFLINE,
            variables = mapOf("nodeName" to nodeName, "serverCount" to serverCount),
            english = "Node \"$nodeName\" stopped answering" +
                if (serverCount > 0) "; $serverCount server(s) are out of reach." else "."
        )

        /** A backup task ended in FAILED, which means tonight has no copy of that world. */
        fun backupFailed(serverName: String, error: String?) = ServerAlertMessage(
            kind = ServerAlertKind.BACKUP_FAILED,
            variables = mapOf("serverName" to serverName, "error" to error),
            english = "A backup of \"$serverName\" failed" + (error?.let { ": $it" } ?: ".")
        )

        /** A node's data disk is nearly full. */
        fun diskLow(nodeName: String, percent: Int) = ServerAlertMessage(
            kind = ServerAlertKind.DISK_LOW,
            variables = mapOf("nodeName" to nodeName, "percent" to percent),
            english = "Node \"$nodeName\" is $percent% full."
        )

        /** A server has been running below playable tick rate for long enough to not be a blip. */
        fun tpsLow(serverName: String, tps: String) = ServerAlertMessage(
            kind = ServerAlertKind.TPS_LOW,
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
            variables = mapOf("serverName" to serverName, "scheduleName" to scheduleName, "error" to error),
            english = "Schedule \"$scheduleName\" on \"$serverName\" failed" + (error?.let { ": $it" } ?: ".")
        )
    }
}
